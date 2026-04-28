/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.lang.reflect.Method
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Production [NsdRegistrar] backed by Android's [NsdManager].
 *
 * Two cross-API quirks live here:
 *
 *  1. **Binary TXT records.** Quick Share's `n=` TXT key carries a
 *     packed binary [io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo].
 *     The binary-aware overload `setAttribute(String, ByteArray)` is the
 *     only path that preserves bytes verbatim; the public String-flavoured
 *     overload calls `value.getBytes("UTF-8")` internally, which would
 *     re-encode any byte >= 0x80 into two wire bytes and corrupt the
 *     payload.
 *
 *     `setAttribute(String, ByteArray)` became public API in
 *     `Build.VERSION_CODES.TIRAMISU` (API 33). On API 24-32 the same
 *     method exists in the platform with the same signature but is
 *     marked `@hide @UnsupportedAppUsage` (no `maxTargetSdk`), placing it
 *     on the unconditional greylist and therefore reflectively
 *     accessible from any target SDK. We reflect into it on those API
 *     levels rather than fall back to the lossy String overload.
 *
 *     The reflection target's signature was verified against AOSP
 *     `android/net/nsd/NsdServiceInfo.java` for API 35 and 36 — the
 *     method has existed unchanged since API 16, so the lookup is
 *     stable across every supported API level (24..32).
 *
 *  2. **Auto-suffixed instance names.** When Android observes a name
 *     collision on the LAN it appends ` (1)`, ` (2)`, etc. The
 *     registration callback's `onServiceRegistered(NsdServiceInfo)`
 *     argument carries the actual published name; we surface that via
 *     [NsdRegistrationHandle.instanceName] rather than trusting the
 *     name we requested.
 *
 * Failures during registration are translated to [IOException] so the
 * caller's catch block matches the JmDNS-era contract.
 */
