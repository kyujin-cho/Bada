/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.receiver

import android.app.Service
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.kyujincho.wvmg.discovery.Discovery
import io.github.kyujincho.wvmg.discovery.ble.BleQuickShareScanner
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import io.github.kyujincho.wvmg.service.downloads.DownloadsWriterFactory
import io.github.kyujincho.wvmg.service.receiver.consent.ConsentBroadcastReceiver
import io.github.kyujincho.wvmg.service.receiver.consent.ConsentCoordinator
import io.github.kyujincho.wvmg.service.receiver.consent.ConsentNotification
import io.github.kyujincho.wvmg.service.receiver.consent.ConsentRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Persistent foreground service that hosts the inbound Quick Share
 * listener. Android 14+ requires every foreground service to declare a
 * specific type; `connectedDevice` is the right one for peer-to-peer
 * file transfer over Wi-Fi (and, in Phase 2, BLE).
 *
 * ### Why a foreground service
 *
 * We need to be reachable to senders while the user has navigated away
 * from the app, the screen is off, or the device is in
 * Doze/App-Standby. Foreground services are the documented Android
 * mechanism for this — the system schedules them generously, kills
 * them last under memory pressure, and surfaces a persistent
 * notification so the user is never surprised about resource usage.
 *
 * ### Manifest declarations required
 *
 *  - `<service android:foregroundServiceType="connectedDevice"/>` on
 *    this class.
 *  - `<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>`
 *  - `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"/>`
 *    (the latter is API 34+ only — guarded with `tools:targetApi`).
 *
 * ### Lifecycle
 *
 * The bulk of this class is plumbing; the actual coordination lives in
 * [ReceiverSession]. The service:
 *
 *  1. Creates the notification channel on first start (idempotent).
 *  2. Builds a [ReceiverSession] and calls `start()` on a coroutine
 *     under [serviceScope].
 *  3. Calls `startForeground` with the persistent notification so
 *     Android promotes the service immediately (must happen within
 *     `Service.FOREGROUND_SERVICE_GRACE_PERIOD_MS` after `startService`).
 *  4. On `onDestroy` — or on an explicit `ACTION_STOP` intent —
 *     calls [ReceiverSession.stop], cancels the scope, and exits.
 *
 * Returning [START_STICKY] tells Android to resurrect the service if
 * the system kills it under memory pressure. The user-visible
 * notification keeps state across that resurrection.
 *
 * ### Public entry points
 *
 *  - [Companion.start] — convenience for callers that just want to
 *    bring the service up. Wraps the
 *    `ContextCompat.startForegroundService` boilerplate.
 *  - [Companion.stop] — symmetric stop.
 *
 * ### Dependency injection
 *
 * For tests, the service exposes [Companion.sessionFactoryOverride].
 * Production paths use the default factory which wires up a real
 * `MediaStoreDownloadsFactory` and a real `Discovery` instance against
 * the application context. The override is process-wide but only
 * consulted on `onCreate`; a unit test can install a fake, spawn a
 * service via Robolectric, and observe the resulting [ReceiverSession]
 * interactions without touching real Android subsystems. Phase 1 keeps
 * the override path purely as a seam — the
 * `ReceiverSession` itself is exhaustively unit-tested on plain JVM
 * (see [ReceiverSession]) so the service body remains the only
 * Android-coupled surface.
 */
public class ReceiverForegroundService : Service() {
    private val serviceJob: Job = SupervisorJob()
    private val serviceScope: CoroutineScope = CoroutineScope(serviceJob + Dispatchers.IO)

    @Volatile
    private var session: ReceiverSession? = null

    @Volatile
    private var consentCoordinator: ConsentCoordinator? = null

    @Volatile
    private var consentReceiver: BroadcastReceiver? = null

    @Volatile
    private var bleScanner: BleQuickShareScanner? = null

    @Volatile
    private var mdnsGate: MdnsAdvertisementGate? = null

