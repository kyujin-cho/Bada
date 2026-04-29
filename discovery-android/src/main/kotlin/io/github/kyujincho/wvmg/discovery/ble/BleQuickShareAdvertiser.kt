/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery.ble

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
import io.github.kyujincho.wvmg.protocol.endpoint.BleServiceData
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Receiver-side BLE pulse advertiser for Quick Share (issue #121).
 *
 * The symmetric counterpart to [BleQuickShareScanner]. While the scanner
 * watches for **sender** pulses on service UUID `0xFE2C`, this class
 * advertises the **receiver's** identity on the canonical Quick Share
 * fast-advertisement service UUID `0xFEF3`
 * ([BleServiceData.SERVICE_UUID_128_STRING]). The two channels close the
 * loop: stock Quick Share peers cross-reference Wi-Fi LAN services they
 * discover against recently-seen BLE pulses on `0xFEF3`, and silently
 * drop mDNS-only peers from their picker. Without an advertiser on this
 * UUID, WVMG is invisible to Galaxy / One UI's send sheet — that was
 * exactly the symptom commit c0150be diagnosed.
 *
 * ### Wire format
 *
 * The service-data payload is built by [BleServiceData.encode] in
 * `:core-protocol` (pure-JVM, KAT-tested). The shape captured from a
 * Galaxy peer's broadcast is:
 *
 * ```text
 * [ versPCP(1) | endpoint_id(4 ASCII) | endpoint_info_len(1) | EndpointInfo(N) ]
 * ```
 *
 * For our hidden, version=1, PHONE EndpointInfo (matching
 * `ReceiverForegroundService.defaultEndpointInfo`) the total payload is
 * **23 bytes**, which fits alongside the 16-bit service-data AD header
 * inside the legacy 31-byte advertising-PDU budget without needing
 * extended advertising.
 *
 * ### Lifecycle
 *
 * Owned by [io.github.kyujincho.wvmg.service.receiver.ReceiverForegroundService],
 * driven through the existing [io.github.kyujincho.wvmg.service.receiver.MdnsAdvertisementGate].
 * The gate already decides when the receiver should be visible based on
 * BLE *scan* activity, the user's "always visible" override, and the QR
 * session flag; this advertiser's [start] / [stop] methods are wired off
 * the same publish/unpublish decisions so BLE and mDNS are advertised
 * (and unpublished) symmetrically.
 *
 * The class shape mirrors [BleQuickShareScanner]:
 *  * [start] is idempotent and non-suspending. Calling [start] while
 *    already advertising is a no-op.
 *  * [stop] is idempotent. Safe to call from any thread; safe to call
 *    inline from `Service.onDestroy`.
 *  * [setAdvertiseMode] switches between [AdvertiseSettings.ADVERTISE_MODE_BALANCED]
 *    (background) and [AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY]
 *    (foreground) — symmetric to the scanner's BALANCED ↔ LOW_LATENCY
 *    pattern from #35. Idempotent if the mode is unchanged.
 *
 * ### Failure modes
 *
 * Like [BleAdvertiser] (the sender-side pulse), [start] returns `false`
 * — and logs without throwing — when:
 *  * `BLUETOOTH_ADVERTISE` is not granted at runtime (API 31+).
 *  * The device has no BLE peripheral mode
 *    (`BluetoothAdapter.bluetoothLeAdvertiser` returns null).
 *  * The Bluetooth adapter is turned off.
 *  * The platform's `startAdvertising` callback reports failure.
 *
 * In every case the receiver continues in mDNS-only mode — the picker
 * ranking degrades, but transfers still work. This matches the issue's
 * acceptance criterion: "On devices without peripheral-mode support,
 * degrade gracefully to mDNS-only without crashing or spamming logs."
 *
 * ### Permissions
 *
 * On API 31+ the runtime [Manifest.permission.BLUETOOTH_ADVERTISE] is
 * required. The umbrella manifest in `:app` declares it; the onboarding
 * flow in `:app/.../onboarding` requests it as an optional permission.
 * Pre-API-31 devices fall back to the install-time legacy
 * `BLUETOOTH` / `BLUETOOTH_ADMIN` permissions also declared in the
 * umbrella manifest.
 *
 * ### Testability
 *
 * [BleAdvertiserGate] abstracts the platform `BluetoothLeAdvertiser` so
 * unit tests on a plain JVM can drive the lifecycle through a fake.
 * Tests that need to observe a mode change inject a recorder and assert
 * on the captured `setAdvertiseMode` calls — see
 * `BleQuickShareAdvertiserTest`.
 *
 * @param gate platform-advertiser abstraction; tests inject a fake.
 * @param permissionChecker checks `BLUETOOTH_ADVERTISE` at start time;
 *   defaults to a [ContextCompat]-backed implementation in production.
 * @param payloadFactory builds the 23-byte service-data payload from
 *   the supplied [EndpointInfo] and the latest endpoint_id. Defaults to
 *   [BleServiceData.encode].
 */
public class BleQuickShareAdvertiser internal constructor(
    private val gate: BleAdvertiserGate,
    private val permissionChecker: AdvertisePermissionChecker,
    private val payloadFactory: PayloadFactory = DefaultPayloadFactory,
) {
    /**
     * Production constructor. Builds a real [BleAdvertiserGate] over the
     * platform [BluetoothManager] and a [ContextCompat]-backed
     * permission checker. Only the application context is retained.
     */
    public constructor(context: Context) : this(
        gate = AndroidBleAdvertiserGate(context.applicationContext),
        permissionChecker = AndroidAdvertisePermissionChecker(context.applicationContext),
    )

    /**
     * Synchronization guard for [start] / [stop] / [setAdvertiseMode]
     * transitions. Same shape as the scanner — a plain `synchronized`
     * block keeps the lifecycle inline-callable from `Service.onDestroy`,
     * which the coroutine-based `Mutex` would not survive once the
     * parent scope is cancelled.
     */
    private val lifecycleLock = Any()

    /**
     * The currently-active platform registration, if any. Wrapped in an
     * AtomicReference so a late asynchronous callback (e.g. an
     * `onStartFailure` arriving after [stop]) cannot clobber a fresh
     * registration the same instance opened in the meantime.
     */
    private val active: AtomicReference<BleAdvertiserGate.Registration?> =
        AtomicReference(null)

    /**
     * Currently-active platform advertise mode. Defaults to
     * [AdvertiseSettings.ADVERTISE_MODE_BALANCED] — the foreground-service
     * friendly mode that does not throttle and that most receivers see
     * within a few hundred milliseconds. Foregrounded apps upgrade to
     * [AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY] for the duration.
     *
     * Mutated through [setAdvertiseMode] under [lifecycleLock]; reads
     * outside the lock are racy in the strict sense but the field is
     * `@Volatile` and only used as a "do nothing if mode unchanged"
     * early-exit in [setAdvertiseMode] itself plus a debug accessor.
     */
    @Volatile
    private var currentMode: Int = AdvertiseSettings.ADVERTISE_MODE_BALANCED

    /**
     * Most recently submitted [EndpointInfo]. Captured during [start]
     * and re-used by [setAdvertiseMode] to rebuild the service-data
     * payload during a re-registration. `null` while the advertiser is
     * not running.
     */
    @Volatile
    private var currentEndpointInfo: EndpointInfo? = null

    /**
     * Most recently submitted endpoint_id (4 ASCII bytes). Same lifetime
     * as [currentEndpointInfo].
     */
    @Volatile
    private var currentEndpointId: ByteArray? = null

    /**
     * Begin advertising the receiver's BLE pulse with the supplied
     * identity. Returns `true` iff the platform accepted the
     * registration (or the advertiser was already running on the same
     * identity — idempotent).
     *
     * Calling [start] while already advertising replaces the existing
     * registration with the new identity. This is the natural shape for
     * a "the receiver's name changed" event.
     *
     * @param endpointInfo the identity descriptor to advertise. Same
     *   bytes that `Discovery.advertise` writes under the mDNS TXT key
     *   `n` (after URL-safe-base64 encoding).
     * @param endpointId the 4-byte ASCII slug — the same value that
     *   appears in the protocol-level `ConnectionRequestFrame.endpoint_id`
     *   and in the mDNS instance name's endpoint_id slice. Stock peers
     *   correlate BLE-pulse identities to mDNS records by this slug, so
     *   matching them across both channels keeps our presence
     *   self-consistent.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    public fun start(
        endpointInfo: EndpointInfo,
        endpointId: ByteArray,
    ): Boolean =
        synchronized(lifecycleLock) {
            require(endpointId.size == BleServiceData.ENDPOINT_ID_LEN) {
                "endpointId must be ${BleServiceData.ENDPOINT_ID_LEN} bytes, got ${endpointId.size}"
            }
            // Idempotent on identity-unchanged calls: the gate may invoke
            // start() repeatedly during steady-state publishing (every
            // BLE-scan re-arm passes through the same MdnsAdvertisementGate
            // decision loop). Tearing the platform registration down and
            // rebuilding it on every flap leaves a brief gap in the BLE
            // pulse and churns the host BT stack. Short-circuit when the
            // already-active registration carries the same identity bytes.
            if (active.get() != null &&
                currentEndpointInfo == endpointInfo &&
                currentEndpointId?.contentEquals(endpointId) == true
            ) {
                return@synchronized true
            }
            // Identity changed (or we're starting fresh) — replace any
            // previous registration so the platform never sees stale
            // identity bytes alongside the fresh ones.
            active.getAndSet(null)?.runCatchingClose()

            if (!permissionChecker.hasAdvertisePermission()) {
                Log.w(TAG, "start: BLUETOOTH_ADVERTISE not granted; skipping BLE pulse advertise")
                return@synchronized false
            }

            val payload =
                try {
                    payloadFactory.build(endpointId, endpointInfo)
                } catch (
                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                ) {
                    Log.w(TAG, "start: payload build failed; skipping BLE pulse advertise", t)
                    return@synchronized false
                }

            val registration =
                try {
                    gate.startAdvertising(payload, currentMode)
                } catch (
                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                ) {
                    // Platform throws SecurityException if the runtime
                    // permission was revoked between the check above
                    // and the call, IllegalStateException if Bluetooth
                    // is off, and IllegalArgumentException if the
                    // payload exceeds the 31-byte budget. None is fatal;
                    // log and let the receiver continue in mDNS-only.
                    Log.w(TAG, "start: BLE advertise startAdvertising threw; pulse skipped", t)
                    null
                }
            if (registration == null) {
                Log.w(TAG, "start: BLE advertise unavailable (no advertiser / failed start)")
                return@synchronized false
            }
            active.set(registration)
            currentEndpointInfo = endpointInfo
            currentEndpointId = endpointId
            Log.w(
                TAG,
                "start: BLE pulse advertise started bytes=${payload.size} " +
                    "uuid=${BleServiceData.SERVICE_UUID_128_STRING} " +
                    "mode=${describeAdvertiseMode(currentMode)}",
            )
            true
        }

    /**
     * Stop the platform advertisement. Idempotent.
     */
    public fun stop() {
        synchronized(lifecycleLock) {
            val registration = active.getAndSet(null) ?: return@synchronized
            registration.runCatchingClose()
            currentEndpointInfo = null
            currentEndpointId = null
            Log.w(TAG, "stop: BLE pulse advertise stopped")
        }
    }

    /**
     * Switches the platform advertisement to the given
     * [AdvertiseSettings] mode constant. Idempotent — calling with the
     * current mode is a no-op.
     *
     * Acceptable values are
     * [AdvertiseSettings.ADVERTISE_MODE_LOW_POWER],
     * [AdvertiseSettings.ADVERTISE_MODE_BALANCED], and
     * [AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY].
     *
     * If an advertisement is currently in flight, the platform
     * registration is torn down and re-registered with the new
     * settings using the most recent identity — symmetric to
     * [BleQuickShareScanner.setScanMode]. If no advertisement is
     * active, the new mode is simply remembered for the next [start].
     *
     * @return `true` if the advertiser ended up running on the
     *   requested mode (either already running on it, or successfully
     *   transitioned, or idle and the mode is now pinned for the next
     *   start). `false` if a re-registration was attempted but the
     *   platform refused.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    public fun setAdvertiseMode(mode: Int): Boolean =
        synchronized(lifecycleLock) {
            if (mode == currentMode) return@synchronized true
            val previous = currentMode
            currentMode = mode

            val info = currentEndpointInfo
            val id = currentEndpointId
            if (active.get() == null || info == null || id == null) {
                Log.w(
                    TAG,
                    "setAdvertiseMode: idle, pinned mode for next start: " +
                        "${describeAdvertiseMode(previous)} -> ${describeAdvertiseMode(mode)}",
                )
                return@synchronized true
            }

            // Active path: tear down the existing registration and bring a
            // new one up under the new settings using the current identity.
            // We deliberately route through gate.startAdvertising rather
            // than a hypothetical "update settings in place" call — the
            // platform API does not expose one and re-registration is the
            // documented pattern (matches BleQuickShareScanner.setScanMode).
            active.getAndSet(null)?.runCatchingClose()

            val payload =
                try {
                    payloadFactory.build(id, info)
                } catch (
                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                ) {
                    Log.w(TAG, "setAdvertiseMode: payload rebuild threw", t)
                    return@synchronized false
                }
            val restarted =
                try {
                    gate.startAdvertising(payload, mode)
                } catch (
                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                ) {
                    Log.w(TAG, "setAdvertiseMode: re-register at new mode failed", t)
                    null
                }
            if (restarted == null) {
                Log.w(
                    TAG,
                    "setAdvertiseMode: ${describeAdvertiseMode(previous)} -> " +
                        "${describeAdvertiseMode(mode)} FAILED, advertiser is now stopped",
                )
                return@synchronized false
            }
            active.set(restarted)
            Log.w(
                TAG,
                "setAdvertiseMode: ${describeAdvertiseMode(previous)} -> ${describeAdvertiseMode(mode)}",
            )
            true
        }

    /**
     * True iff a platform advertisement is currently in flight.
     */
    public val isAdvertising: Boolean
        get() = active.get() != null

    /** Currently-active platform advertise mode. Useful for diagnostics. */
    public val activeAdvertiseMode: Int
        get() = currentMode

    /**
     * Run [BleAdvertiserGate.Registration.close] without letting an
     * exception escape. Uses a consistent log tag so the failure is
     * attributable in logcat.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun BleAdvertiserGate.Registration.runCatchingClose() {
        try {
            close()
        } catch (t: Throwable) {
            Log.w(TAG, "close threw on previous BLE advertise registration", t)
        }
    }

    public companion object {
        /** logcat tag for receiver-side BLE-advertise diagnostics. */
        internal const val TAG: String = "WvmgBleAdv"

        /**
         * Build the platform [AdvertiseSettings] for the given mode.
         *
         * Pinned configuration:
         *  * `setConnectable(false)` — Quick Share fast advertisements
         *    are non-connectable. We never accept GATT connections; the
         *    advertisement only signals presence. Setting this `false`
         *    also keeps the advertising-PDU budget free for the
         *    service-data payload.
         *  * `setTxPowerLevel(ADVERTISE_TX_POWER_HIGH)` — best chance
         *    of a wake-up across a typical room. Symmetric to the
         *    sender-side `BleAdvertiser` and to the BALANCED scan-mode
         *    range we already tune for on the scanner side.
         *  * `setAdvertiseMode(<mode>)` — variable; defaults to
         *    BALANCED and switches to LOW_LATENCY while the app is
         *    foregrounded.
         */
        internal fun buildSettings(mode: Int): AdvertiseSettings =
            AdvertiseSettings
                .Builder()
                .setAdvertiseMode(mode)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build()

        /**
         * Build the platform [AdvertiseData] containing only the
         * 16-bit service-data AD for `0xFEF3`.
         *
         * The service-UUID is embedded inside the service-data
         * structure (AD type `0x16`), so we deliberately do not also
         * advertise it through the "Complete List of 16-bit Service
         * UUIDs" AD — including both pushes us over the 31-byte
         * legacy budget. This mirrors the comment in [BleAdvertiser].
         */
        internal fun buildAdvertiseData(payload: ByteArray): AdvertiseData {
            val parcelUuid = ParcelUuid(UUID.fromString(BleServiceData.SERVICE_UUID_128_STRING))
            return AdvertiseData
                .Builder()
                .addServiceData(parcelUuid, payload)
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build()
        }

        /**
         * Human-readable label for an [AdvertiseSettings] mode constant.
         * Used in logcat so a power-tuning triage can grep
         * `WvmgBleAdv setAdvertiseMode` and read the transition without
         * translating integers.
         */
        internal fun describeAdvertiseMode(mode: Int): String =
            when (mode) {
                AdvertiseSettings.ADVERTISE_MODE_LOW_POWER -> "LOW_POWER"
                AdvertiseSettings.ADVERTISE_MODE_BALANCED -> "BALANCED"
                AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY -> "LOW_LATENCY"
                else -> "UNKNOWN($mode)"
            }

        /**
         * Test-only factory matching the existing module convention.
         * The supplied [gate] / [permissionChecker] / [payloadFactory]
         * let JVM unit tests exercise the lifecycle without
         * instantiating the production Android wiring.
         */
        @JvmStatic
        internal fun forTesting(
            gate: BleAdvertiserGate,
            permissionChecker: AdvertisePermissionChecker = AdvertisePermissionChecker { true },
            payloadFactory: PayloadFactory = DefaultPayloadFactory,
        ): BleQuickShareAdvertiser =
            BleQuickShareAdvertiser(
                gate = gate,
                permissionChecker = permissionChecker,
                payloadFactory = payloadFactory,
            )
    }
}

