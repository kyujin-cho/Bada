/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:Suppress(
    "MagicNumber", // 6-byte MAC, 2-hex-digit octets, 16-radix, 0xFF mask are well-known.
    "ReturnCount", // Per-octet validation reads cleanest with early `null` returns.
    "SwallowedException", // BT close/teardown errors are unactionable; collapse to no-op + log.
)

package io.github.kyujincho.wvmg.discovery.medium

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * Thin indirection over the Android Bluetooth APIs that
 * [BleL2capMediumProvider] relies on.
 *
 * The interface only exposes the calls we actually make — listening for
 * an L2CAP CoC channel, opening one client-side, and the small set of
 * pre-flight checks (permission grant, hardware feature, BT enabled).
 * Because the provider talks to Android exclusively through this
 * interface, every code path inside the provider can be unit-tested on
 * the JVM with a hand-written fake — there is no Robolectric / android
 * stub dependency in `:discovery-android`'s test classpath.
 *
 * All members that touch the platform are gated on API 29 + the
 * appropriate runtime permission via `@RequiresApi` /
 * `@RequiresPermission` annotations so callers see the gate at the
 * compiler level.
 *
 * @see DefaultBluetoothL2capIo for the production implementation.
 */
public interface BluetoothL2capIo {
    /**
     * Current `Build.VERSION.SDK_INT`. Surfaced through the interface
     * so JVM unit tests can simulate the API-29 gate without touching
     * the `android.jar` `Build.VERSION` class — its methods have no
     * `Code` attribute on the stub jar, which crashes reflection-based
     * field overrides at JUnit's class-resolution stage.
     */
    public val apiLevel: Int

    /**
     * True iff `BLUETOOTH_CONNECT` is currently granted at runtime
     * (API 31+) or the legacy `BLUETOOTH` install-time permission is
     * present (API 29–30, where the runtime grant does not apply).
     */
    public fun hasConnectPermission(): Boolean

    /**
     * True iff the device declares `FEATURE_BLUETOOTH_LE`. Used as the
     * hardware-level gate for [BleL2capMediumProvider.isSupported].
     */
    public fun hasBleHardware(): Boolean

    /**
     * True iff the local `BluetoothAdapter` is non-null, currently
     * enabled (`BluetoothAdapter.STATE_ON`), and the user has not
     * toggled BT off since the provider was constructed. Provider
     * checks this from [BleL2capMediumProvider.isSupported] so that a
     * BT-off device drops out of the supported-mediums advertisement.
     */
    public fun isBluetoothEnabled(): Boolean

    /**
     * Allocate a listening L2CAP server socket and return its
     * receiver-side bring-up parameters.
     *
     * Implementations call `BluetoothAdapter.listenUsingInsecureL2capChannel()`
     * and pair the resulting `BluetoothServerSocket` with the local
     * adapter MAC. Returns `null` if the platform refuses (BT off, no
     * peripheral mode, IO error). The returned [Listener] takes
     * ownership of the socket — the caller MUST close it when the
     * upgrade either completes or aborts.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public fun listen(): Listener?

    /**
     * Open an L2CAP CoC client channel to [macAddress] / [psm].
     *
     * Implementations resolve `macAddress` via
     * `BluetoothAdapter.getRemoteDevice(...)` and then call
     * `BluetoothDevice.createInsecureL2capChannel(psm).connect()`.
     * Returns `null` on any IO error so the framework can fall back to
     * the next ladder rung without translating Android exceptions.
     *
     * @param macAddress canonical `"AA:BB:CC:DD:EE:FF"` form (uppercase
     *   hex, colon-delimited). The provider formats the on-the-wire
     *   credentials this way; callers should not need to reformat.
     *
     * The return type is the [L2capChannel] wrapper rather than the raw
     * `BluetoothSocket` so JVM unit tests do not need to materialize a
     * `BluetoothSocket` instance — `android.jar`'s stub of that class
     * has no `Code` attribute and any test that even references it
     * fails at JUnit's class-resolution stage with `ClassFormatError`.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public fun connect(
        macAddress: String,
        psm: Int,
    ): L2capChannel?

    /**
     * Local adapter MAC bytes (6 bytes, big-endian) used by the
     * server-side provider when packing the upgrade-credentials frame.
     * Returns `null` if the adapter is missing or the address cannot be
     * read (hidden by the OS on API 23+ — see implementation note).
     */
    public fun localMacBytes(): ByteArray?

    /**
     * Handle to a listening L2CAP server socket plus the assigned PSM.
     * The provider hands this back through [BleL2capMediumProvider.prepareUpgrade]
     * inside an [BleL2capUpgradedTransport]; the framework's accept hook
     * (#54) waits on [accept] for the sender's reconnect.
     */
    public interface Listener {
        /** PSM assigned by the kernel for this listening channel. */
        public val psm: Int

        /**
         * Block waiting for the sender to connect, then return the
         * accepted [BluetoothSocket]. Throws if the underlying server
         * socket is closed before a connection arrives. The caller MUST
         * close the returned socket when done.
         */
        @RequiresApi(Build.VERSION_CODES.Q)
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public fun accept(): L2capChannel

        /** Stop listening. Idempotent. */
        public fun close()
    }
}

