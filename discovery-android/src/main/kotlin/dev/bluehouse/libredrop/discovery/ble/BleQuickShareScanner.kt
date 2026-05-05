/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.discovery.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import dev.bluehouse.libredrop.discovery.diagnostics.DiagnosticLog as Log

/**
 * Quick Share BLE pulse scanner.
 *
 * The receiver side of Phase 2's BLE auto-discovery (epic #2). Quick
 * Share senders advertise a 16-byte BLE service-data payload under
 * service UUID `0xFE2C` whenever they have something to send (#32).
 * The first 14 bytes of that payload are pinned by PROTOCOL.md:
 *
 * ```
 * fc 12 8e 01 42 00 00 00 00 00 00 00 00 00
 * ```
 *
 * The trailing two bytes are random and ignored. The scanner uses
 * `ScanFilter` with a prefix + mask to push the comparison into the
 * platform — only matching advertisements ever cross the kernel/user
 * boundary, which keeps power consumption acceptable.
 *
 * ### Lifecycle
 *
 * The class is designed to be owned by [dev.bluehouse.libredrop.service.receiver.ReceiverForegroundService].
 * The service starts the scanner when it comes up and stops it when it
 * tears down. Callers observe the scanner state through:
 *
 *  * [pulses] — a hot [Flow] of every matching [ScanResult]. Suitable
 *    for diagnostic logging or future "saw a pulse from X" UI.
 *  * [activity] — a [StateFlow] of [ScanActivity] indicating whether a
 *    pulse was seen recently. Issue #34 (mDNS gating) consumes this to
 *    decide whether to publish the receiver's mDNS service.
 *
 * The "active" / "idle" transition is driven by a configurable
 * [inactivityTimeoutMillis] — by default 30 s after the last seen pulse
 * the scanner reports [ScanActivity.Idle]. The platform scanner itself
 * keeps running while [start] is in effect; only the activity flag flips
 * back to idle. A future optimization may stop the platform scan during
 * idle stretches to save battery, but the issue's acceptance criterion
 * ("Battery consumption acceptable: in `SCAN_MODE_BALANCED`, BLE scan is
 * ~10–20 mAh/day") makes the always-on simpler approach acceptable for
 * Phase 2.
 *
 * ### Scan-mode tuning (#35)
 *
 * The scan registration runs in [ScanSettings.SCAN_MODE_BALANCED] by
 * default — the documented "low-power, foreground-service-friendly"
 * mode that Android allows to run continuously without throttling. The
 * scanner exposes [setScanMode] so the owning foreground service can
 * temporarily upgrade to [ScanSettings.SCAN_MODE_LOW_LATENCY] while the
 * user has the app foregrounded (responsiveness matters more than power
 * during active interaction) and revert back to BALANCED when the app
 * returns to the background. `setReportDelay(0)` is pinned for both
 * modes — non-zero delay batches advertisements which is unsuitable for
 * a near-real-time interactive trigger.
 *
 * Switching the scan mode while a scan is in flight tears down the
 * platform registration and re-registers with the new settings. This is
 * the platform-recommended pattern (`BluetoothLeScanner.startScan` is
 * idempotent on the callback identity but takes its `ScanSettings`
 * snapshot at registration time) and is fast in practice — the kernel
 * just updates the in-flight scan parameters.
 *
 * ### Threading
 *
 * `start` and `stop` are safe to call from any thread; they serialize
 * through a [Mutex] internally. The platform [ScanCallback] fires on
 * the system's binder thread; we hop straight back into the supplied
 * [coroutineScope] so consumer callbacks never block the binder pool.
 *
 * ### Permissions
 *
 * On API 31+ the runtime [Manifest.permission.BLUETOOTH_SCAN] is
 * required (already requested during onboarding by #31). On API ≤ 30
 * the install-time [Manifest.permission.BLUETOOTH] +
 * [Manifest.permission.BLUETOOTH_ADMIN] permissions are sufficient and
 * the umbrella manifest in `:app` declares them with `maxSdkVersion=30`.
 *
 * If the permission is missing, [start] logs a warning and becomes a
 * no-op — the scanner does not throw because the receiver service must
 * keep working in mDNS-only mode when BLE is unavailable.
 *
 * ### Testability
 *
 * [BleScannerGate] abstracts the platform `BluetoothLeScanner` so unit
 * tests on a plain JVM can drive the lifecycle through a fake. Tests
 * that need to observe the [activity] flow flip drive the inactivity
 * timer through `runTest`'s virtual scheduler — see
 * `BleQuickShareScannerTest`.
 *
 * @param coroutineScope scope that owns the inactivity-timeout
 *   coroutine. Cancelling the scope only stops the timer — call [stop]
 *   explicitly to release the platform scan registration.
 * @param gate platform-scanner abstraction; tests inject a fake.
 * @param permissionChecker checks `BLUETOOTH_SCAN` at start time;
 *   defaults to a [ContextCompat]-backed implementation.
 * @param inactivityTimeoutMillis time after the last seen pulse before
 *   [activity] flips back to [ScanActivity.Idle]. 30 s by default per
 *   the issue's acceptance criterion.
 */
