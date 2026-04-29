/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery.medium

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import io.github.kyujincho.wvmg.protocol.medium.Medium
import io.github.kyujincho.wvmg.protocol.medium.MediumProvider
import io.github.kyujincho.wvmg.protocol.medium.UpgradePathCredentials
import io.github.kyujincho.wvmg.protocol.medium.UpgradedTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Issue #51 — Bluetooth RFCOMM bandwidth-upgrade medium.
 *
 * Stands up an RFCOMM service on the **server** (receiver) side and
 * connects to it on the **client** (sender) side. Throughput is
 * 1–2 Mbps in practice (Bluetooth Classic 4.x ~1 Mbps, 5.x ~2 Mbps);
 * the value of this rung is universal availability — every Android
 * device with a working radio can RFCOMM, even when no Wi-Fi network
 * is in range.
 *
 * Pairing is **not** required: we use the *insecure* RFCOMM variants
 * (`listenUsingInsecureRfcommWithServiceRecord` and
 * `createInsecureRfcommSocketToServiceRecord`) because the application
 * payload is already encrypted by UKEY2 + SecureChannel. Demanding
 * RFCOMM-layer pairing would just trigger a redundant OS pairing prompt
 * without adding meaningful security on top of what UKEY2 already
 * provides; the acceptance criteria on issue #51 spell this out
 * explicitly.
 *
 * ### Why this lives in `:discovery-android`
 *
 * `:core-protocol` is JVM-pure (no `android.*` imports — guarded in
 * code review). The provider therefore can't live in the protocol
 * module: it touches `BluetoothAdapter`, `BluetoothServerSocket`,
 * `BluetoothSocket`, runtime permission checks, and feature flags —
 * all `android.*`. The framework consumes us through
 * [MediumProvider] / [UpgradePathCredentials] / [UpgradedTransport],
 * none of which leak Android types into `:core-protocol`.
 *
 * ### Threading
 *
 *  - [isSupported] is called on the framework's caller thread (often
 *    the orchestrator's coroutine dispatcher). Pure synchronous polling
 *    of `BluetoothAdapter.isEnabled()` and `PackageManager` — safe to
 *    call anywhere.
 *  - [prepareUpgrade] / [adoptUpgrade] are `suspend` and dispatch their
 *    blocking I/O onto [Dispatchers.IO] internally. Closing the
 *    returned [BluetoothRfcommTransport] is safe from any thread.
 *
 * ### Cancellation
 *
 * `BluetoothServerSocket.accept()` and `BluetoothSocket.connect()` are
 * blocking syscalls; coroutine cancellation does **not** unblock them
 * while parked. Callers that want to cancel a pending accept/connect
 * MUST close the socket from another thread first (the close-before-
 * cancel pattern documented in the project's CLAUDE.md). That's the
 * orchestrator's responsibility once #54 wires this provider in.
 *
 * @property context Application context. Held weakly by Android only
 *   transitively (we re-resolve `BluetoothManager` per call rather than
 *   caching the adapter, so a Bluetooth toggle off→on is picked up the
 *   next time the provider is exercised).
 * @property bluetooth IO indirection — substituted in unit tests so the
 *   per-call surface (`isAvailable`, `listen`, `connect`) is mockable
 *   without pulling Robolectric into the module.
 * @property serviceUuid Stable SDP service-record UUID used for both the
 *   receiver-side `listenUsingInsecureRfcommWithServiceRecord` and the
 *   sender-side `createInsecureRfcommSocketToServiceRecord`. Defaults to
 *   [DEFAULT_SERVICE_UUID]; tests and debug builds may override.
 * @property serviceName SDP service-record human-readable name. Defaults
 *   to [DEFAULT_SERVICE_NAME]; not load-bearing on the wire (the UUID
 *   is the actual lookup key) but Android requires a non-null string.
 */
public class BluetoothRfcommMediumProvider internal constructor(
    private val bluetooth: BluetoothIo,
    public val serviceUuid: UUID = DEFAULT_SERVICE_UUID,
    public val serviceName: String = DEFAULT_SERVICE_NAME,
) : MediumProvider {
    /**
     * Production constructor. Wires up [DefaultBluetoothIo] over the
     * application [Context]'s [BluetoothManager].
     */
    public constructor(
        context: Context,
        serviceUuid: UUID = DEFAULT_SERVICE_UUID,
        serviceName: String = DEFAULT_SERVICE_NAME,
    ) : this(
        bluetooth = DefaultBluetoothIo(context.applicationContext),
        serviceUuid = serviceUuid,
        serviceName = serviceName,
    )

    /** The currently-listening server socket, if any. */
    private val activeServer: AtomicReference<BluetoothServerSocket?> = AtomicReference(null)

    override val medium: Medium = Medium.BLUETOOTH

    /**
     * Available iff the device has a Bluetooth adapter, the adapter is
     * enabled, and (API 31+) [Manifest.permission.BLUETOOTH_CONNECT] is
     * granted at runtime. Returns false eagerly on any pre-condition
     * miss — the framework treats that as "drop this medium from the
     * advertised set this lifecycle".
     */
    override fun isSupported(): Boolean = bluetooth.isAvailable()

    /**
     * Server (receiver) side. Open an RFCOMM listener and return the
     * MAC + UUID the sender will need.
     *
     * Returns `null` when the device cannot stand up the listener (no
     * adapter, Bluetooth off mid-call, missing CONNECT permission, IO
     * exception from `listenUsingInsecureRfcommWithServiceRecord`). The
     * orchestrator falls through to the next ladder rung in that case.
     *
     * The returned credentials carry the local adapter's MAC. The
     * sender uses it via `BluetoothAdapter.getRemoteDevice(macString)`
     * to materialise a `BluetoothDevice` for the connect call.
     *
     * The accepted [BluetoothSocket] is parked in [activeServer] for
     * [acceptUpgradedSocket] to claim. We leave the accept loop to the
     * caller (the orchestrator's per-medium thread) so this method
     * stays non-blocking — the listener is created and immediately
     * returns to the framework.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun prepareUpgrade(): UpgradePathCredentials? =
        withContext(Dispatchers.IO) {
            // Re-entrancy guard: close any previous listener before opening
            // a fresh one. A second prepareUpgrade in the same provider
            // lifetime is not expected today (the orchestrator builds one
            // per upgrade) but the safety belt is cheap.
            activeServer.getAndSet(null)?.closeQuietly()

            val mac = bluetooth.localMacAddressBytes() ?: return@withContext null
            val server =
                try {
                    bluetooth.listen(serviceName, serviceUuid)
                } catch (
                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                ) {
                    // listenUsingInsecureRfcommWithServiceRecord throws
                    // IOException when the SDP record can't be registered
                    // (adapter went off, etc.) and SecurityException when
                    // the runtime CONNECT permission was revoked between
                    // isSupported() and now. Both collapse to "can't
                    // upgrade"; surface as null for the framework.
                    Log.w(TAG, "RFCOMM listen failed; declining upgrade", t)
                    return@withContext null
                }
            if (server == null) {
                Log.w(TAG, "RFCOMM listen returned null; declining upgrade")
                return@withContext null
            }
            activeServer.set(server)
            UpgradePathCredentials.Bluetooth(
                macAddress = mac,
                serviceUuid = serviceUuid.toString(),
            )
        }

    /**
     * Server (receiver) side. Park on the listener returned from the
     * preceding [prepareUpgrade] and produce the [BluetoothSocket] for
     * the framework's CLIENT_INTRODUCTION read pump.
     *
     * Blocking — must be called from a worker thread / IO dispatcher.
     * Throws [IllegalStateException] if [prepareUpgrade] was not called
     * (or failed). Returns `null` if the listener was closed mid-accept
     * (e.g. by the orchestrator cancelling the upgrade).
     *
     * Note: this is NOT part of the [MediumProvider] interface — it's a
     * server-side hook the orchestrator will invoke on its own thread
     * after `UPGRADE_PATH_AVAILABLE` is on the wire. Until #54 wires it
     * in, only the unit tests in this module exercise this path.
     */
    public fun acceptUpgradedSocket(): BluetoothSocket? {
        val server =
            activeServer.get()
                ?: error("acceptUpgradedSocket called before prepareUpgrade returned non-null")
        return try {
            // accept() honours the timeout parameter when supplied; we
            // pass the project-wide RFCOMM accept timeout so a missing
            // CLIENT_INTRODUCTION doesn't park the IO thread forever.
            // The orchestrator can still pre-empt by closing the server
            // socket from another thread.
            server.accept(ACCEPT_TIMEOUT_MILLIS)
        } catch (io: IOException) {
            Log.w(TAG, "RFCOMM accept failed", io)
            null
        } finally {
            activeServer.getAndSet(null)?.closeQuietly()
        }
    }

    /**
     * Client (sender) side. Open an RFCOMM socket to the receiver using
     * the MAC + UUID it just sent in `UPGRADE_PATH_AVAILABLE`.
     *
     * Returns `null` when the bring-up fails (wrong credentials shape,
     * `BluetoothAdapter.getRemoteDevice` rejects the MAC, the connect
     * call times out / throws, etc.). The orchestrator emits
     * `UPGRADE_FAILURE` on a `null` return.
     *
     * The Android `BluetoothSocket.connect()` is a blocking syscall;
     * dispatched onto [Dispatchers.IO] so the caller's coroutine
     * dispatcher is not blocked.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun adoptUpgrade(credentials: UpgradePathCredentials): UpgradedTransport? =
        withContext(Dispatchers.IO) {
            if (credentials !is UpgradePathCredentials.Bluetooth) {
                Log.w(
                    TAG,
                    "adoptUpgrade rejecting non-Bluetooth credentials medium=${credentials.medium}",
                )
                return@withContext null
            }
            val uuid =
                runCatching { UUID.fromString(credentials.serviceUuid) }
                    .getOrElse {
                        Log.w(TAG, "adoptUpgrade: malformed service UUID '${credentials.serviceUuid}'", it)
                        return@withContext null
                    }
            val macString = credentials.macAddressString()
            val socket =
                try {
                    bluetooth.connect(macString, uuid)
                } catch (
                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                ) {
                    // createInsecureRfcommSocketToServiceRecord throws
                    // IllegalArgumentException for malformed MACs (we
                    // already validated, but the platform may disagree),
                    // IOException for "couldn't open a socket on this
                    // adapter", and SecurityException for revoked CONNECT.
                    // .connect() then throws IOException for refused /
                    // out-of-range / cancelled.
                    Log.w(TAG, "RFCOMM connect to $macString/$uuid failed", t)
                    return@withContext null
                }
            if (socket == null) {
                Log.w(TAG, "RFCOMM connect returned null; declining upgrade")
                return@withContext null
            }
            BluetoothRfcommTransport(socket)
        }

    /**
     * Indirection over the platform's `BluetoothManager` /
     * `BluetoothAdapter` surface. Lets unit tests substitute fakes
     * without pulling Robolectric in.
     *
     * The interface is intentionally narrow — every call here is a
     * single platform syscall — so a fake can implement it in a few
     * lines.
     */
    internal interface BluetoothIo {
        /**
         * Cheap polling check: device has an adapter, adapter is on,
         * runtime CONNECT permission granted (API 31+).
         */
        fun isAvailable(): Boolean

        /**
         * The adapter's own MAC, as 6 raw bytes. Returns `null` when
         * the device hides it (Android 6+ returns the sentinel
         * `02:00:00:00:00:00` to most apps without the deprecated
         * `LOCAL_MAC_ADDRESS` permission — we surface that as null so
         * the framework knows the medium is not actually usable here).
         */
        fun localMacAddressBytes(): ByteArray?

        /**
         * Open an RFCOMM listener via
         * `listenUsingInsecureRfcommWithServiceRecord(name, uuid)`.
         * Wrapped here so tests can return a fake server socket without
         * needing a real Bluetooth radio.
         */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun listen(
            name: String,
            uuid: UUID,
        ): BluetoothServerSocket?

        /**
         * Connect via
         * `createInsecureRfcommSocketToServiceRecord(uuid).connect()`.
         * Wrapped here so tests can return a fake socket without a real
         * radio.
         */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun connect(
            macAddress: String,
            uuid: UUID,
        ): BluetoothSocket?
    }

    /**
     * Production [BluetoothIo]. Re-resolves the adapter on every call
     * so a Bluetooth toggle (off → on between `isSupported` and
     * `prepareUpgrade`) is picked up.
     */
    private class DefaultBluetoothIo(
        private val context: Context,
    ) : BluetoothIo {
        @Suppress("ReturnCount") // Guard clauses are clearer than nested ifs here.
        override fun isAvailable(): Boolean {
            val pm = context.packageManager
            if (!pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) return false
            if (!hasConnectPermission()) return false
            val adapter = adapter() ?: return false
            return adapter.isEnabled
        }

        @Suppress("ReturnCount") // Each branch maps to a distinct platform failure mode.
        override fun localMacAddressBytes(): ByteArray? {
            val adapter = adapter() ?: return null
            // adapter.address: empty / null on early API levels;
            // Android 6+ returns the sentinel "02:00:00:00:00:00"
            // unless the caller holds the (non-grantable) LOCAL_MAC_ADDRESS
            // permission. Both cases mean "we don't have a useful MAC
            // to advertise" — return null so the framework drops the
            // upgrade attempt and falls through.
            val addr = runCatching { adapter.address }.getOrNull() ?: return null
            if (addr.isEmpty() || addr == LOCAL_MAC_ADDRESS_SENTINEL) return null
            return runCatching { UpgradePathCredentials.Bluetooth.macStringToBytes(addr) }
                .getOrNull()
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun listen(
            name: String,
            uuid: UUID,
        ): BluetoothServerSocket? {
            val adapter = adapter() ?: return null
            return adapter.listenUsingInsecureRfcommWithServiceRecord(name, uuid)
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        @Suppress("ReturnCount") // Each null check guards a distinct platform failure mode.
        override fun connect(
            macAddress: String,
            uuid: UUID,
        ): BluetoothSocket? {
            val adapter = adapter() ?: return null
            val device = adapter.getRemoteDevice(macAddress) ?: return null
            // Cancel discovery before connecting — Android docs are
            // explicit that an in-flight discovery dramatically slows
            // down RFCOMM connect attempts. We have no way of knowing
            // whether something else triggered discovery, so cancel
            // unconditionally; cancelDiscovery is a no-op when nothing
            // is in flight.
            runCatching { adapter.cancelDiscovery() }
            val socket = device.createInsecureRfcommSocketToServiceRecord(uuid) ?: return null
            socket.connect()
            return socket
        }

        private fun adapter(): BluetoothAdapter? {
            val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
            return manager.adapter
        }

        private fun hasConnectPermission(): Boolean {
            // Pre-API-31 uses the legacy install-time BLUETOOTH /
            // BLUETOOTH_ADMIN model — always granted if declared in the
            // manifest, so short-circuit the runtime check.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            return checkSPermission()
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun checkSPermission(): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
    }

    public companion object {
        private const val TAG: String = "WvmgBtRfcomm"

        /**
         * Default SDP service-record UUID. Chosen as a fresh
         * project-specific value (UUIDv4) rather than reusing the
         * stock Quick Share well-known UUID, because the stock receiver
         * is not what we're connecting to here — both peers are running
         * THIS app and pick the UUID symmetrically out of the
         * `BluetoothCredentials.service_name` field on the wire (the
         * proto carries it explicitly, so any value the receiver picks
         * works).
         *
         * Document any future change to this default in
         * `docs/testing/interop-bluetooth-rfcomm.md` so older builds can
         * still find the listener — older clients that hard-coded the
         * old UUID would fail to connect to a new server until they
         * pick up the new value from the wire credentials.
         */
        public val DEFAULT_SERVICE_UUID: UUID =
            UUID.fromString("a82efa21-ae5c-3dde-9bbc-f16da7b16c1a")

        /**
         * Default SDP service-record name. Not load-bearing on the
         * wire (the UUID is the actual lookup key) but Android requires
         * a non-null string for `listenUsingInsecureRfcommWithServiceRecord`.
         */
        public const val DEFAULT_SERVICE_NAME: String = "WvmgQuickShareRfcomm"

        /**
         * Sentinel MAC string Android 6+ returns from
         * `BluetoothAdapter.getAddress()` to apps that do not hold the
         * (un-grantable) `LOCAL_MAC_ADDRESS` permission. Treat as "no
         * usable MAC".
         */
        internal const val LOCAL_MAC_ADDRESS_SENTINEL: String = "02:00:00:00:00:00"

        /**
         * RFCOMM `accept()` timeout in milliseconds. Long enough that a
         * legitimate sender always reaches us (Bluetooth bring-up is
         * slow), short enough that an aborted upgrade doesn't park the
         * IO thread for the whole connection lifetime. The orchestrator
         * can still pre-empt by closing the server socket from another
         * thread.
         */
        internal const val ACCEPT_TIMEOUT_MILLIS: Int = 30_000
    }
}

/**
 * [UpgradedTransport] subtype carrying a connected [BluetoothSocket].
 *
 * Lives in `:discovery-android` rather than `:core-protocol` so the
 * `android.bluetooth.BluetoothSocket` type does not leak into the
 * JVM-pure protocol module. The framework treats it as opaque until
 * the SecureChannel is rebuilt around its input/output streams in #54.
 *
 * Implements [Closeable] so the orchestrator can dispose of the socket
 * (and thus unblock any reader parked in the platform's blocking
 * `read()`) on cancellation. `close()` is idempotent.
 */
public data class BluetoothRfcommTransport(
    val socket: BluetoothSocket,
) : UpgradedTransport,
    Closeable {
    override val medium: Medium = Medium.BLUETOOTH

    override fun close() {
        socket.closeQuietly()
    }
}

/**
 * Swallow `IOException`s from `Closeable.close()`. The platform's
 * `BluetoothSocket.close` and `BluetoothServerSocket.close` both throw
 * `IOException` on already-closed sockets, which is noise we never
 * want to propagate in cleanup paths.
 */
private fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
        // intentional: cleanup paths never propagate IOException.
    }
}