/**
 * Connected L2CAP CoC channel — Android-side wrapper around
 * `BluetoothSocket`'s stream pair plus close/peer accessors.
 *
 * Exists for two reasons:
 *
 *  1. **Testability**: `android.jar`'s stub of `BluetoothSocket` does
 *     not have a `Code` attribute on its methods, which crashes
 *     JUnit's class-resolution. Test fakes that need to return a
 *     channel can implement this interface with plain JVM streams.
 *  2. **Clean ownership boundary**: the framework's transport-swap
 *     hook (#54) reads/writes through the I/O streams and tears the
 *     channel down via [close]; it never needs to touch
 *     `BluetoothSocket` directly.
 */
public interface L2capChannel : Closeable {
    /** Read-side of the channel; backed by `BluetoothSocket.inputStream`. */
    public val inputStream: InputStream

    /** Write-side of the channel; backed by `BluetoothSocket.outputStream`. */
    public val outputStream: OutputStream

    /** Close the channel and tear down the underlying socket. Idempotent. */
    public override fun close()
}

/**
 * Production [L2capChannel] backed by a real `BluetoothSocket`. Lives
 * in `:discovery-android` so `:core-protocol` never sees the type.
 */
public class BluetoothL2capChannel(
    private val socket: BluetoothSocket,
) : L2capChannel {
    override val inputStream: InputStream get() = socket.inputStream
    override val outputStream: OutputStream get() = socket.outputStream

    override fun close() {
        try {
            socket.close()
        } catch (
            // close on a torn-down socket throws IOException; nothing
            // to do — the channel is already gone from the kernel's
            // perspective.
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            // no-op
        }
    }
}

/**
 * Production [BluetoothL2capIo]. Talks directly to the platform
 * `BluetoothManager`. Lives in `:discovery-android` so `:core-protocol`
 * never imports `android.*`.
 */
public class DefaultBluetoothL2capIo(
    context: Context,
) : BluetoothL2capIo {
    /** Use the application context so we never accidentally pin an Activity. */
    private val appContext: Context = context.applicationContext

    override val apiLevel: Int = Build.VERSION.SDK_INT

    private val adapter: BluetoothAdapter?
        get() {
            val manager =
                appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    ?: return null
            return manager.adapter
        }

    override fun hasConnectPermission(): Boolean {
        // API 29–30 used the legacy install-time BLUETOOTH /
        // BLUETOOTH_ADMIN permissions; they are always granted when
        // declared, so we short-circuit. API 31+ uses the runtime
        // BLUETOOTH_CONNECT grant.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun hasBleHardware(): Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    override fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    @RequiresApi(Build.VERSION_CODES.Q)
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun listen(): BluetoothL2capIo.Listener? {
        val adapter = adapter ?: return null
        return try {
            val server = adapter.listenUsingInsecureL2capChannel()
            DefaultListener(server)
        } catch (
            // listenUsingInsecureL2capChannel can throw IOException
            // when the radio rejects the request and SecurityException
            // when BLUETOOTH_CONNECT is revoked between the pre-flight
            // and the platform call. Both map to "skip the upgrade".
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun connect(
        macAddress: String,
        psm: Int,
    ): L2capChannel? {
        val adapter = adapter ?: return null
        return try {
            val device: BluetoothDevice = adapter.getRemoteDevice(macAddress)
            val socket = device.createInsecureL2capChannel(psm)
            socket.connect()
            BluetoothL2capChannel(socket)
        } catch (
            // getRemoteDevice throws IllegalArgumentException for a
            // malformed MAC; createInsecureL2capChannel /
            // connect throw IOException on radio errors and
            // SecurityException on revoked permission. Collapse all to
            // null so the framework can demote the medium cleanly.
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            null
        }
    }

    override fun localMacBytes(): ByteArray? {
        // BluetoothAdapter.getAddress() has been a sandbox sentinel
        // ("02:00:00:00:00:00") since API 23 for non-system apps. The
        // peer never actually uses the MAC to authenticate — it
        // re-derives identity from the UKEY2 session keys — but it does
        // use it to call BluetoothAdapter.getRemoteDevice(), which only
        // needs the bytes to be syntactically valid. Returning the
        // sentinel here is therefore acceptable and matches what every
        // other Android Quick Share peer ends up sending.
        val raw = adapter?.address ?: return null
        return parseMacAddress(raw)
    }

    /**
     * Adapter for `BluetoothServerSocket` that exposes the assigned PSM
     * via the API 29+ `BluetoothServerSocket.getPsm()` accessor.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private class DefaultListener(
        private val server: BluetoothServerSocket,
    ) : BluetoothL2capIo.Listener {
        override val psm: Int = server.psm

        @RequiresApi(Build.VERSION_CODES.Q)
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun accept(): L2capChannel = BluetoothL2capChannel(server.accept())

        override fun close() {
            try {
                server.close()
            } catch (
                // close on a server socket can throw IOException when
                // the kernel has already torn the channel down. Nothing
                // to recover; swallow.
                @Suppress("TooGenericExceptionCaught") t: Throwable,
            ) {
                // no-op
            }
        }
    }

    private companion object {
        /** Parse `"AA:BB:CC:DD:EE:FF"` into 6 bytes, or `null`. */
        fun parseMacAddress(value: String): ByteArray? {
            val parts = value.split(':')
            if (parts.size != 6) return null
            val out = ByteArray(6)
            for ((i, octet) in parts.withIndex()) {
                if (octet.length != 2) return null
                val byte = octet.toIntOrNull(16) ?: return null
                if (byte !in 0..0xFF) return null
                out[i] = byte.toByte()
            }
            return out
        }
    }
}