public class BleQuickShareScanner internal constructor(
    private val coroutineScope: CoroutineScope,
    private val gate: BleScannerGate,
    private val permissionChecker: PermissionChecker,
    private val inactivityTimeoutMillis: Long = DEFAULT_INACTIVITY_TIMEOUT_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /**
     * Production constructor. Builds a real [BleScannerGate] over the
     * platform [BluetoothManager] and a [ContextCompat]-backed
     * permission checker. Only the application context is retained.
     */
    public constructor(
        context: Context,
        coroutineScope: CoroutineScope,
        inactivityTimeoutMillis: Long = DEFAULT_INACTIVITY_TIMEOUT_MILLIS,
    ) : this(
        coroutineScope = coroutineScope,
        gate = AndroidBleScannerGate(context.applicationContext),
        permissionChecker = AndroidPermissionChecker(context.applicationContext),
        inactivityTimeoutMillis = inactivityTimeoutMillis,
    )

    /**
     * Synchronization guard for [start] / [stop] state transitions.
     *
     * We use a plain `synchronized` block instead of a coroutine
     * `Mutex` so [stop] can run inline from `Service.onDestroy` without
     * needing a coroutine context — the parent scope is typically about
     * to be cancelled when stop runs, which would otherwise truncate
     * any suspend lock. The lock is held only over fast in-memory
     * state transitions plus a single platform call (`startScan` /
     * `stopScan`), so contention is negligible in practice.
     */
    private val lifecycleLock = Any()

    @Volatile
    private var registration: BleScannerGate.Registration? = null

    /**
     * Currently-active platform scan mode. Defaults to
     * [ScanSettings.SCAN_MODE_BALANCED] per the issue #35 acceptance
     * criterion — the mode that Android allows a foreground service to
     * run indefinitely without throttling.
     *
     * Mutated through [setScanMode] under [lifecycleLock]. Reads outside
     * the lock are racy in the strict sense but the field is `@Volatile`
     * and only used as a debug accessor / for the "do nothing if mode
     * unchanged" early-exit in [setScanMode] itself.
     */
    @Volatile
    private var currentScanMode: Int = ScanSettings.SCAN_MODE_BALANCED

    private val activityState: MutableStateFlow<ScanActivity> = MutableStateFlow(ScanActivity.Idle)

    @Volatile
    private var inactivityJob: Job? = null

    /**
     * Hot [StateFlow] reporting whether a Quick Share BLE pulse was seen
     * within [inactivityTimeoutMillis]. Issue #34's mDNS gate subscribes
     * to this to decide when to publish the receiver advertisement.
     *
     * The flow is a cold-to-hot wrapper around an internal mutable
     * state; subscribers always immediately see the current value and
     * subsequent transitions until they cancel.
     */
    public val activity: StateFlow<ScanActivity> = activityState.asStateFlow()

    private val pulsesSink: MutableSharedFlow<ScanResult> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = PULSES_BUFFER)

    /**
     * Hot [Flow] of every matching [ScanResult] received while a scan is
     * in flight. Subscribers see only pulses that arrive after they
     * start collecting (no replay) — the scanner is stateless beyond
     * the inactivity window itself.
     *
     * Production builds attach a single [ScanCallback] to the platform
     * scanner during [start]; that callback both calls [recordPulse]
     * (to drive the [activity] state machine) and forwards the result
     * to this shared flow. JVM unit tests use [forTestEmitPulse] to
     * drive [activity] without instantiating any platform types.
     */
    public val pulses: Flow<ScanResult> = pulsesSink.asSharedFlow()

    @Volatile
    private var primarySink: BleScannerGate.PulseSink? = null

    /**
     * Begin scanning for Quick Share BLE pulses. Idempotent: if a scan
     * is already in flight this call is a no-op.
     *
     * If `BLUETOOTH_SCAN` is not granted, or if the device has no
     * Bluetooth LE support / the adapter is disabled, this method logs
     * the failure and returns without throwing — the receiver service
     * continues to run in mDNS-only mode.
     *
     * @return `true` if the scan is now active (either newly started or
     *   already in flight), `false` if the scan could not be started.
     */
    public fun start(): Boolean =
        synchronized(lifecycleLock) {
            if (registration != null) return@synchronized true
            if (!permissionChecker.hasScanPermission()) {
                Log.w(TAG, "start: BLUETOOTH_SCAN not granted; skipping BLE pulse scan")
                return@synchronized false
            }
            val started =
                try {
                    gate.startScan(currentScanMode)
                } catch (
                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                ) {
                    // Platform throws SecurityException if the runtime permission
                    // was revoked between the check above and the call, and
                    // IllegalStateException if Bluetooth is off. Neither is fatal;
                    // log and let the receiver continue without BLE.
                    Log.w(TAG, "start: BLE scan failed to start", t)
                    null
                }
            if (started == null) return@synchronized false
            registration = started
            val sink =
                object : BleScannerGate.PulseSink {
                    override fun onPulse(result: ScanResult) {
                        recordPulse()
                        pulsesSink.tryEmit(result)
                    }

                    override fun onFailed(errorCode: Int) {
                        Log.w(TAG, "BLE scan failed errorCode=$errorCode")
                    }
                }
            gate.addSink(sink)
            primarySink = sink
            Log.w(TAG, "start: BLE pulse scan started mode=${describeScanMode(currentScanMode)}")
            true
        }

    /**
     * Switches the platform scan to the given [ScanSettings] scan-mode
     * constant. Idempotent — calling with the current mode is a no-op.
     *
     * Acceptable values are [ScanSettings.SCAN_MODE_LOW_POWER],
     * [ScanSettings.SCAN_MODE_BALANCED], and
     * [ScanSettings.SCAN_MODE_LOW_LATENCY]. Other constants
     * (`SCAN_MODE_OPPORTUNISTIC` in particular) are intentionally out of
     * scope: opportunistic mode means "only deliver results that some
     * other scan would have delivered anyway", and we are the only
     * BLE scanner in our process.
     *
     * If a scan is currently in flight, the platform registration is
     * torn down and re-registered with the new settings. If no scan is
     * active, the new mode is simply remembered for the next [start] —
     * this avoids spurious platform calls when the lifecycle observer
     * fires before the scanner has been started for the first time.
     *
     * Failures during the re-registration are logged but non-fatal: the
     * receiver service continues without BLE, and the next [start]
     * attempt will retry.
     *
     * @param mode scan mode constant from [ScanSettings].
     * @return `true` if the scanner ended up running on the requested
     *   mode (either already running on it, or successfully transitioned
     *   to it, or the scanner is currently stopped and the mode is now
     *   pinned for next start). `false` if a re-registration was
     *   attempted but the platform refused.
     */
    public fun setScanMode(mode: Int): Boolean =
        synchronized(lifecycleLock) {
            // No-op when the requested mode equals the current one.
            // Covers both "already scanning at this mode" and "idle and
            // mode is already the pinned-for-next-start value" — the
            // latter avoids a noisy "BALANCED -> BALANCED" logcat line
            // at first observer attach.
            if (mode == currentScanMode) return@synchronized true
            val previous = currentScanMode
            currentScanMode = mode

            // Mode change while idle: just remember the new mode for the
            // next start(). No platform call is needed.
            if (registration == null) {
                Log.w(
                    TAG,
                    "setScanMode: scanner idle, pinned mode for next start: " +
                        "${describeScanMode(previous)} -> ${describeScanMode(mode)}",
                )
                return@synchronized true
            }

            // Active path: tear down the existing registration and bring
            // a new one up under the new settings. We deliberately
            // route through gate.startScan rather than a hypothetical
            // "update settings in place" call — the platform API does
            // not expose one and re-registration is the
            // documented pattern.
            val sink = primarySink
            registration?.let { current ->
                try {
                    current.close()
                } catch (
                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                ) {
                    Log.w(TAG, "setScanMode: close of previous registration threw", t)
                }
            }
            registration = null

            val restarted =
                try {
                    gate.startScan(mode)
                } catch (
                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                ) {
                    Log.w(TAG, "setScanMode: re-register at new mode failed", t)
                    null
                }
            if (restarted == null) {
                // Restart failed — drop the sink registration as well
                // so the next start() is a clean slate.
                if (sink != null) gate.removeSink(sink)
                primarySink = null
                Log.w(
                    TAG,
                    "setScanMode: ${describeScanMode(previous)} -> ${describeScanMode(mode)} " +
                        "FAILED, scanner is now stopped",
                )
                return@synchronized false
            }
            registration = restarted
            Log.w(
                TAG,
                "setScanMode: ${describeScanMode(previous)} -> ${describeScanMode(mode)}",
            )
            true
        }

    /** Currently-active platform scan mode. Reflects the value last
     *  passed to [setScanMode], or [ScanSettings.SCAN_MODE_BALANCED]
     *  before the first call. Useful for diagnostics / tests. */
    public val activeScanMode: Int
        get() = currentScanMode

    /**
     * Stop scanning. Idempotent: calling [stop] when no scan is in
     * flight is a no-op.
     */
    public fun stop() {
        synchronized(lifecycleLock) {
            val current = registration ?: return@synchronized
            primarySink?.let { gate.removeSink(it) }
            primarySink = null
            try {
                current.close()
            } catch (
                @Suppress("TooGenericExceptionCaught") t: Throwable,
            ) {
                // SecurityException possible if BLUETOOTH_SCAN was revoked at runtime;
                // the registration is still discarded so the next start() will be clean.
                Log.w(TAG, "stop: BLE scan close threw", t)
            }
            registration = null
            inactivityJob?.cancel()
            inactivityJob = null
            activityState.value = ScanActivity.Idle
            Log.w(TAG, "stop: BLE pulse scan stopped")
        }
    }

    /** True iff a scan is currently in flight. */
    public val isScanning: Boolean
        get() = registration != null

    private fun recordPulse() {
        val now = nowMillis()
        activityState.value = ScanActivity.Active(lastSeenAtMillis = now)
        // Refresh the inactivity timer: cancel the in-flight one
        // (if any) and start a new one. We do not need atomicity
        // beyond "cancel old, schedule new" because the StateFlow
        // already serializes value updates.
        inactivityJob?.cancel()
        inactivityJob =
            coroutineScope.launch {
                delay(inactivityTimeoutMillis)
                if (isActive) {
                    val current = activityState.value
                    if (current is ScanActivity.Active && current.lastSeenAtMillis == now) {
                        activityState.value = ScanActivity.Idle
                    }
                }
            }
    }

    /**
     * Test-only entry point that simulates the platform delivering a
     * matching pulse. Used by JVM unit tests to drive the inactivity
     * timeout state machine without standing up a real Bluetooth stack.
     */
    internal fun forTestEmitPulse() {
        recordPulse()
    }

    public companion object {
        /** logcat tag for BLE-pulse-related diagnostics. */
        internal const val TAG: String = "LibreDropBleScan"

        /**
         * 30 s default inactivity window per the acceptance criterion in
         * issue #33. After the last seen pulse, [activity] flips back to
         * [ScanActivity.Idle] this many milliseconds later.
         */
        public const val DEFAULT_INACTIVITY_TIMEOUT_MILLIS: Long = 30_000L

        /**
         * Extra buffer for the [pulses] shared flow. A small ring lets
         * concurrent subscribers and brief consumer back-pressure absorb
         * a burst of advertisements without dropping pulses on the
         * floor; 16 was picked as comfortably above typical
         * `SCAN_MODE_BALANCED` arrival rates.
         */
        private const val PULSES_BUFFER: Int = 16

        /**
         * String form of the Quick Share BLE service UUID per
         * PROTOCOL.md — the 16-bit short ID `0xFE2C` expanded into the
         * SIG base UUID. Pin the string here so JVM unit tests, where
         * [ParcelUuid] is mocked out by AGP, can still verify the wire
         * value matches the spec.
         */
        public const val QUICK_SHARE_SERVICE_UUID_STRING: String =
            "0000fe2c-0000-1000-8000-00805f9b34fb"

        /**
         * [ParcelUuid] form of [QUICK_SHARE_SERVICE_UUID_STRING] used
         * by the platform [ScanFilter] API.
         */
        public val QUICK_SHARE_SERVICE_UUID: ParcelUuid
            get() = ParcelUuid.fromString(QUICK_SHARE_SERVICE_UUID_STRING)

        /**
         * 14-byte service-data prefix shared by every Quick Share BLE
         * pulse. The trailing 2 bytes of the 16-byte payload are random
         * per advertisement and are matched with a zero mask (any value
         * accepted).
         *
         * Returns a fresh copy on each call so callers cannot mutate the
         * canonical bytes; the array is small and infrequently read so
         * the copy cost is negligible.
         */
        public val SERVICE_DATA_PREFIX: ByteArray
            get() =
                byteArrayOf(
                    0xfc.toByte(),
                    0x12.toByte(),
                    0x8e.toByte(),
                    0x01.toByte(),
                    0x42.toByte(),
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                )

        /**
         * Mask applied alongside [SERVICE_DATA_PREFIX]. `0xff` for each
         * pinned prefix byte makes the platform compare it byte-for-byte;
         * the mask is the same length as the prefix.
         */
        public val SERVICE_DATA_MASK: ByteArray
            get() = ByteArray(SERVICE_DATA_PREFIX.size) { 0xff.toByte() }

        /**
         * Builds the canonical Quick Share BLE scan filter list.
         *
         * Used by [AndroidBleScannerGate] to compose the platform
         * `startScan` arguments. Exposed (internal) so any future
         * additional gate implementations stay consistent with the
         * spec'd prefix + mask without re-deriving them.
         *
         * Throws on a JVM unit-test runtime where AGP stubs out
         * [ScanFilter.Builder] — never call from a JVM test path.
         */
        internal fun buildScanFilters(): List<ScanFilter> =
            listOf(
                ScanFilter
                    .Builder()
                    .setServiceData(
                        QUICK_SHARE_SERVICE_UUID,
                        SERVICE_DATA_PREFIX,
                        SERVICE_DATA_MASK,
                    ).build(),
            )

        /**
         * Builds the platform [ScanSettings] for a given scan-mode
         * constant. Pinned configuration:
         *
         *  * `setScanMode` — variable, defaults to BALANCED at the
         *    scanner level, but switches to LOW_LATENCY while the user
         *    has the app foregrounded (#35).
         *  * `setReportDelay(0)` — pinned. Non-zero delay batches
         *    advertisements which would block the interactive
         *    "saw a sender" trigger.
         *
         * Throws on a JVM unit-test runtime where AGP stubs out
         * [ScanSettings.Builder] — never call from a JVM test path.
         */
        internal fun buildScanSettings(scanMode: Int = ScanSettings.SCAN_MODE_BALANCED): ScanSettings =
            ScanSettings
                .Builder()
                .setScanMode(scanMode)
                .setReportDelay(0)
                .build()

        /**
         * Human-readable label for the [ScanSettings] scan-mode
         * constants we care about. Used in logcat lines so a power-tuning
         * triage can grep `LibreDropBleScan setScanMode` and read the
         * transition without translating integers.
         */
        internal fun describeScanMode(mode: Int): String =
            when (mode) {
                ScanSettings.SCAN_MODE_OPPORTUNISTIC -> "OPPORTUNISTIC"
                ScanSettings.SCAN_MODE_LOW_POWER -> "LOW_POWER"
                ScanSettings.SCAN_MODE_BALANCED -> "BALANCED"
                ScanSettings.SCAN_MODE_LOW_LATENCY -> "LOW_LATENCY"
                else -> "UNKNOWN($mode)"
            }

        /**
         * Test-only factory matching the existing module convention. The
         * supplied [gate] and [permissionChecker] let JVM unit tests
         * exercise the lifecycle without instantiating the production
         * Android wiring.
         */
        @JvmStatic
        internal fun forTesting(
            coroutineScope: CoroutineScope,
            gate: BleScannerGate,
            permissionChecker: PermissionChecker = PermissionChecker { true },
            inactivityTimeoutMillis: Long = DEFAULT_INACTIVITY_TIMEOUT_MILLIS,
            nowMillis: () -> Long = System::currentTimeMillis,
        ): BleQuickShareScanner =
            BleQuickShareScanner(
                coroutineScope = coroutineScope,
                gate = gate,
                permissionChecker = permissionChecker,
                inactivityTimeoutMillis = inactivityTimeoutMillis,
                nowMillis = nowMillis,
            )
    }
}