internal class AndroidNsdRegistrar(
    private val nsdManager: NsdManager,
) : NsdRegistrar {
    override suspend fun register(
        serviceType: String,
        instanceName: String,
        port: Int,
        attributes: Map<String, ByteArray>,
    ): NsdRegistrationHandle {
        val serviceInfo = buildServiceInfo(serviceType, instanceName, port, attributes)

        return suspendCancellableCoroutine { cont ->
            val listener = RegistrationListener(cont, nsdManager)
            try {
                nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (
                @Suppress("TooGenericExceptionCaught") t: Throwable,
            ) {
                if (cont.isActive) {
                    cont.resumeWithException(IOException("NsdManager.registerService threw", t))
                }
                return@suspendCancellableCoroutine
            }

            cont.invokeOnCancellation {
                listener.cancel()
            }
        }
    }

    private fun buildServiceInfo(
        serviceType: String,
        instanceName: String,
        port: Int,
        attributes: Map<String, ByteArray>,
    ): NsdServiceInfo =
        NsdServiceInfo().apply {
            this.serviceName = instanceName
            this.serviceType = serviceType
            this.port = port
            applyAttributes(this, attributes)
        }

    /**
     * Reference-counted [NsdManager.RegistrationListener]. The first
     * `onServiceRegistered` callback resumes the suspending caller with
     * the actually-registered name. After that the listener stays
     * attached until [cancel] is invoked — that's the path the
     * [AndroidNsdRegistrationHandle.close] method takes to unregister.
     */
    private class RegistrationListener(
        private val continuation: CancellableContinuation<NsdRegistrationHandle>,
        private val nsdManager: NsdManager,
    ) : NsdManager.RegistrationListener {
        private val unregistered = AtomicBoolean(false)
        private val resumed = AtomicBoolean(false)

        @Volatile
        private var handle: AndroidNsdRegistrationHandle? = null

        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            // The platform may pass back a name that differs from the
            // requested one (auto-suffix on collision). Surface the
            // actual registered name to the caller.
            val registeredName = serviceInfo.serviceName ?: ""
            val host: InetAddress? = readHostAddress(serviceInfo)
            val newHandle =
                AndroidNsdRegistrationHandle(
                    instanceName = registeredName,
                    hostAddress = host,
                    listener = this,
                    nsdManager = nsdManager,
                )
            handle = newHandle
            if (resumed.compareAndSet(false, true) && continuation.isActive) {
                continuation.resume(newHandle)
            }
        }

        override fun onRegistrationFailed(
            serviceInfo: NsdServiceInfo,
            errorCode: Int,
        ) {
            if (resumed.compareAndSet(false, true) && continuation.isActive) {
                continuation.resumeWithException(
                    IOException("NsdManager.registerService failed (errorCode=$errorCode)"),
                )
            } else {
                Log.w(TAG, "NsdManager registration failed after resume: errorCode=$errorCode")
            }
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            handle?.markInactive()
        }

        override fun onUnregistrationFailed(
            serviceInfo: NsdServiceInfo,
            errorCode: Int,
        ) {
            // Best-effort: even if the platform refuses to unregister
            // (e.g. service already gone), the handle is dead from our
            // perspective.
            handle?.markInactive()
            Log.w(TAG, "NsdManager unregister failed errorCode=$errorCode")
        }

        fun cancel() {
            if (unregistered.compareAndSet(false, true)) {
                runCatching { nsdManager.unregisterService(this) }
            }
        }

        fun unregister() {
            cancel()
        }

        private fun readHostAddress(serviceInfo: NsdServiceInfo): InetAddress? =
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    serviceInfo.hostAddresses?.firstOrNull()
                } else {
                    @Suppress("DEPRECATION")
                    serviceInfo.host
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Throwable,
            ) {
                null
            }
    }

    /**
     * Concrete [NsdRegistrationHandle] returned to the caller. Owns the
     * platform listener registration; closing the handle dispatches
     * `unregisterService` and waits for `onServiceUnregistered` to
     * confirm — the parent `RegistrationListener` flips `isActive` to
     * false on either the success or failure callback.
     */
    private class AndroidNsdRegistrationHandle(
        override val instanceName: String,
        override val hostAddress: InetAddress?,
        private val listener: RegistrationListener,
        @Suppress("unused") private val nsdManager: NsdManager,
    ) : NsdRegistrationHandle {
        private val active = AtomicBoolean(true)

        override val isActive: Boolean
            get() = active.get()

        override fun close() {
            if (active.compareAndSet(true, false)) {
                listener.unregister()
            }
        }

        fun markInactive() {
            active.set(false)
        }
    }

    internal companion object {
        private const val TAG = Discovery.TAG

        /**
         * Cached reflective handle on `NsdServiceInfo.setAttribute(String, byte[])`.
         *
         * Resolved lazily so the cost of the JNI/Class lookup is paid
         * once per process. The method has existed unchanged since
         * API 16 (verified against AOSP `NsdServiceInfo.java` for
         * API 35 and 36) and is marked `@hide @UnsupportedAppUsage`
         * without a `maxTargetSdk`, so it is on the unconditional
         * greylist and reflectively accessible from any target SDK.
         */
        private val reflectiveSetAttribute: Method by lazy {
            try {
                NsdServiceInfo::class.java
                    .getDeclaredMethod(
                        "setAttribute",
                        String::class.java,
                        ByteArray::class.java,
                    ).apply { isAccessible = true }
            } catch (e: NoSuchMethodException) {
                // Defensive — should never fire on supported API levels.
                // We deliberately raise rather than silently fall back to
                // the lossy String overload, which would corrupt any
                // attribute byte >= 0x80 by re-encoding it as UTF-8.
                throw IllegalStateException(
                    "NsdServiceInfo.setAttribute(String, byte[]) not found via reflection; " +
                        "platform contract broken.",
                    e,
                )
            }
        }

        /**
         * Apply [attributes] to [serviceInfo].
         *
         * On API 33+ this calls the public byte[] overload of
         * `NsdServiceInfo.setAttribute` directly. On API 24-32 the same
         * underlying method is reflectively invoked because the public
         * String overload UTF-8-re-encodes its value (see
         * AOSP `NsdServiceInfo.setAttribute(String, String)` which calls
         * `value.getBytes("UTF-8")`), which would corrupt any
         * attribute byte >= 0x80.
         *
         * The [setBytes] callback exists as a test seam so unit tests
         * can drive the lambda without instantiating the platform
         * [NsdServiceInfo] class — the unit-test JAR's stub for that
         * class throws `ClassFormatError` even when the test never
         * calls a real setter.
         */
        @JvmStatic
        internal fun applyAttributes(
            serviceInfo: NsdServiceInfo,
            attributes: Map<String, ByteArray>,
        ): Unit =
            applyAttributes(
                attributes = attributes,
                setBytes = { key, value -> setBinaryAttribute(serviceInfo, key, value) },
            )

        /**
         * Test-friendly form of [applyAttributes]. The single setter
         * lambda always receives the raw [ByteArray] payload — there is
         * no second branch on API level inside this helper because both
         * API levels resolve to the same byte[]-based platform method
         * (one publicly, one reflectively).
         */
        @JvmStatic
        internal fun applyAttributes(
            attributes: Map<String, ByteArray>,
            setBytes: (String, ByteArray) -> Unit,
        ) {
            for ((key, value) in attributes) {
                setBytes(key, value)
            }
        }

        /**
         * Production binary-attribute setter. Picks the public byte[]
         * overload on API 33+ and falls back to a reflective invoke of
         * the same hidden method on API 24-32. Never delegates to the
         * String overload — that path is lossy for any byte >= 0x80.
         */
        private fun setBinaryAttribute(
            info: NsdServiceInfo,
            key: String,
            value: ByteArray,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                info.setAttribute(key, value)
            } else {
                try {
                    reflectiveSetAttribute.invoke(info, key, value)
                } catch (
                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                ) {
                    throw IllegalStateException(
                        "NsdServiceInfo.setAttribute(String, byte[]) reflective invoke failed " +
                            "for key=$key (sdkInt=${Build.VERSION.SDK_INT})",
                        t,
                    )
                }
            }
        }
    }
}
