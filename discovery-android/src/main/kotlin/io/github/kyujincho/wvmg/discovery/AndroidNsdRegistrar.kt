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
 *     `NsdServiceInfo.setAttribute(String, ByteArray)` exists only since
 *     API 33 (`Build.VERSION_CODES.TIRAMISU`); on earlier versions we
 *     fall back to `setAttribute(String, String)` and wrap the bytes in
 *     a Latin-1 / ISO-8859-1 string. ISO-8859-1 maps every byte
 *     0x00..0xFF to a single 1-to-1 codepoint, so the round-trip
 *     `bytes -> String(..., ISO_8859_1) -> getBytes(ISO_8859_1)` is
 *     bit-exact and the on-the-wire DNS-SD TXT record is identical to
 *     the API 33+ path. NearDrop, stock Quick Share, and Windows Quick
 *     Share all parse the TXT byte stream directly, so this is the
 *     critical interop seam.
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
         * Apply [attributes] to [serviceInfo] using the binary-aware
         * setter on API 33+ and the Latin-1 string fallback on
         * older versions.
         *
         * Latin-1 is chosen because every byte 0x00..0xFF round-trips
         * through `String(bytes, ISO_8859_1).toByteArray(ISO_8859_1)`
         * exactly. The platform's String-flavoured setter emits the
         * resulting String to the wire as raw bytes (it does not
         * UTF-8-re-encode the value), so the on-the-wire TXT record
         * is bit-identical to what the byte-array setter would produce.
         *
         * The [setBytes] / [setString] callbacks let unit tests drive
         * both branches without instantiating the platform
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
                sdkInt = Build.VERSION.SDK_INT,
                setBytes = { key, value -> serviceInfo.setAttribute(key, value) },
                setString = { key, value -> serviceInfo.setAttribute(key, value) },
            )

        /**
         * Test-friendly form of [applyAttributes]. The two setter
         * lambdas are independent so unit tests can pin which branch
         * fired.
         */
        @JvmStatic
        internal fun applyAttributes(
            attributes: Map<String, ByteArray>,
            sdkInt: Int,
            setBytes: (String, ByteArray) -> Unit,
            setString: (String, String) -> Unit,
        ) {
            for ((key, value) in attributes) {
                if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                    setBytes(key, value)
                } else {
                    // ISO-8859-1 (Latin-1) preserves every byte verbatim.
                    // The platform's String setter writes the codepoints
                    // back out as raw bytes, so the wire format matches
                    // the API 33+ binary-setter path byte-for-byte.
                    setString(key, String(value, Charsets.ISO_8859_1))
                }
            }
        }
    }
}