/**
 * Permission gate consulted by [BleQuickShareAdvertiser.start] before
 * attempting a platform advertisement. Lifted to its own interface so
 * JVM tests can stub it without depending on [ContextCompat].
 */
public fun interface AdvertisePermissionChecker {
    public fun hasAdvertisePermission(): Boolean
}

internal class AndroidAdvertisePermissionChecker(
    private val context: Context,
) : AdvertisePermissionChecker {
    override fun hasAdvertisePermission(): Boolean {
        // On API ≤ 30 the install-time BLUETOOTH / BLUETOOTH_ADMIN
        // permissions are declared in the umbrella manifest and the
        // runtime check below trivially passes. From API 31+ we need
        // the runtime BLUETOOTH_ADVERTISE grant.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return checkSPermission()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun checkSPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        ) == PackageManager.PERMISSION_GRANTED
}

/**
 * Builder for the 23-byte service-data payload. The default
 * implementation delegates to [BleServiceData.encode] in `:core-protocol`;
 * tests can substitute a fake to drive specific failure paths.
 */
public fun interface PayloadFactory {
    public fun build(
        endpointId: ByteArray,
        endpointInfo: EndpointInfo,
    ): ByteArray
}

/**
 * Production payload factory — calls into the pure-JVM encoder so the
 * Android-side advertiser never has to know the byte layout itself.
 */
public object DefaultPayloadFactory : PayloadFactory {
    override fun build(
        endpointId: ByteArray,
        endpointInfo: EndpointInfo,
    ): ByteArray = BleServiceData.encode(endpointId, endpointInfo)
}

/**
 * Abstract handle over the platform [BluetoothLeAdvertiser]. The real
 * Android type is final so we wrap it instead of mocking — same shape
 * as [BleScannerGate].
 *
 * The gate owns the platform [AdvertiseCallback] subclass (which JVM
 * unit tests cannot instantiate because the AGP stub jar lacks bytecode
 * for its non-abstract methods) and the platform settings/data builders.
 * Consumers receive a [Registration] whose [Registration.close] tears
 * the advertisement down — no AGP-stubbed type ever leaks across this
 * boundary.
 */
public interface BleAdvertiserGate {
    /**
     * Begins a Quick Share fast-advertisement under
     * [BleServiceData.SERVICE_UUID_128_STRING] with the given
     * 23-ish-byte service-data payload and platform mode.
     *
     * Returns a [Registration] handle whose [Registration.close] stops
     * the platform advertisement, or `null` if it could not be started
     * (adapter off, no LE peripheral mode, etc.).
     *
     * @param serviceData the encoded service-data payload from
     *   [BleServiceData.encode]. Must be ≤ 23 bytes for the
     *   default hidden EndpointInfo shape; longer payloads (e.g. with
     *   inline UTF-8 device name) may exceed the 31-byte advertising-PDU
     *   budget and trigger `ADVERTISE_FAILED_DATA_TOO_LARGE`.
     * @param mode one of [AdvertiseSettings.ADVERTISE_MODE_LOW_POWER],
     *   [AdvertiseSettings.ADVERTISE_MODE_BALANCED], or
     *   [AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY].
     */
    public fun startAdvertising(
        serviceData: ByteArray,
        mode: Int,
    ): Registration?

    /** Token returned from [startAdvertising]; close to stop the underlying advertisement. */
    public interface Registration : AutoCloseable {
        /** Stops the underlying platform advertisement. Idempotent. */
        public override fun close()
    }
}

/**
 * Production [BleAdvertiserGate] backed by [BluetoothLeAdvertiser].
 *
 * The platform [AdvertiseCallback] is constructed lazily — the gate's
 * lifetime is bound to a real Android device, so its instances never
 * have to survive a JVM unit-test class load.
 */
internal class AndroidBleAdvertiserGate(
    private val context: Context,
) : BleAdvertiserGate {
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    override fun startAdvertising(
        serviceData: ByteArray,
        mode: Int,
    ): BleAdvertiserGate.Registration? {
        val advertiser = resolveAdvertiser() ?: return null
        val settings = BleQuickShareAdvertiser.buildSettings(mode)
        val data = BleQuickShareAdvertiser.buildAdvertiseData(serviceData)
        val callback = AdvertiseCallbackImpl()
        advertiser.startAdvertising(settings, data, callback)
        return Registration(advertiser, callback)
    }

    @Suppress("ReturnCount")
    private fun resolveAdvertiser(): BluetoothLeAdvertiser? {
        // Each early-return covers a distinct failure mode that the
        // production logs already differentiate; collapsing them into a
        // single chained ?: hides the cause when one trips. Suppress
        // the detekt warning explicitly rather than restructuring.
        val manager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: return null
        val adapter: BluetoothAdapter = manager.adapter ?: return null
        if (!adapter.isEnabled) return null
        return adapter.bluetoothLeAdvertiser
    }

    private class AdvertiseCallbackImpl : AdvertiseCallback() {
        @Volatile
        var lastError: Int? = null
            private set

        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.w(BleQuickShareAdvertiser.TAG, "advertise: onStartSuccess settings=$settingsInEffect")
        }

        @Suppress("MagicNumber")
        override fun onStartFailure(errorCode: Int) {
            lastError = errorCode
            val label =
                when (errorCode) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
                    ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                    ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                    else -> "UNKNOWN"
                }
            Log.w(BleQuickShareAdvertiser.TAG, "advertise: onStartFailure code=$errorCode ($label)")
        }
    }

    private inner class Registration(
        private val advertiser: BluetoothLeAdvertiser,
        private val callback: AdvertiseCallbackImpl,
    ) : BleAdvertiserGate.Registration {
        @Volatile
        private var closed: Boolean = false

        @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
        override fun close() {
            if (closed) return
            closed = true
            try {
                advertiser.stopAdvertising(callback)
            } catch (
                @Suppress("TooGenericExceptionCaught") t: Throwable,
            ) {
                // Adapter may have been turned off after startAdvertising
                // succeeded; we still want the registration discarded.
                Log.w(BleQuickShareAdvertiser.TAG, "stopAdvertising threw", t)
            }
        }
    }
}
