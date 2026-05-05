/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.discovery.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import dev.bluehouse.libredrop.protocol.endpoint.BleAdvertisementHeader
import dev.bluehouse.libredrop.protocol.endpoint.BleServiceData
import dev.bluehouse.libredrop.protocol.endpoint.DctAdvertisement
import dev.bluehouse.libredrop.protocol.endpoint.EndpointInfo
import dev.bluehouse.libredrop.protocol.endpoint.NearbyServiceId
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
 * UUID, LibreDrop is invisible to Galaxy / One UI's send sheet — that was
 * exactly the symptom commit c0150be diagnosed.
 *
 * ### Wire format
 *
 * The primary service-data payload is built as a compact BLE v2 GATT
 * advertisement header in `:core-protocol` (pure-JVM, KAT-tested). Stock
 * Samsung senders read the visible receiver identity from GATT slot zero,
 * where LibreDrop publishes a second-profile fast advertisement:
 *
 * ```text
 * [ header | bloom_filter | advertisement_hash | psm=0 ]
 * ```
 *
 * Vivo/OriginOS devices can run Google Play services' own Nearby GATT
 * provider beside LibreDrop. The regular `0xFEF3` slot service therefore
 * must stay published, and slot zero must advertise the second-profile socket
 * so Samsung resolves the selected peer into LibreDrop instead of Google's
 * coresident regular profile. The connectable extended payload carries the
 * visible receiver name only. Do not append the RX instant-connection extra
 * field here: on One UI 8.0.5 it promotes the tap into a separate
 * Mosey/link-local path on port 8770, bypassing LibreDrop's BLE GATT
 * bootstrap server.
 *
 * LibreDrop does not submit the optional DCT (`0xFC73`) advertisement in
 * receiver mode. Samsung ShareLive was observed to probe the DCT advertiser
 * address as if it were a GATT advertisement header after a successful first
 * transfer; that failed probe poisoned the next tap and routed it to GMS's
 * regular socket. The visible extended `0xFEF3` advertisement is the stable
 * identity surface for this path.
 *
 * ### Lifecycle
 *
 * Owned by [dev.bluehouse.libredrop.service.receiver.ReceiverForegroundService],
 * driven through the existing [dev.bluehouse.libredrop.service.receiver.MdnsAdvertisementGate].
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
 * @param payloadFactory builds the compact advertisement header from the
 *   supplied [EndpointInfo] and the latest endpoint_id. Visible identity rides
 *   in the optional extended/DCT advertisements.
 * @param visiblePayloadFactory optionally builds an Android O+ extended
 *   `0xFEF3` payload carrying the visible [EndpointInfo]. This is the BLE-only
 *   path Samsung ShareLive was observed to promote into the share sheet when
 *   DCT was ignored.
 * @param dctPayloadFactory optionally builds the `0xFC73` DCT payload for
 *   Android O+ secondary advertising. DCT is the stock Nearby path that
 *   carries a short visible receiver name without overfilling the 27-byte
 *   `0xFEF3` fast-advertisement value.
 */