/**
 * Coarse activity state surfaced to consumers. Issue #34's mDNS gate
 * uses this to decide whether to publish the receiver mDNS service.
 */
public sealed interface ScanActivity {
    /** No matching pulse seen within the inactivity window. */
    public data object Idle : ScanActivity

    /**
     * A matching pulse has been seen recently. [lastSeenAtMillis] is
     * the timestamp (per the scanner's clock) of the most recent
     * matching advertisement. The state automatically flips back to
     * [Idle] after the configured inactivity timeout elapses.
     */
    public data class Active(
        public val lastSeenAtMillis: Long,
    ) : ScanActivity
}

/**
 * Permission gate consulted by [BleQuickShareScanner.start] before
 * attempting a platform scan. Lifted to its own interface so JVM tests
 * can stub it without depending on [ContextCompat].
 */
public fun interface PermissionChecker {
    public fun hasScanPermission(): Boolean
}

internal class AndroidPermissionChecker(
    private val context: Context,
) : PermissionChecker {
    override fun hasScanPermission(): Boolean {
        // On API ≤ 30 the install-time BLUETOOTH / BLUETOOTH_ADMIN
        // permissions are declared in the umbrella manifest and the
        // runtime check below trivially passes. From API 31+ we need
        // the runtime BLUETOOTH_SCAN grant.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN,
        ) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Abstract handle over the platform [BluetoothLeScanner]. The real
 * Android type is final, so we wrap it instead of mocking.
 *
 * The gate owns:
 *   * the platform scan configuration (filter prefix, mask, scan mode),
 *   * the platform [ScanCallback] subclass (which JVM unit tests cannot
 *     instantiate because the AGP stub jar lacks bytecode for its
 *     non-abstract methods).
 *
 * Consumers register a [PulseSink] to receive deserialized results — no
 * AGP-stubbed type ever leaks across the gate boundary, so unit tests
 * on a plain JVM can drive the scanner end-to-end.
 */
public interface BleScannerGate {
    /**
     * Begins a Quick-Share-shaped platform scan with the given scan
     * mode. Returns a [Registration] handle whose [Registration.close]
     * stops the platform scan, or `null` if the scan could not be
     * started (adapter off, no LE support, etc.).
     *
     * The gate is responsible for building the canonical [ScanFilter]
     * (service UUID `0xFE2C` + 14-byte service-data prefix) and the
     * [ScanSettings] (`reportDelay = 0`, scan mode = [scanMode]) from
     * the public companions on [BleQuickShareScanner].
     *
     * The gate is also responsible for fanning each [ScanResult] out to
     * every [PulseSink] registered via [addSink].
     *
     * @param scanMode one of [ScanSettings.SCAN_MODE_LOW_POWER],
     *   [ScanSettings.SCAN_MODE_BALANCED], or
     *   [ScanSettings.SCAN_MODE_LOW_LATENCY]. The scanner uses BALANCED
     *   by default and switches to LOW_LATENCY while the app is
     *   foregrounded (#35).
     */
    public fun startScan(scanMode: Int): Registration?

    /**
     * Subscribes a [PulseSink] to receive results from the active scan.
     * Subsequent matching advertisements are delivered via
     * [PulseSink.onPulse]; scan failures are delivered via
     * [PulseSink.onFailed].
     */
    public fun addSink(sink: PulseSink)

    /** Removes a sink previously registered via [addSink]. */
    public fun removeSink(sink: PulseSink)

    /** Token returned from [startScan]; close to stop the underlying scan. */
    public interface Registration : AutoCloseable {
        /** Stops the underlying platform scan. Idempotent. */
        public override fun close()
    }

    /**
     * Consumer-side handle for matching pulses. Implementations live
     * inside [BleQuickShareScanner]; tests provide their own where
     * needed.
     */
    public interface PulseSink {
        /** Fired for each matching advertisement. */
        public fun onPulse(result: ScanResult)

        /**
         * Fired when the platform scanner reports a non-recoverable
         * failure. The default no-ops; the production scanner logs.
         */
        public fun onFailed(errorCode: Int) {
            // Default: no-op.
        }
    }
}

/**
 * Production [BleScannerGate] backed by [BluetoothLeScanner]. Owns one
 * platform scan and fans out [ScanResult]s to a list of subscriber
 * [BleScannerGate.PulseSink]s — needed because `BluetoothLeScanner`
 * itself only accepts a single callback per registration but the
 * scanner has multiple downstream consumers.
 *
 * The platform [ScanCallback] is constructed only when needed: the
 * gate's lifetime is bound to a real Android device, so its instances
 * never have to survive a JVM unit-test class load.
 */
internal class AndroidBleScannerGate(
    private val context: Context,
) : BleScannerGate {
    private val sinks = mutableListOf<BleScannerGate.PulseSink>()
    private val sinksLock = Any()

    private val rootCallback: ScanCallback by lazy { createRootCallback() }

    private fun createRootCallback(): ScanCallback =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult?,
            ) {
                if (result == null) return
                snapshotSinks().forEach { it.onPulse(result) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                if (results.isNullOrEmpty()) return
                snapshotSinks().forEach { sink ->
                    results.forEach { sink.onPulse(it) }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                snapshotSinks().forEach { it.onFailed(errorCode) }
            }
        }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun startScan(scanMode: Int): BleScannerGate.Registration? {
        val scanner = resolveScanner() ?: return null
        scanner.startScan(
            BleQuickShareScanner.buildScanFilters(),
            BleQuickShareScanner.buildScanSettings(scanMode),
            rootCallback,
        )
        return Registration(scanner)
    }

    override fun addSink(sink: BleScannerGate.PulseSink) {
        synchronized(sinksLock) { sinks += sink }
    }

    override fun removeSink(sink: BleScannerGate.PulseSink) {
        synchronized(sinksLock) { sinks.remove(sink) }
    }

    private fun snapshotSinks(): List<BleScannerGate.PulseSink> = synchronized(sinksLock) { sinks.toList() }

    @Suppress("ReturnCount")
    private fun resolveScanner(): BluetoothLeScanner? {
        // Each early-return covers a distinct failure mode that the
        // production logs already differentiate; collapsing them into a
        // single chained ?: hides the cause when one trips. Suppress
        // the detekt warning explicitly rather than restructuring.
        val manager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: return null
        val adapter: BluetoothAdapter = manager.adapter ?: return null
        if (!adapter.isEnabled) return null
        return adapter.bluetoothLeScanner
    }

    private inner class Registration(
        private val scanner: BluetoothLeScanner,
    ) : BleScannerGate.Registration {
        @Volatile
        private var closed: Boolean = false

        @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
        override fun close() {
            if (closed) return
            closed = true
            try {
                scanner.stopScan(rootCallback)
            } catch (
                @Suppress("TooGenericExceptionCaught") t: Throwable,
            ) {
                // Adapter may have been turned off after startScan succeeded;
                // we still want the registration discarded.
                Log.w(BleQuickShareScanner.TAG, "stopScan threw", t)
            }
        }
    }
}

/**
 * Top-level supervisor for the [BleQuickShareScanner] lifecycle. Owns a
 * private [CoroutineScope] so consumers don't have to wire one up
 * themselves; cancel by calling [shutdown].
 *
 * Receiver services typically construct one of these per session,
 * call [start] when the foreground service comes up, and call
 * [shutdown] from `onDestroy`.
 */
public class BleScannerHost(
    context: Context,
    inactivityTimeoutMillis: Long = BleQuickShareScanner.DEFAULT_INACTIVITY_TIMEOUT_MILLIS,
) : AutoCloseable {
    private val supervisor: Job = SupervisorJob()
    private val scope: CoroutineScope = CoroutineScope(supervisor + Dispatchers.Default)

    public val scanner: BleQuickShareScanner =
        BleQuickShareScanner(
            context = context,
            coroutineScope = scope,
            inactivityTimeoutMillis = inactivityTimeoutMillis,
        )

    /** Begins scanning. Safe to call multiple times. */
    public fun start(): Boolean = scanner.start()

    /**
     * Switches the platform scan mode. Forwards to
     * [BleQuickShareScanner.setScanMode]; see that method for the
     * acceptable [ScanSettings] constants.
     */
    public fun setScanMode(mode: Int): Boolean = scanner.setScanMode(mode)

    /** Stops scanning and tears down the owning scope. */
    public fun shutdown() {
        scanner.stop()
        scope.cancel()
    }

    override fun close() {
        scanner.stop()
        scope.cancel()
    }
}
