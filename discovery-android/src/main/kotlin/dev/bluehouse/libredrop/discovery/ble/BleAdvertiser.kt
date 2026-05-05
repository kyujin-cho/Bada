/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:android.annotation.SuppressLint("MissingPermission")
@file:Suppress("MagicNumber") // Bluetooth API status codes are well-known.

package dev.bluehouse.libredrop.discovery.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Sender-side BLE advertiser that emits the Quick Share service-data pulse
 * (#32). Stock Quick Share / NearDrop receivers listen for this 24-byte
 * payload and, on seeing it, enable their mDNS responder so the sender's
 * Wi-Fi LAN discovery can find them.
 *
 * Lifecycle:
 *
 *  1. The caller (typically `SendActivity` after the share intent is
 *     parsed) constructs a [BleAdvertiser] for an application [Context].
 *  2. [start] returns a [BleAdvertiseHandle] when the platform accepts the
 *     advertisement, or `null` when the device cannot advertise (no
 *     adapter, no peripheral mode, Bluetooth off, missing
 *     `BLUETOOTH_ADVERTISE` permission, or the platform's
 *     [BluetoothLeAdvertiser.startAdvertising] callback reports failure).
 *  3. The handle's [close] stops the advertisement. Closing twice is a
 *     no-op; the advertiser may be re-`start`ed after close.
 *
 * The Quick Share advertisement is connectable to match stock sender
 * pulses. We still only need the receiver's BLE scan loop to wake up and
 * start its reachable bootstrap surface; connectability and the non-zero
 * `secret_id_hash` are classification signals for that active-send path.
 *
 * Threading: [start] / [close] are safe to call from any thread; the
 * underlying `BluetoothLeAdvertiser` callbacks fire on the main thread.
 *
 * Permissions:
 *  * API 31+ requires `BLUETOOTH_ADVERTISE` granted at runtime. Onboarding
 *    requests it (#31), but we still re-check on [start] because the user
 *    can revoke from Settings. A revoked permission yields `null` rather
 *    than a [SecurityException].
 *  * API 24–30 uses the legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` model
 *    which is install-time and therefore always granted if declared.
 */
public class BleAdvertiser internal constructor(
    private val provider: AdvertiserProvider,
    private val payloadFactory: () -> ByteArray,
    private val now: () -> Long,
) {
    /**
     * The currently-live handle, if any. [start] swaps in a new handle
     * after closing the previous one (re-entrancy guard); [close] paths
     * use [java.util.concurrent.atomic.AtomicReference.compareAndSet] so
     * a late `close` from a stale handle doesn't clobber a fresh
     * advertisement that the same instance opened in the meantime.
     */
    private val active: AtomicReference<BleAdvertiseHandleImpl?> = AtomicReference(null)

    /**
     * Production constructor. Wires up the Android `BluetoothManager` and
     * a fresh [SecureRandom] for the payload's random salt byte.
     *
     * @param endpointId the sender's 4-byte ASCII slug. The pulse's
     *   `secret_id_hash` is the truncated SHA-256 of this value, which
     *   stock GMS receivers inspect to classify the pulse as an active
     *   `type=NOTIFY` share instead of `type=SILENT`. Must be non-empty.
     */
    public constructor(context: Context, endpointId: String) : this(
        provider = DefaultAdvertiserProvider(context.applicationContext),
        payloadFactory = { BleAdvertisePayload.build(endpointId, SecureRandom()) },
        now = System::currentTimeMillis,
    )

    /**
     * Begin advertising. Returns `null` when the platform cannot start —
     * see class kdoc for the full failure list. Callers should treat
     * `null` as "BLE pulse unavailable, continue with mDNS-only", per the
     * issue's fallback acceptance criterion.
     *
     * Idempotent within a single instance: calling [start] while a previous
     * advertisement is still running closes the old handle first.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    public fun start(): BleAdvertiseHandle? {
        // Re-entrancy: if a prior advertisement is still live, close it
        // first so we don't leak the BluetoothLeAdvertiser callback.
        active.getAndSet(null)?.close()

        // Pre-flight check: collapse the "no permission" and "no
        // advertiser available" branches into a single early-return so
        // the success path reads top-to-bottom without nested guards.
        val advertiser = preflight() ?: return null

        val payload = payloadFactory()
        val settings = buildSettings()
        val data = buildAdvertiseData(payload)

        val callback = AdvertiseCallbackImpl()
        return try {
            advertiser.startAdvertising(settings, data, callback)
            val handle =
                BleAdvertiseHandleImpl(
                    advertiser = advertiser,
                    callback = callback,
                    payload = payload,
                    startedAt = now(),
                    onClosed = { active.compareAndSet(it, null) },
                )
            active.set(handle)
            Log.w(
                TAG,
                "BLE advertise: startAdvertising submitted bytes=" + payload.size +
                    " uuid=" + BleAdvertisePayload.SERVICE_UUID_128,
            )
            handle
        } catch (
            // BluetoothLeAdvertiser surfaces a mix of IllegalArgumentException
            // (payload too large), IllegalStateException (Bluetooth turned
            // off mid-call), and SecurityException (permission revoked
            // between checkSelfPermission and startAdvertising). All of
            // them mean "we couldn't start"; collapse to null + warn.
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            Log.w(TAG, "BLE advertise: startAdvertising threw — pulse skipped", t)
            null
        }
    }

    /**
     * Resolve the platform [BluetoothLeAdvertiser] for this start call,
     * or `null` if any of the pre-conditions fail (permission revoked,
     * device has no peripheral mode, Bluetooth turned off). Each failure
     * is logged so on-device diagnostics still attribute the skip.
     */
    private fun preflight(): BluetoothLeAdvertiser? {
        if (!provider.hasAdvertisePermission()) {
            Log.w(TAG, "BLE advertise: BLUETOOTH_ADVERTISE not granted — pulse skipped")
            return null
        }
        val advertiser = provider.advertiser()
        if (advertiser == null) {
            Log.w(TAG, "BLE advertise: no BluetoothLeAdvertiser available — pulse skipped")
        }
        return advertiser
    }

    /**
     * Internal indirection over `BluetoothManager.adapter.bluetoothLeAdvertiser`
     * + [ContextCompat.checkSelfPermission]. Lets unit tests substitute
     * fakes without pulling Robolectric into this module.
     */
    internal interface AdvertiserProvider {
        fun hasAdvertisePermission(): Boolean

        fun advertiser(): BluetoothLeAdvertiser?
    }

    /**
     * Production provider. Pulls the adapter + advertiser from the system
     * `BluetoothManager` on every call so a Bluetooth toggle (off → on) is
     * picked up the next time [start] runs.
     */
    private class DefaultAdvertiserProvider(
        private val context: Context,
    ) : AdvertiserProvider {
        override fun hasAdvertisePermission(): Boolean {
            // Pre-API-31 devices use the legacy install-time BLUETOOTH /
            // BLUETOOTH_ADMIN permissions; they are always granted when
            // declared, so we short-circuit the runtime check.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            return checkSPermission()
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun checkSPermission(): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ) == PackageManager.PERMISSION_GRANTED

        override fun advertiser(): BluetoothLeAdvertiser? {
            // adapter.bluetoothLeAdvertiser returns null when the adapter
            // is off, when the device lacks peripheral mode, or when the
            // chipset's BLE stack hasn't finished initializing yet. All
            // three map cleanly to "skip the pulse, fall back to mDNS-only".
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter: BluetoothAdapter? = manager?.adapter
            return adapter?.bluetoothLeAdvertiser
        }
    }

    /**
     * Default [AdvertiseSettings] for the Quick Share pulse:
     *   * `LOW_LATENCY` mode — receivers should see us within ~100ms.
     *   * `TX_POWER_HIGH` — best chance of a wake-up across a typical
     *     room.
     *   * `connectable = true` — stock Quick Share senders advertise as
     *     connectable. Samsung empirics show this is one of the signals
     *     used to distinguish an active share pulse from passive
     *     discovery noise.
     */
    private fun buildSettings(): AdvertiseSettings =
        AdvertiseSettings
            .Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

    /**
     * Build the [AdvertiseData] with only the 16-bit Quick Share
     * service-data AD. The 16-bit service-UUID is embedded *inside* the
     * service-data structure (AD type 0x16), so there is no need for a
     * separate "Complete List of 16-bit Service UUIDs" AD — the
     * receiver's [ScanFilter] from #33 matches on service data, not on
     * the service-UUID list, so the extra AD has no consumer.
     *
     * Including both pushed the total advertising payload to 32 bytes
     * (4 for the redundant UUID list + 28 for service data), one byte
     * over the 31-byte legacy budget, causing
     * `AdvertiseCallback.onStartFailure(ADVERTISE_FAILED_DATA_TOO_LARGE)`
     * on real hardware. Found while running the BLE-trigger interop
     * runbook against the Vivo X300 Ultra (Funtouch 16 / OriginOS 6).
     *
     * Final payload size, well under 31 bytes:
     *   * 4 bytes for the service-data AD header (length+type+16-bit UUID),
     *   * 24 bytes for the Quick Share service-data payload itself.
     */
    private fun buildAdvertiseData(payload: ByteArray): AdvertiseData {
        val parcelUuid = ParcelUuid(UUID.fromString(BleAdvertisePayload.SERVICE_UUID_128))
        return AdvertiseData
            .Builder()
            .addServiceData(parcelUuid, payload)
            // Skip device name and TX power level — they would push us
            // over the 31-byte budget and aren't part of the Quick Share
            // wire spec.
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
    }

    /**
     * Concrete [BleAdvertiseHandle] backed by a live
     * `BluetoothLeAdvertiser` callback registration.
     */
    private class BleAdvertiseHandleImpl(
        private val advertiser: BluetoothLeAdvertiser,
        private val callback: AdvertiseCallbackImpl,
        override val payload: ByteArray,
        override val startedAt: Long,
        private val onClosed: (BleAdvertiseHandleImpl) -> Unit,
    ) : BleAdvertiseHandle {
        private val activeFlag = AtomicBoolean(true)

        override val isActive: Boolean get() = activeFlag.get()

        override val lastError: Int? get() = callback.lastError

        override fun close() {
            if (!activeFlag.compareAndSet(true, false)) return
            try {
                advertiser.stopAdvertising(callback)
                Log.w(TAG, "BLE advertise: stopAdvertising complete")
            } catch (
                // stopAdvertising can throw SecurityException if the user
                // revoked BLUETOOTH_ADVERTISE while we were running, or
                // IllegalStateException if Bluetooth was turned off
                // mid-flight. Either way, the advertisement is gone and
                // there is nothing to recover.
                @Suppress("TooGenericExceptionCaught") t: Throwable,
            ) {
                Log.w(TAG, "BLE advertise: stopAdvertising threw; ignoring", t)
            }
            onClosed(this)
        }
    }

    /**
     * Captures the most recent `onStartFailure` error code from
     * [BluetoothLeAdvertiser]. Callers that surface diagnostics (the
     * receiver-side BLE scanner test harness, future debug screens) can
     * read [BleAdvertiseHandle.lastError] to attribute "no peer woke up"
     * to the platform vs to wire issues.
     */
    private class AdvertiseCallbackImpl : AdvertiseCallback() {
        @Volatile
        var lastError: Int? = null
            private set

        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.w(TAG, "BLE advertise: onStartSuccess settings=$settingsInEffect")
        }

        override fun onStartFailure(errorCode: Int) {
            lastError = errorCode
            Log.w(TAG, "BLE advertise: onStartFailure code=$errorCode (${describeError(errorCode)})")
        }

        private fun describeError(code: Int): String =
            when (code) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
                ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                else -> "UNKNOWN"
            }
    }

    public companion object {
        /** Shared logcat tag with the rest of the discovery module. */
        internal const val TAG: String = "LibreDropDiscovery"
    }
}

/**
 * Closeable lifecycle handle returned by [BleAdvertiser.start]. Closing the
 * handle stops the underlying BLE advertisement; subsequent close calls are
 * no-ops.
 */
public interface BleAdvertiseHandle : Closeable {
    /** True until [close] runs successfully. */
    public val isActive: Boolean

    /** The 24-byte service-data payload submitted to the platform. */
    public val payload: ByteArray

    /** `System.currentTimeMillis()` at the moment [BleAdvertiser.start] returned. */
    public val startedAt: Long

    /**
     * Most recent `BluetoothLeAdvertiser` `onStartFailure` error code,
     * or `null` if no failure has been reported. Mirrors
     * `AdvertiseCallback.ADVERTISE_FAILED_*`.
     */
    public val lastError: Int?

    /** Stop the advertisement. Idempotent. */
    public override fun close()
}