    /**
     * Observer attached to [ProcessLifecycleOwner] that switches the
     * BLE scan mode based on whether the user has the app foregrounded.
     * `null` while the service is not running; populated by
     * [startBleScanner] and detached by [stopReceiverAndExit].
     *
     * The observer runs on the main thread (per the `ProcessLifecycleOwner`
     * contract); the [BleQuickShareScanner.setScanMode] call it makes is
     * synchronized internally and runs the platform call inline, so
     * blocking the main thread here is bounded by a single
     * `BluetoothLeScanner.startScan` round-trip.
     */
    @Volatile
    private var processLifecycleObserver: AppLifecycleScanModeObserver? = null

    override fun onCreate() {
        super.onCreate()
        ReceiverNotification.ensureChannel(this)
        // The consent channel must exist before we post the first
        // heads-up notification — the platform silently drops
        // notifications targeting a missing channel on API 26+.
        ConsentNotification.ensureChannel(this)
        registerConsentReceiver()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            stopReceiverAndExit()
            return START_NOT_STICKY
        }

        // Promote to the foreground BEFORE doing any setup work. Android
        // 14+ enforces that startForeground happens within
        // FOREGROUND_SERVICE_GRACE_PERIOD_MS of startForegroundService;
        // calling it here makes the deadline trivial to meet regardless
        // of how long the receiver setup takes below.
        val notification =
            ReceiverNotification.build(
                context = this,
                contentIntent = ReceiverNotification.buildOpenAppIntent(this, openAppTarget),
                // Surface the current SSID in the persistent
                // notification (#85). `readCurrentSsid` returns `null`
                // when the SSID is unavailable / redacted, in which
                // case the builder falls back to a generic body.
                ssid = CurrentSsidProvider.readCurrentSsid(this),
            )
        ServiceCompat.startForeground(
            this,
            ReceiverNotification.NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )

        // Bring up the receiver session if it isn't already running.
        // Multiple startService calls are idempotent — the second one
        // just keeps the existing session alive.
        if (session == null) {
            startReceiverSession()
            startBleScanner()
        }