public class BleQuickShareAdvertiser internal constructor(
    private val gate: BleAdvertiserGate,
    private val permissionChecker: AdvertisePermissionChecker,
    private val payloadFactory: PayloadFactory = DefaultPayloadFactory,
    private val visiblePayloadFactory: OptionalPayloadFactory = DefaultVisiblePayloadFactory,
    private val dctPayloadFactory: DctPayloadFactory = DefaultDctPayloadFactory,
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
            val dctPayload =
                try {
                    dctPayloadFactory.build(endpointInfo)
                } catch (
                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                ) {
                    Log.w(TAG, "start: DCT payload build failed; compact BLE pulse only", t)
                    null
                }
            val visiblePayload =
                try {
                    visiblePayloadFactory.build(endpointId, endpointInfo)
                } catch (
                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                ) {
                    Log.w(TAG, "start: visible extended payload build failed; compact BLE pulse only", t)
                    null
                }

            val registration =
                try {
                    gate.startAdvertising(payload, currentMode, dctPayload, visiblePayload)
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
            val dctEndpointLabel = dctPayload?.let { endpointInfo.dctEndpointId() } ?: "-"
            Log.w(
                TAG,
                "start: BLE pulse advertise started bytes=${payload.size} " +
                    "endpointId=${endpointId.toAsciiLabel()} payload=${payload.toHex()} " +
                    "visibleBytes=${visiblePayload?.size ?: 0} " +
                    "dctBytes=${dctPayload?.size ?: 0} dctEndpointId=$dctEndpointLabel " +
                    "uuid=${BleServiceData.SERVICE_UUID_128_STRING} " +
                    "dctUuid=${DctAdvertisement.SERVICE_UUID_128_STRING} " +
                    "mode=${describeAdvertiseMode(currentMode)} placement=direct-fast+visible-extended+dct-legacy",
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
            val dctPayload =
                try {
                    dctPayloadFactory.build(info)
                } catch (
                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                ) {
                    Log.w(TAG, "setAdvertiseMode: DCT payload rebuild threw; compact BLE pulse only", t)
                    null
                }
            val visiblePayload =
                try {
                    visiblePayloadFactory.build(id, info)
                } catch (
                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                ) {
                    Log.w(TAG, "setAdvertiseMode: visible extended payload rebuild threw; compact BLE pulse only", t)
                    null
                }
            val restarted =
                try {
                    gate.startAdvertising(payload, mode, dctPayload, visiblePayload)
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
            val dctEndpointLabel = dctPayload?.let { info.dctEndpointId() } ?: "-"
            Log.w(
                TAG,
                "setAdvertiseMode: ${describeAdvertiseMode(previous)} -> ${describeAdvertiseMode(mode)} " +
                    "endpointId=${id.toAsciiLabel()} payload=${payload.toHex()} " +
                    "visibleBytes=${visiblePayload?.size ?: 0} " +
                    "dctBytes=${dctPayload?.size ?: 0} dctEndpointId=$dctEndpointLabel " +
                    "placement=direct-fast+visible-extended+dct-legacy",
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
        internal const val TAG: String = "LibreDropBleAdv"

        /**
         * Build the platform [AdvertiseSettings] for the given mode.
         *
         * Pinned configuration:
         *  * `setConnectable(true)` — stock Quick Share taps a visible
         *    off-LAN peer by opening a BLE GATT socket against the same
         *    advertiser address. The service data still lives in the scan
         *    response so the legacy advertising-PDU budget remains small.
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
                .setConnectable(true)
                .build()

        /**
         * Build the secondary legacy DCT advertisement settings. DCT is an
         * identity hint only in LibreDrop's current off-LAN path; the connectable
         * GATT socket remains on the primary `0xFEF3` advertisement.
         */
        internal fun buildDctSettings(mode: Int): AdvertiseSettings =
            AdvertiseSettings
                .Builder()
                .setAdvertiseMode(mode)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build()

        /**
         * Build the primary legacy advertisement. The Quick Share fast
         * payload is carried in the scan response so the platform emits
         * a legacy, connectable, scannable advertisement shape like
         * stock Nearby's high-power receiver advertising path.
         */
        internal fun buildAdvertiseData(): AdvertiseData =
            AdvertiseData
                .Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build()

        /**
         * Build the scan-response [AdvertiseData] containing only the
         * 16-bit service-data AD for `0xFEF3`.
         *
         * The service-UUID is embedded inside the service-data
         * structure (AD type `0x16`), so we deliberately do not also
         * advertise it through the "Complete List of 16-bit Service
         * UUIDs" AD — including both pushes us over the 31-byte
         * legacy budget. This mirrors the comment in [BleAdvertiser].
         */
        internal fun buildScanResponseData(payload: ByteArray): AdvertiseData =
            buildServiceData(BleServiceData.SERVICE_UUID_128_STRING, payload)

        /** Build an [AdvertiseData] carrying DCT service data under `0xFC73`. */
        internal fun buildDctAdvertiseData(payload: ByteArray): AdvertiseData =
            buildServiceData(DctAdvertisement.SERVICE_UUID_128_STRING, payload)

        private fun buildServiceData(
            uuid: String,
            payload: ByteArray,
        ): AdvertiseData {
            val parcelUuid = ParcelUuid(UUID.fromString(uuid))
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
         * `LibreDropBleAdv setAdvertiseMode` and read the transition without
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
            visiblePayloadFactory: OptionalPayloadFactory = DefaultVisiblePayloadFactory,
            dctPayloadFactory: DctPayloadFactory = DefaultDctPayloadFactory,
        ): BleQuickShareAdvertiser =
            BleQuickShareAdvertiser(
                gate = gate,
                permissionChecker = permissionChecker,
                payloadFactory = payloadFactory,
                visiblePayloadFactory = visiblePayloadFactory,
                dctPayloadFactory = dctPayloadFactory,
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
 * Builder for the compact primary service-data payload. The default implementation
 * emits a BLE v2 GATT advertisement header in `:core-protocol`; tests can
 * substitute a fake to drive specific failure paths.
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
    ): ByteArray {
        val gattAdvertisement =
            BleServiceData.encodeFramed(
                endpointId = endpointId,
                endpointInfo = endpointInfo,
                secondProfile = true,
            )
        return BleAdvertisementHeader.encodeSingleSlot(
            serviceId = NearbyServiceId.VALUE,
            gattAdvertisement = gattAdvertisement,
            psm = 0,
            supportsExtendedAdvertisement = endpointInfo.supportsVisibleExtendedAdvertisement(),
        )
    }
}

private fun EndpointInfo.supportsVisibleExtendedAdvertisement(): Boolean = !hidden && !deviceName.isNullOrBlank()

/** Optional payload builder for secondary extended advertisements. */
public fun interface OptionalPayloadFactory {
    public fun build(
        endpointId: ByteArray,
        endpointInfo: EndpointInfo,
    ): ByteArray?
}

/**
 * Builds the visible-name extended `0xFEF3` payload. A visible EndpointInfo
 * cannot fit in the legacy 31-byte scan-response budget once the BLE v2 frame
 * wrapper and device token are included, but it fits in an extended advertising
 * set and is what Samsung ShareLive promoted during off-LAN receiver testing.
 */
public object DefaultVisiblePayloadFactory : OptionalPayloadFactory {
    override fun build(
        endpointId: ByteArray,
        endpointInfo: EndpointInfo,
    ): ByteArray? {
        if (endpointInfo.hidden || endpointInfo.deviceName == null) return null
        val activePsm = BleDctPsmHolder.currentPsm?.takeIf { it != DctAdvertisement.DEFAULT_PSM }
        return BleServiceData.encodeFramedWithExtraFields(
            endpointId = endpointId,
            endpointInfo = endpointInfo,
            psm = activePsm,
            rxInstantConnectionAdvertisement = null,
            secondProfile = true,
        )
    }
}

/**
 * Optional builder for the DCT (`0xFC73`) advertisement payload. DCT carries a
 * short visible name for Nearby's off-LAN discovery path while the primary
 * `0xFEF3` fast advertisement remains the legal compact hidden shape.
 */
public fun interface DctPayloadFactory {
    public fun build(endpointInfo: EndpointInfo): ByteArray?
}

public object DefaultDctPayloadFactory : DctPayloadFactory {
    override fun build(endpointInfo: EndpointInfo): ByteArray? = null
}

private fun ByteArray.toAsciiLabel(): String = String(this, Charsets.US_ASCII)

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun EndpointInfo.dctEndpointId(): String? =
    deviceName
        ?.takeIf { it.isNotBlank() }
        ?.let { DctAdvertisement.generateEndpointId(DctAdvertisement.DEFAULT_DEDUP, it) }

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
     * Begins a Quick Share advertisement under
     * [BleServiceData.SERVICE_UUID_128_STRING] with the given
     * compact framed service-data payload and platform mode.
     *
     * Returns a [Registration] handle whose [Registration.close] stops
     * the platform advertisement, or `null` if it could not be started
     * (adapter off, no LE peripheral mode, etc.).
     *
     * @param serviceData the encoded `0xFEF3` service-data payload. The
     *   production receiver uses [BleServiceData.encodeFramed] while tests
     *   may inject custom bytes for failure-path coverage.
     * @param dctServiceData optional `0xFC73` DCT service-data payload used
     *   as the stock off-LAN visible-name hint.
     * @param mode one of [AdvertiseSettings.ADVERTISE_MODE_LOW_POWER],
     *   [AdvertiseSettings.ADVERTISE_MODE_BALANCED], or
     *   [AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY].
     */
    public fun startAdvertising(
        serviceData: ByteArray,
        mode: Int,
        dctServiceData: ByteArray? = null,
        visibleServiceData: ByteArray? = null,
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
        dctServiceData: ByteArray?,
        visibleServiceData: ByteArray?,
    ): BleAdvertiserGate.Registration? {
        val resolved = resolveAdvertiser() ?: return null
        val advertiser = resolved.advertiser
        val visibleRegistration =
            startVisibleExtendedAdvertisingIfAvailable(
                advertiser = advertiser,
                adapter = resolved.adapter,
                serviceData = visibleServiceData,
                mode = mode,
            )
        val callback =
            startHiddenLegacyAdvertising(
                advertiser = advertiser,
                serviceData = serviceData,
                mode = mode,
            )
        val dctRegistration =
            startDctAdvertisingIfAvailable(
                advertiser = advertiser,
                adapter = resolved.adapter,
                serviceData = dctServiceData,
                mode = mode,
            )
        return Registration(advertiser, callback, visibleRegistration, dctRegistration)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startHiddenLegacyAdvertising(
        advertiser: BluetoothLeAdvertiser,
        serviceData: ByteArray,
        mode: Int,
    ): AdvertiseCallbackImpl? {
        val settings = BleQuickShareAdvertiser.buildSettings(mode)
        val (data, scanResponse, placement) =
            if (serviceData.fitsPrimaryLegacyAdvertisement()) {
                Triple(BleQuickShareAdvertiser.buildScanResponseData(serviceData), null, "primary")
            } else {
                Triple(
                    BleQuickShareAdvertiser.buildAdvertiseData(),
                    BleQuickShareAdvertiser.buildScanResponseData(serviceData),
                    "scan-response",
                )
            }
        return try {
            AdvertiseCallbackImpl(label = "advertise").also { primaryCallback ->
                advertiser.startAdvertising(settings, data, scanResponse, primaryCallback)
                Log.w(
                    BleQuickShareAdvertiser.TAG,
                    "advertise: submitted legacy $placement bytes=${serviceData.size} " +
                        "uuid=${BleServiceData.SERVICE_UUID_128_STRING}",
                )
            }
        } catch (e: SecurityException) {
            logHiddenLegacyStartFailure(e)
            null
        } catch (e: IllegalArgumentException) {
            logHiddenLegacyStartFailure(e)
            null
        } catch (e: IllegalStateException) {
            logHiddenLegacyStartFailure(e)
            null
        }
    }

    private fun ByteArray.fitsPrimaryLegacyAdvertisement(): Boolean =
        size + SERVICE_DATA_AD_OVERHEAD_BYTES + FLAGS_AD_OVERHEAD_BYTES <= LEGACY_ADVERTISING_DATA_BYTES

    private fun logHiddenLegacyStartFailure(e: RuntimeException) {
        Log.w(
            BleQuickShareAdvertiser.TAG,
            "advertise: hidden legacy startAdvertising failed; continuing with secondary advertisements",
            e,
        )
    }

    @Suppress("ReturnCount")
    private fun resolveAdvertiser(): ResolvedAdvertiser? {
        // Each early-return covers a distinct failure mode that the
        // production logs already differentiate; collapsing them into a
        // single chained ?: hides the cause when one trips. Suppress
        // the detekt warning explicitly rather than restructuring.
        val manager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: return null
        val adapter: BluetoothAdapter = manager.adapter ?: return null
        if (!adapter.isEnabled) return null
        val advertiser = adapter.bluetoothLeAdvertiser ?: return null
        return ResolvedAdvertiser(adapter, advertiser)
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startDctAdvertisingIfAvailable(
        advertiser: BluetoothLeAdvertiser,
        adapter: BluetoothAdapter,
        serviceData: ByteArray?,
        mode: Int,
    ): DctRegistration? {
        if (serviceData == null) return null
        val legacyAdvertiseDataSize = SERVICE_DATA_AD_OVERHEAD_BYTES + serviceData.size
        if (legacyAdvertiseDataSize <= LEGACY_ADVERTISING_DATA_BYTES) {
            return try {
                val callback = AdvertiseCallbackImpl(label = "DCT advertise")
                advertiser.startAdvertising(
                    BleQuickShareAdvertiser.buildDctSettings(mode),
                    BleQuickShareAdvertiser.buildDctAdvertiseData(serviceData),
                    callback,
                )
                Log.w(
                    BleQuickShareAdvertiser.TAG,
                    "DCT advertise: submitted legacy bytes=${serviceData.size} " +
                        "uuid=${DctAdvertisement.SERVICE_UUID_128_STRING}",
                )
                DctRegistration.Legacy(callback)
            } catch (t: Throwable) {
                Log.w(
                    BleQuickShareAdvertiser.TAG,
                    "DCT advertise: legacy startAdvertising threw; trying extended set",
                    t,
                )
                startExtendedDctAdvertisingIfAvailable(
                    advertiser = advertiser,
                    adapter = adapter,
                    serviceData = serviceData,
                    mode = mode,
                )
            }
        }

        Log.w(
            BleQuickShareAdvertiser.TAG,
            "DCT advertise: legacy payload too large bytes=$legacyAdvertiseDataSize; trying extended set",
        )
        return startExtendedDctAdvertisingIfAvailable(
            advertiser = advertiser,
            adapter = adapter,
            serviceData = serviceData,
            mode = mode,
        )
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startExtendedDctAdvertisingIfAvailable(
        advertiser: BluetoothLeAdvertiser,
        adapter: BluetoothAdapter,
        serviceData: ByteArray,
        mode: Int,
    ): DctRegistration? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        if (!adapter.isLeExtendedAdvertisingSupported) {
            Log.w(
                BleQuickShareAdvertiser.TAG,
                "DCT advertise: controller does not support LE extended advertising",
            )
            return null
        }
        val advertiseDataSize = SERVICE_DATA_AD_OVERHEAD_BYTES + serviceData.size
        if (advertiseDataSize > adapter.leMaximumAdvertisingDataLength) {
            Log.w(
                BleQuickShareAdvertiser.TAG,
                "DCT advertise: payload too large bytes=$advertiseDataSize " +
                    "max=${adapter.leMaximumAdvertisingDataLength}",
            )
            return null
        }
        return try {
            val callback = AdvertisingSetCallbackImpl(label = "DCT advertise")
            advertiser.startAdvertisingSet(
                buildExtendedParameters(mode, connectable = false),
                BleQuickShareAdvertiser.buildDctAdvertiseData(serviceData),
                null,
                null,
                null,
                0,
                0,
                callback,
            )
            Log.w(
                BleQuickShareAdvertiser.TAG,
                "DCT advertise: submitted extended bytes=${serviceData.size} " +
                    "uuid=${DctAdvertisement.SERVICE_UUID_128_STRING}",
            )
            DctRegistration.Extended(callback)
        } catch (t: Throwable) {
            Log.w(
                BleQuickShareAdvertiser.TAG,
                "DCT advertise: startAdvertisingSet threw; compact BLE pulse only",
                t,
            )
            null
        }
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startVisibleExtendedAdvertisingIfAvailable(
        advertiser: BluetoothLeAdvertiser,
        adapter: BluetoothAdapter,
        serviceData: ByteArray?,
        mode: Int,
    ): ExtendedRegistration? {
        if (serviceData == null) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        if (!adapter.isLeExtendedAdvertisingSupported) {
            Log.w(
                BleQuickShareAdvertiser.TAG,
                "visible advertise: controller does not support LE extended advertising",
            )
            return null
        }
        val advertiseDataSize = SERVICE_DATA_AD_OVERHEAD_BYTES + serviceData.size
        if (advertiseDataSize > adapter.leMaximumAdvertisingDataLength) {
            Log.w(
                BleQuickShareAdvertiser.TAG,
                "visible advertise: payload too large bytes=$advertiseDataSize " +
                    "max=${adapter.leMaximumAdvertisingDataLength}",
            )
            return null
        }
        return try {
            val callback = AdvertisingSetCallbackImpl(label = "visible advertise")
            advertiser.startAdvertisingSet(
                buildExtendedParameters(mode, connectable = true),
                BleQuickShareAdvertiser.buildScanResponseData(serviceData),
                null,
                null,
                null,
                0,
                0,
                callback,
            )
            Log.w(
                BleQuickShareAdvertiser.TAG,
                "visible advertise: submitted extended bytes=${serviceData.size} " +
                    "uuid=${BleServiceData.SERVICE_UUID_128_STRING}",
            )
            ExtendedRegistration(callback)
        } catch (t: Throwable) {
            Log.w(
                BleQuickShareAdvertiser.TAG,
                "visible advertise: startAdvertisingSet threw; compact BLE pulse only",
                t,
            )
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildExtendedParameters(
        mode: Int,
        connectable: Boolean,
    ): AdvertisingSetParameters =
        AdvertisingSetParameters
            .Builder()
            .setLegacyMode(false)
            .setConnectable(connectable)
            .setScannable(false)
            .setAnonymous(false)
            .setIncludeTxPower(false)
            .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
            .setSecondaryPhy(BluetoothDevice.PHY_LE_1M)
            .setInterval(mode.toExtendedInterval())
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()

    @RequiresApi(Build.VERSION_CODES.O)
    private fun Int.toExtendedInterval(): Int =
        when (this) {
            AdvertiseSettings.ADVERTISE_MODE_LOW_POWER -> AdvertisingSetParameters.INTERVAL_HIGH
            AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY -> AdvertisingSetParameters.INTERVAL_LOW
            else -> AdvertisingSetParameters.INTERVAL_MEDIUM
        }

    private data class ResolvedAdvertiser(
        val adapter: BluetoothAdapter,
        val advertiser: BluetoothLeAdvertiser,
    )

    @RequiresApi(Build.VERSION_CODES.O)
    private class AdvertisingSetCallbackImpl(
        private val label: String,
    ) : AdvertisingSetCallback() {
        @Volatile
        var lastStatus: Int? = null
            private set

        override fun onAdvertisingSetStarted(
            advertisingSet: android.bluetooth.le.AdvertisingSet?,
            txPower: Int,
            status: Int,
        ) {
            lastStatus = status
            Log.w(
                BleQuickShareAdvertiser.TAG,
                "$label: onAdvertisingSetStarted status=$status txPower=$txPower",
            )
        }
    }

    private class AdvertiseCallbackImpl(
        private val label: String,
    ) : AdvertiseCallback() {
        @Volatile
        var lastError: Int? = null
            private set

        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.w(BleQuickShareAdvertiser.TAG, "$label: onStartSuccess settings=$settingsInEffect")
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
            Log.w(BleQuickShareAdvertiser.TAG, "${this.label}: onStartFailure code=$errorCode ($label)")
        }
    }

    private sealed class DctRegistration {
        @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
        abstract fun close(advertiser: BluetoothLeAdvertiser)

        class Legacy(
            private val callback: AdvertiseCallbackImpl,
        ) : DctRegistration() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
            override fun close(advertiser: BluetoothLeAdvertiser) {
                advertiser.stopAdvertising(callback)
            }
        }

        @RequiresApi(Build.VERSION_CODES.O)
        class Extended(
            private val callback: AdvertisingSetCallbackImpl,
        ) : DctRegistration() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
            override fun close(advertiser: BluetoothLeAdvertiser) {
                advertiser.stopAdvertisingSet(callback)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private class ExtendedRegistration(
        private val callback: AdvertisingSetCallbackImpl,
    ) {
        @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
        fun close(advertiser: BluetoothLeAdvertiser) {
            advertiser.stopAdvertisingSet(callback)
        }
    }

    private inner class Registration(
        private val advertiser: BluetoothLeAdvertiser,
        private val callback: AdvertiseCallbackImpl?,
        private val visibleRegistration: ExtendedRegistration?,
        private val dctRegistration: DctRegistration?,
    ) : BleAdvertiserGate.Registration {
        @Volatile
        private var closed: Boolean = false

        @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
        override fun close() {
            if (closed) return
            closed = true
            if (dctRegistration != null) {
                try {
                    dctRegistration.close(advertiser)
                } catch (
                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                ) {
                    Log.w(BleQuickShareAdvertiser.TAG, "stop DCT advertise threw", t)
                }
            }
            if (visibleRegistration != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    visibleRegistration.close(advertiser)
                } catch (
                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                ) {
                    Log.w(BleQuickShareAdvertiser.TAG, "stop visible advertise threw", t)
                }
            }
            if (callback != null) {
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

    private companion object {
        private const val FLAGS_AD_OVERHEAD_BYTES: Int = 3
        private const val SERVICE_DATA_AD_OVERHEAD_BYTES: Int = 4
        private const val LEGACY_ADVERTISING_DATA_BYTES: Int = 31
    }
}