        return START_STICKY
    }

    /**
     * Start the Phase 2 BLE pulse scanner (#33) with the battery-tuned
     * scan settings from #35. The scanner observes sender BLE
     * advertisements so a downstream feature (#34, mDNS gating) can
     * hold off mDNS publish until a matching pulse is seen. Failures
     * here — `BLUETOOTH_SCAN` not granted, adapter disabled, no LE
     * support — are non-fatal and logged inside
     * [BleQuickShareScanner.start]; the receiver continues in
     * mDNS-only mode.
     *
     * The scan starts in [ScanSettings.SCAN_MODE_BALANCED] (the
     * documented "low-power, foreground-service-friendly" mode that
     * Android allows to run continuously without throttling). An
     * [AppLifecycleScanModeObserver] attached to
     * [ProcessLifecycleOwner] then upgrades to
     * [ScanSettings.SCAN_MODE_LOW_LATENCY] while the user has the app
     * foregrounded — responsiveness matters more than power during
     * active interaction — and reverts to BALANCED when the app moves
     * back to the background.
     *
     * Owns both the scanner instance and the lifecycle observer so
     * [stopReceiverAndExit] can tear them down symmetrically.
     */
    private fun startBleScanner() {
        if (bleScanner != null) return
        val scanner =
            BleQuickShareScanner(
                context = applicationContext,
                coroutineScope = serviceScope,
            )
        bleScanner = scanner
        ActiveBleScannerHolder.set(scanner)
        // start() is idempotent and non-suspending; the receiver service
        // controls the single scan registration over its lifetime.
        scanner.start()

        // Attach the foreground/background scan-mode observer (#35).
        // ProcessLifecycleOwner requires its observers to be attached
        // on the main thread.
        attachProcessLifecycleObserver(scanner)
    }

    /**
     * Add an [AppLifecycleScanModeObserver] to [ProcessLifecycleOwner]
     * so the scan mode tracks the app's foreground/background state.
     *
     * Marshalled onto the main thread because both
     * `ProcessLifecycleOwner.lifecycle.addObserver` and
     * `LifecycleOwner` callbacks must run on the main thread per
     * AndroidX contract. `Service.onStartCommand` is itself called on
     * the main thread, so this `post` is a no-op when invoked from the
     * normal lifecycle path — it only exists as a defensive measure
     * for any future call site that might be migrated off the main
     * thread.
     */
    private fun attachProcessLifecycleObserver(scanner: BleQuickShareScanner) {
        val observer = AppLifecycleScanModeObserver { mode -> scanner.setScanMode(mode) }
        processLifecycleObserver = observer
        runOnMainThread {
            ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Tear the receiver down before the scope is cancelled so the
        // NSD unregister and TCP listener close get a chance to run on
        // the IO dispatcher.
        stopReceiverAndExit()
        super.onDestroy()
    }

    /**
     * Construct the [ReceiverSession] from [Companion.sessionFactory] and
     * launch its lifecycle on [serviceScope]. Failures during start
     * result in the service stopping itself; the caller observes the
     * absent notification and the UI surfaces the error in #22.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun startReceiverSession() {
        val newSession = sessionFactory.invoke(applicationContext)
        session = newSession

        // Start the consent coordinator before the session so the
        // SharedFlow subscriptions are in place when the first
        // accepted connection is emitted.
        startConsentCoordinator(newSession)

        serviceScope.launch {
            try {
                newSession.start()
                // Once start() returns successfully the TCP listener is
                // bound. With the gated-advertise factory in production,
                // the mDNS publish is not yet up — the gate launched
                // below handles that. Multicast-lock acquisition is no
                // longer needed: NsdManager owns the system mDNS
                // responder, which has its own multicast filter
                // exemption.
                startMdnsGate(newSession)
            } catch (
                @Suppress("SwallowedException") t: Throwable,
            ) {
                // Setup failed — bring the service down so the user
                // notification doesn't claim we're listening when we
                // aren't. We log nothing here on purpose; richer
                // error reporting belongs to the in-app status surface
                // (#22 covers the consent UI; broader transfer
                // reporting is a follow-up).
                stopReceiverAndExit()
            }
        }

        // Periodically log the discovery diagnostics so issue #83's
        // silent-failure surface is visible in logcat without needing a
        // debug screen. The cadence is intentionally slow (every 10s)
        // so we don't spam logcat in the steady state.
        startDiscoveryDiagnosticsLogger()
    }

    /**
     * Construct and launch the [MdnsAdvertisementGate] that drives
     * [ReceiverSession.publishAdvertisement] / `unpublishAdvertisement`
     * from BLE pulse activity (#34). Called after [ReceiverSession.start]
     * returns, so the bound TCP port is already known.
     *
     * If [bleScanner] is `null` (BLE failed to start, e.g. permission
     * denied), the gate observes a never-emitting source — the override
     * holder remains the only signal that can drive a publish. Users
     * can still toggle the always-visible override to run the receiver
     * in mDNS-only mode.
     */
    private fun startMdnsGate(activeSession: ReceiverSession) {
        // Bail out if the service has already been torn down between
        // session.start() returning and this method running. Without
        // the running check, a quick start/stop sequence could leak a
        // gate whose collector keeps a reference to a stopped session.
        if (mdnsGate != null) return
        if (!activeSession.isRunning) return
        val gate =
            MdnsAdvertisementGate(
                session = activeSession,
                bleActivity =
                    bleScanner?.activity
                        ?: kotlinx.coroutines.flow.MutableStateFlow(
                            io.github.kyujincho.wvmg.discovery.ble.ScanActivity.Idle,
                        ),
                alwaysVisibleOverride = MdnsVisibilityOverrideHolder.activeFlow,
                qrSessionActive = QrSessionActiveHolder.activeFlow,
            )
        gate.start(serviceScope)
        mdnsGate = gate
    }

    /**
     * Emit a [DiscoveryDiagnostics] line to logcat every
     * [DIAGNOSTICS_LOG_INTERVAL_MS] ms while the service is running.
     * Wired off the [ActiveDiscoveryHolder] so any session factory that
     * stores a [Discovery] there gets observed for free; if no
     * [Discovery] is registered (e.g. tests installing a custom
     * factory) the loop simply skips logging and idles.
     */
    private fun startDiscoveryDiagnosticsLogger() {
        serviceScope.launch {
            while (isActive) {
                val discovery = ActiveDiscoveryHolder.current()
                if (discovery != null) {
                    val snap = discovery.snapshot()
                    Log.i(
                        DIAGNOSTICS_TAG,
                        "discovery snapshot: " +
                            "advertising=${snap.advertising} browsing=${snap.browsing} " +
                            "lockHeld=${snap.multicastLockHeld} " +
                            "advBound=${snap.advertiseBoundAddress?.hostAddress ?: "-"} " +
                            "browseBound=${snap.browseBoundAddress?.hostAddress ?: "-"} " +
                            "events=${snap.recentEvents.size}",
                    )
                }
                delay(DIAGNOSTICS_LOG_INTERVAL_MS)
            }
        }
    }

    /**
     * Wire a [ConsentCoordinator] over the session's flows so that
     * each `WaitingForUserConsent` transition is surfaced as a
     * heads-up notification posted via [ConsentNotification].
     */
    private fun startConsentCoordinator(session: ReceiverSession) {
        val ctx = applicationContext
        val coordinator =
            ConsentCoordinator(
                activeConnections = session.activeConnections,
                results = session.completions,
                registry = ConsentRegistry.instance,
                sink =
                    object : ConsentCoordinator.Sink {
                        override fun postConsent(
                            connectionId: Long,
                            entry: ConsentRegistry.Entry,
                        ) {
                            ConsentNotification.post(
                                context = ctx,
                                connectionId = connectionId,
                                entry = entry,
                                trampolineTarget = consentTrampolineTarget,
                            )
                        }

                        override fun dismissConsent(connectionId: Long) {
                            ConsentNotification.dismiss(ctx, connectionId)
                        }
                    },
                scope = serviceScope,
            )
        coordinator.start()
        consentCoordinator = coordinator
    }

    /**
     * Register the [ConsentBroadcastReceiver] dynamically. We use
     * dynamic registration because the registry is in-process state —
     * a manifest-registered receiver that survives process death
     * would have nothing to dispatch to. `RECEIVER_NOT_EXPORTED` (API
     * 33+) ensures other apps cannot fire our consent broadcasts.
     */
    private fun registerConsentReceiver() {
        if (consentReceiver != null) return
        val receiver = ConsentBroadcastReceiver()
        val filter =
            IntentFilter().apply {
                ConsentBroadcastReceiver.ACTION_FILTER.forEach { addAction(it) }
            }
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        consentReceiver = receiver
    }

    /**
     * Run [block] on the main thread. If we are already on the main
     * thread, executes inline; otherwise posts to a [Handler] bound to
     * [Looper.getMainLooper]. Used to satisfy the `ProcessLifecycleOwner`
     * "observers must be attached on the main thread" contract from
     * any future call site.
     */
    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post(block)
        }
    }

    private fun unregisterConsentReceiverIfNeeded() {
        val receiver = consentReceiver ?: return
        runCatching { unregisterReceiver(receiver) }
        consentReceiver = null
    }

    private fun stopReceiverAndExit() {
        consentCoordinator?.stop()
        consentCoordinator = null
        // Dismiss every pending consent notification we know about.
        // The connection itself will be torn down when the session
        // stops; without explicit dismissal the heads-up would linger
        // pointing at a dead connection.
        val ctx = applicationContext
        ConsentRegistry.instance.snapshotIds().forEach { id ->
            ConsentRegistry.instance.unregister(id)
            ConsentNotification.dismiss(ctx, id)
        }
        unregisterConsentReceiverIfNeeded()

        // Stop the gate before the session so its delayed unpublish
        // coroutine can no longer fire after `session.stop()` has
        // already torn the AdvertiseHandle down.
        mdnsGate?.stop()
        mdnsGate = null

        session?.stop()
        session = null
        ActiveDiscoveryHolder.clear()

        // Detach the ProcessLifecycleOwner observer first — once the
        // scanner is stopped, any late foreground/background callback
        // would just be a no-op on a stopped scanner, but holding the
        // observer attached past `stopReceiverAndExit` would leak the
        // service through the global ProcessLifecycleOwner.
        processLifecycleObserver?.let { observer ->
            runOnMainThread {
                ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
            }
        }
        processLifecycleObserver = null

        // Stop the BLE scanner before cancelling the scope so the
        // platform `stopScan` actually runs synchronously; otherwise
        // the registration could outlive the service for a window.
        bleScanner?.stop()
        bleScanner = null
        ActiveBleScannerHolder.clear()

        serviceJob.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    public companion object {
        /**
         * Action string for the explicit "stop the service" intent.
         * Sent by the in-app stop control (#22) and by the share-intent
         * router after a transfer completes if the receiver was started
         * implicitly. External components must not use this; the action
         * is hardcoded into the package, not exported.
         */
        public const val ACTION_STOP: String = "io.github.kyujincho.wvmg.service.receiver.ACTION_STOP"

        /**
         * Intent extra carrying a serialized [EndpointInfo] when the
         * caller wants to override the service's identity (e.g. tests
         * or a future "rename device" feature). Optional — when absent
         * the service falls back to a process-default identity.
         */
        public const val EXTRA_ENDPOINT_INFO: String = "wvmg.endpoint_info"

        /**
         * The activity class to open on notification tap. The `:app`
         * module sets this in its `Application.onCreate` so the
         * `:service-android` library does not statically depend on
         * `MainActivity`. Defaults to `null`; in that case the
         * notification is non-tappable.
         */
        @JvmStatic
        @Volatile
        public var openAppTarget: Class<*>? = null

        /**
         * The activity class to launch as the consent **trampoline**
         * (#22). Pressed when the user taps the heads-up consent
         * notification body — opens a screen-on, lock-screen-bypassing
         * activity that shows full transfer details and re-uses the
         * Accept / Reject decisions through [ConsentRegistry].
         *
         * Set by `:app` in its `Application.onCreate`; `:service-android`
         * does not statically depend on the activity class so this
         * field stays nullable. When `null`, the notification's
         * content tap is omitted but the action buttons still work.
         */
        @JvmStatic
        @Volatile
        public var consentTrampolineTarget: Class<*>? = null

        /**
         * Process-wide override of the [SessionFactory]. Tests install
         * a fake here before binding to the service; production never
         * touches it. Reset to `null` between tests so a stale fake
         * does not leak across cases.
         */
        @JvmStatic
        @Volatile
        public var sessionFactoryOverride: SessionFactory? = null

        /**
         * The active [SessionFactory]. Returns the override if one was
         * installed, otherwise the production default that wires up a
         * real `Discovery` and `DownloadsWriterFactory`.
         */
        public val sessionFactory: SessionFactory
            get() = sessionFactoryOverride ?: defaultSessionFactory

        /**
         * Stable holder for the production default. Lazily computed so
         * tests that install an override before any service start have
         * the chance to do so without paying for the production wiring.
         */
        private val defaultSessionFactory: SessionFactory =
            SessionFactory { context ->
                val identity = EndpointIdentityHolder.snapshot.get() ?: defaultEndpointInfo(context)
                EndpointIdentityHolder.snapshot.compareAndSet(null, identity)
                // Keep one Discovery per session so the periodic
                // diagnostic snapshot in [startReceiverSession] reflects
                // the same instance the advertise lambda is using.
                val discovery = Discovery(context)
                ActiveDiscoveryHolder.set(discovery)
                ReceiverSession(
                    tcpServerFactory = TcpServerFactory.default(),
                    advertiser =
                        DiscoveryAdvertiser { endpointInfo, port ->
                            discovery.advertise(endpointInfo, port)
                        },
                    factoryProvider = { DownloadsWriterFactory.create(context) },
                    endpointInfo = identity,
                    // Issue #34: defer mDNS publish to the
                    // [MdnsAdvertisementGate] so we only advertise while
                    // a sender BLE pulse is active (or the user has
                    // forced visibility on, or a QR session is in
                    // progress).
                    advertiseGated = true,
                )
            }

        /**
         * Bring up the foreground service. Wraps the platform call so
         * callers don't need to remember `startForegroundService` vs
         * `startService` per API level.
         */
        public fun start(context: Context) {
            val intent = Intent(context, ReceiverForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the foreground service via an explicit intent so the
         * `Service.onStartCommand` path runs and we can tear down
         * gracefully (rather than having the system kill us).
         */
        public fun stop(context: Context) {
            val intent =
                Intent(context, ReceiverForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
            // startService delivers onStartCommand even when the
            // service is already running, which is what we want.
            context.startService(intent)
        }

        /**
         * Build the default [EndpointInfo] for this device.
         *
         * **Visibility bit = 1 (hidden).** Stock Quick Share unconditionally
         * publishes mDNS records with `EndpointInfo.hidden = true`,
         * regardless of whether the user has selected "Everyone" or
         * "Contacts only" in the Quick Share UI. The Everyone-vs-Contacts
         * decision is enforced during the protocol negotiation, not at
         * the mDNS layer. Samsung One UI's stock Quick Share picker
         * appears to whitelist records with this canonical shape and
         * silently drops `visBit=0` records as malformed/non-conformant —
         * which is why WVMG was invisible to a Galaxy peer's send sheet
         * despite mDNS being fully reachable on the wire (verified via
         * `dns-sd` from a third device on the same hotspot).
         *
         * **`deviceName = null` follows from `hidden = true`.** The
         * `EndpointInfo` invariant requires the inline UTF-8 name to be
         * absent when the visibility bit is set; stock conveys the
         * friendly name through other channels (BLE service-data on the
         * sender pulse, plus the encrypted-name TLV in Contacts mode).
         * For our GMS-free Everyone-only use case the consequence is that
         * Samsung's picker shows a generic label until connect — that is
         * acceptable interop for now and is tracked as a follow-up to
         * surface the friendly name via TLV type 1 (advertising token /
         * encrypted name material) when we can.
         *
         * Random salt + encrypted-metadata-key bytes are indistinguishable
         * to peers from real GMS-issued ones for the GMS-free use case
         * targeted by this project.
         */
        private fun defaultEndpointInfo(context: Context): EndpointInfo {
            // The application label is still loaded so future code paths
            // (e.g. consent UI, BLE service data) that surface a friendly
            // name can pull from the same source. The mDNS record itself
            // intentionally does not carry it — see the kdoc above.
            @Suppress("UNUSED_VARIABLE")
            val applicationLabel =
                context.applicationInfo
                    .loadLabel(context.packageManager)
                    .toString()
                    .ifBlank { DEFAULT_DEVICE_NAME }
                    .take(MAX_DEFAULT_NAME_BYTES)
            return EndpointInfo(
                version = 1,
                hidden = true,
                deviceType =
                    io.github.kyujincho.wvmg.protocol.endpoint.DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN).also { java.security.SecureRandom().nextBytes(it) },
                deviceName = null,
                tlvRecords = emptyList(),
            )
        }

        private const val DEFAULT_DEVICE_NAME = "Quick Share"

        // 64 bytes leaves comfortable headroom under the 255-byte
        // single-byte length limit and is more than enough for typical
        // app-label names ("Quick Share" is 11).
        private const val MAX_DEFAULT_NAME_BYTES = 64

        /**
         * Cadence for the discovery diagnostics logger spawned in
         * [startDiscoveryDiagnosticsLogger]. 10 s strikes a balance
         * between catching the silent-failure window from issue #83
         * (peers should be visible within a few seconds) and not
         * flooding logcat in steady state.
         */
        private const val DIAGNOSTICS_LOG_INTERVAL_MS: Long = 10_000L

        /** logcat tag for the diagnostics line — matches the discovery module. */
        private const val DIAGNOSTICS_TAG: String = "WvmgDiscovery"
    }
}

/**
 * Process-wide holder for the active [BleQuickShareScanner] instance.
 *
 * Issue #34's mDNS-gating logic needs read-only access to the scanner's
 * `activity` StateFlow without re-architecting the foreground service
 * dependency graph. Stashing it on a process-wide holder mirrors
 * [ActiveDiscoveryHolder]: no behaviour change unless a downstream
 * caller subscribes; tests that don't construct a real scanner simply
 * find `null` here.
 */
public object ActiveBleScannerHolder {
    @Volatile
    private var ref: BleQuickShareScanner? = null

    internal fun set(scanner: BleQuickShareScanner) {
        ref = scanner
    }

    /** The active scanner, or `null` if the receiver service is not running. */
    public fun current(): BleQuickShareScanner? = ref

    internal fun clear() {
        ref = null
    }
}

/**
 * Sticky "always visible" override for the mDNS gate (#34).
 *
 * The user surfaces this through the launcher activity (`MainActivity`)
 * — when toggled on, the receiver publishes mDNS unconditionally even
 * if no BLE pulse is in flight. Useful on devices where BLE scan is
 * unavailable (no `BLUETOOTH_SCAN` grant, no LE hardware) or whenever
 * the user wants to be discoverable regardless of sender activity.
 *
 * The flag is process-wide and lives in memory only — it resets across
 * process death. Persisting the toggle is a follow-up; the in-memory
 * surface is the minimum #34 needs to ship the override path.
 *
 * The gate subscribes to [activeFlow]; UI surfaces (and tests) flip the
 * value via [setAlwaysVisible].
 */
public object MdnsVisibilityOverrideHolder {
    private val state: kotlinx.coroutines.flow.MutableStateFlow<Boolean> =
        kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * Hot [kotlinx.coroutines.flow.StateFlow] of the override state.
     * `true` while the user has forced the receiver to be visible.
     */
    public val activeFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = state

    /** Current snapshot value of [activeFlow]. */
    public val isActive: Boolean
        get() = state.value

    /** Update the override. Safe to call from any thread. */
    public fun setAlwaysVisible(active: Boolean) {
        state.value = active
    }
}

/**
 * QR-code receive-flow bypass for the mDNS gate (#34 acceptance
 * criterion: "QR-code path overrides this gating entirely — see
 * #1.16").
 *
 * The receiver-side QR-code-driven flow (paired-key over a one-shot
 * scan) is being implemented in a separate issue; the holder is
 * exposed now so the gate logic stays correct once that flow lands.
 * Today the holder is wired to a hot flow with no producers — the
 * gate consults it but the value is always `false`.
 */
public object QrSessionActiveHolder {
    private val state: kotlinx.coroutines.flow.MutableStateFlow<Boolean> =
        kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Hot flow signalling whether a QR-code receive session is active. */
    public val activeFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = state

    /** Current snapshot value of [activeFlow]. */
    public val isActive: Boolean
        get() = state.value

    /**
     * Set the QR-session-active flag. The QR receive flow flips this on
     * when a scan begins and off when the resulting transfer completes
     * or is cancelled.
     */
    public fun setQrSessionActive(active: Boolean) {
        state.value = active
    }
}

/**
 * Process-wide holder for the active [Discovery] instance. The
 * receiver session factory stashes its [Discovery] here so the service
 * body's diagnostic-snapshot loop can read it without expanding the
 * [SessionFactory] interface. Tests that install a custom factory
 * never touch this holder and the snapshot loop simply finds `null`.
 */
internal object ActiveDiscoveryHolder {
    @Volatile
    private var ref: io.github.kyujincho.wvmg.discovery.Discovery? = null

    fun set(discovery: io.github.kyujincho.wvmg.discovery.Discovery) {
        ref = discovery
    }

    fun current(): io.github.kyujincho.wvmg.discovery.Discovery? = ref

    fun clear() {
        ref = null
    }
}

/**
 * Process-singleton holder for the receiver's stable [EndpointInfo].
 *
 * The receiver advertises a single identity per process lifetime. We
 * generate the salt + encrypted-key on first `onCreate` and pin it for
 * subsequent restarts (e.g. after `START_STICKY` brings the service
 * back). This matches NearDrop's behaviour — Quick Share peers
 * generally treat a stable salt+key tuple as part of the device's
 * identity for resume / fingerprinting purposes.
 *
 * Persisting the identity across process death is the scope of #22's
 * settings work; for now an in-memory snapshot is fine.
 */
internal object EndpointIdentityHolder {
    val snapshot: AtomicReference<EndpointInfo?> = AtomicReference(null)
}

/**
 * Builder for a [ReceiverSession]. The Android service consults this on
 * `onStartCommand` to construct a session bound to its own
 * `applicationContext`. Lifted out as an interface so a test can install
 * a fake without touching the rest of the service body.
 */
public fun interface SessionFactory {
    public fun invoke(context: Context): ReceiverSession
}

/**
 * `ProcessLifecycleOwner` observer that switches the BLE scan mode
 * between [ScanSettings.SCAN_MODE_LOW_LATENCY] (foreground) and
 * [ScanSettings.SCAN_MODE_BALANCED] (background) per the issue #35
 * acceptance criterion.
 *
 * `ProcessLifecycleOwner` aggregates every activity in the process —
 * `onStart` fires when the first activity becomes visible, `onStop`
 * fires `LIFECYCLE_DELAY_MS` (≈700 ms in current AndroidX) after the
 * last activity goes away. The 700 ms hysteresis is exactly what we
 * want: rotation, transient activity-to-activity transitions, and
 * brief pauses for system dialogs do not flap the scan mode.
 *
 * The observer is intentionally kept tiny and side-effect-free: its
 * only job is to translate two lifecycle callbacks into two scan-mode
 * change requests on the supplied [setScanMode] sink. All locking,
 * restart, and platform-call logic lives inside the
 * [BleQuickShareScanner] the sink ultimately targets — that lets unit
 * tests drive the observer with a recording sink instead of needing a
 * full scanner standing by.
 *
 * Made `internal` so the unit test in `:service-android` can exercise
 * the lifecycle transitions without instantiating the full foreground
 * service.
 *
 * @param setScanMode callback that applies a [ScanSettings] scan-mode
 *   constant to the active BLE scanner. Production wires this to
 *   [BleQuickShareScanner.setScanMode]; tests use a recorder.
 */
internal class AppLifecycleScanModeObserver(
    private val setScanMode: (Int) -> Unit,
) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        // App returned to the foreground: upgrade to LOW_LATENCY for
        // responsiveness while the user is actively interacting.
        setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
    }

    override fun onStop(owner: LifecycleOwner) {
        // App backgrounded: revert to BALANCED so the foreground
        // service can run the scan continuously without throttling and
        // without burning the battery.
        setScanMode(ScanSettings.SCAN_MODE_BALANCED)
    }
}
