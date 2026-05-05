/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:android.annotation.SuppressLint("MissingPermission")

package dev.bluehouse.libredrop.service.receiver

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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.bluehouse.libredrop.discovery.Discovery
import dev.bluehouse.libredrop.discovery.ble.BleQuickShareAdvertiser
import dev.bluehouse.libredrop.discovery.ble.BleQuickShareScanner
import dev.bluehouse.libredrop.discovery.bootstrap.BleGattInitialControlServer
import dev.bluehouse.libredrop.discovery.diagnostics.DiagnosticLog
import dev.bluehouse.libredrop.discovery.medium.MediumRegistries
import dev.bluehouse.libredrop.protocol.endpoint.BleServiceData
import dev.bluehouse.libredrop.protocol.endpoint.EndpointInfo
import dev.bluehouse.libredrop.service.downloads.DownloadsWriterFactory
import dev.bluehouse.libredrop.service.receiver.consent.ConsentBroadcastReceiver
import dev.bluehouse.libredrop.service.receiver.consent.ConsentCoordinator
import dev.bluehouse.libredrop.service.receiver.consent.ConsentDiagnostic
import dev.bluehouse.libredrop.service.receiver.consent.ConsentIntents
import dev.bluehouse.libredrop.service.receiver.consent.ConsentModalRegistry
import dev.bluehouse.libredrop.service.receiver.consent.ConsentNotification
import dev.bluehouse.libredrop.service.receiver.consent.ConsentRegistry
import dev.bluehouse.libredrop.service.receiver.foreground.AppForegroundState
import dev.bluehouse.libredrop.service.receiver.foreground.ProcessLifecycleOwnerAppForegroundState
import dev.bluehouse.libredrop.service.receiver.progress.TransferCancelRegistry
import dev.bluehouse.libredrop.service.receiver.progress.TransferProgressCoordinator
import dev.bluehouse.libredrop.service.receiver.progress.TransferProgressNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    private var progressCoordinator: TransferProgressCoordinator? = null

    @Volatile
    private var consentReceiver: BroadcastReceiver? = null

    /**
     * Process-wide foreground/background flag (#151). Lazily
     * initialised on the main thread the first time the consent
     * coordinator is wired up — `ProcessLifecycleOwner.get()` requires
     * its observers to be registered there. The same instance is
     * reused across coordinator restarts so the listener subscription
     * stays attached for the lifetime of the service process.
     */
    @Volatile
    private var appForegroundState: AppForegroundState? = null

    @Volatile
    private var bleScanner: BleQuickShareScanner? = null

    /**
     * Receiver-side BLE pulse advertiser (#121). Owned for the lifetime
     * of the foreground service alongside the scanner; lifecycle-gated
     * via [MdnsAdvertisementGate]'s [BleVisibilityBroadcaster] sink so
     * BLE advertise tracks mDNS publish/unpublish in lock-step.
     *
     * `null` when the receiver service is not running, or when BLE
     * peripheral advertising is unavailable (no `BLUETOOTH_ADVERTISE`
     * grant — see [BleQuickShareAdvertiser.start]'s graceful failure
     * path).
     */
    @Volatile
    private var bleAdvertiser: BleQuickShareAdvertiser? = null

    @Volatile
    private var mdnsGate: MdnsAdvertisementGate? = null

    @Volatile
    private var discoveryDiagnosticsJob: Job? = null

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
        // Same lifecycle invariant for the progress notification
        // channel (#46) — created upfront so the first chunk's
        // progress post lands.
        TransferProgressNotification.ensureChannel(this)
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

        // Reconcile the receiver identity on every start request so a
        // newly-saved advertised name takes effect without waiting for
        // a full process restart. If the identity changed while the
        // session was already running, refresh only the advertise
        // surfaces in place so active inbound transfers survive.
        reconcileReceiverIdentityIfNeeded()

        // Bring up the receiver session if it isn't already running.
        // Multiple startService calls are otherwise idempotent.
        if (session == null) {
            startBleScanner()
            startReceiverSession()
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

        // Construct the receiver-side BLE pulse advertiser (#121). The
        // advertiser is **not** started here — the [MdnsAdvertisementGate]
        // routes its publish/unpublish decisions to both mDNS and BLE
        // simultaneously through [BleVisibilityBroadcaster], so
        // start/stop calls happen in lock-step with the existing mDNS
        // path. Failures inside the advertiser (no permission, no
        // peripheral mode, adapter off) are swallowed and logged
        // there; the receiver continues in mDNS-only mode.
        bleAdvertiser = BleQuickShareAdvertiser(applicationContext)

        // Attach the foreground/background mode observer (#35) for both
        // the scanner and the advertiser. Symmetric tuning: BALANCED in
        // the background, LOW_LATENCY while the user has the app
        // foregrounded, so a fresh send-side picker scan sees us within
        // a few hundred milliseconds.
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
        // The observer fans the foreground/background transition out to
        // both the scanner and the advertiser. Both classes' mode
        // setters are idempotent and inline-callable; the closure holds
        // a reference to the volatile [bleAdvertiser] field so it picks
        // up `null` cleanly if the service tears down between
        // observer-attach and the next lifecycle callback.
        val observer =
            AppLifecycleScanModeObserver { scanMode ->
                scanner.setScanMode(scanMode)
                bleAdvertiser?.setAdvertiseMode(translateToAdvertiseMode(scanMode))
            }
        processLifecycleObserver = observer
        runOnMainThread {
            ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        }
    }

    /**
     * Translate a [android.bluetooth.le.ScanSettings] scan-mode constant
     * to the corresponding [android.bluetooth.le.AdvertiseSettings]
     * advertise-mode constant. Mirrors the BALANCED ↔ LOW_LATENCY pairing
     * the scanner already uses.
     *
     * The mapping is intentionally tight — only the two modes the
     * lifecycle observer toggles between are recognised. Anything else
     * falls back to BALANCED, which is the safe default for a
     * continuously-running foreground service.
     */
    private fun translateToAdvertiseMode(scanMode: Int): Int =
        when (scanMode) {
            ScanSettings.SCAN_MODE_LOW_LATENCY ->
                android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            ScanSettings.SCAN_MODE_BALANCED ->
                android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_BALANCED
            ScanSettings.SCAN_MODE_LOW_POWER ->
                android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
            else -> android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_BALANCED
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
        // Same shape for the progress coordinator (#46): subscribe
        // before the session starts so the first `Receiving` state
        // transition produces a progress notification.
        startProgressCoordinator(newSession)
        startInboundDiagnosticsLogger(newSession)
        ReceiverIdentityRotator(
            nextIdentityProvider = { AdvertisedDeviceNames.createEndpointInfo(applicationContext) },
            bleBroadcasterFactory = { buildBleBroadcaster() },
            logger = { line ->
                DiagnosticLog.e(INBOUND_DIAG_TAG, line)
                appendInboundLog(line)
            },
        ).start(serviceScope, newSession)

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
     * Refresh the canonical receiver [EndpointInfo] from the current
     * advertised-name setting and platform defaults.
     *
     * The receiver keeps its metadata blob stable across refreshes so
     * peers still see the same logical device, but a changed device
     * name requires a new endpoint id and refreshed advertise surfaces.
     */
    private fun reconcileReceiverIdentityIfNeeded() {
        val current = EndpointIdentityHolder.snapshot.get()
        val refreshed = AdvertisedDeviceNames.createEndpointInfo(applicationContext, current)
        if (current == refreshed) return

        val activeSession = session
        val nameChanged = current?.deviceName != refreshed.deviceName
        if (activeSession == null) {
            if (nameChanged) {
                BleEndpointIdHolder.clear()
            }
            EndpointIdentityHolder.snapshot.set(refreshed)
        } else {
            if (nameChanged) {
                BleEndpointIdHolder.clear()
            }
            EndpointIdentityHolder.snapshot.set(refreshed)
            val wasAdvertising = activeSession.isAdvertising
            val applied = activeSession.replaceEndpointInfo(refreshed)
            if (applied) {
                if (activeSession.isAdvertising) {
                    buildBleBroadcaster().start()
                }
            } else if (current != null) {
                if (nameChanged) {
                    BleEndpointIdHolder.clear()
                }
                EndpointIdentityHolder.snapshot.set(current)
                activeSession.replaceEndpointInfo(current)
                if (wasAdvertising) {
                    runCatching { activeSession.publishAdvertisement() }
                }
            }
        }
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
                            dev.bluehouse.libredrop.discovery.ble.ScanActivity.Idle,
                        ),
                alwaysVisibleOverride = MdnsVisibilityOverrideHolder.activeFlow,
                qrSessionActive = QrSessionActiveHolder.activeFlow,
                outboundSessionActive = OutboundSessionActiveHolder.activeFlow,
                bleBroadcaster = buildBleBroadcaster(),
            )
        gate.start(serviceScope)
        mdnsGate = gate
    }

    /**
     * Build a [BleVisibilityBroadcaster] that hands the gate's
     * publish/unpublish decisions to the [bleAdvertiser] using the
     * receiver's stable [EndpointInfo] and a process-stable 4-byte
     * endpoint_id slug. The advertiser compacts the EndpointInfo into
     * the hidden, no-name BLE shape so it fits the legacy advertisement
     * budget; the mDNS path keeps the visible, name-bearing identity.
     *
     * The endpoint_id we feed into BLE is the same process-stable slug
     * that production `Discovery` uses for the mDNS instance name.
     * Stock Nearby Connections discovery dedupes cross-medium sightings
     * by this id, so the receiver must not advertise one id over BLE
     * and another via mDNS.
     *
     * Returns [BleVisibilityBroadcaster.Noop] if [bleAdvertiser] is
     * `null` (the service is between start/stop, or no advertiser was
     * constructed in [startBleScanner] for some reason). The gate
     * tolerates a no-op broadcaster the same way it tolerates a
     * never-active BLE pulse: mDNS still publishes/unpublishes per the
     * other gating signals.
     */
    @Suppress("ReturnCount")
    private fun buildBleBroadcaster(): BleVisibilityBroadcaster {
        val advertiser = bleAdvertiser ?: return BleVisibilityBroadcaster.Noop
        return object : BleVisibilityBroadcaster {
            override fun start(): Boolean {
                val endpointInfo =
                    EndpointIdentityHolder.snapshot.get() ?: return false
                val endpointId = BleEndpointIdHolder.bytesFor()
                // BleQuickShareAdvertiser.start re-uses the existing
                // platform registration when the identity is unchanged,
                // so re-issuing start() while already advertising is
                // effectively a no-op.
                return advertiser.start(endpointInfo, endpointId)
            }

            override fun stop() {
                advertiser.stop()
            }
        }
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
        if (discoveryDiagnosticsJob != null) return
        discoveryDiagnosticsJob =
            serviceScope.launch {
                while (isActive) {
                    val discovery = ActiveDiscoveryHolder.current()
                    if (discovery != null) {
                        val snap = discovery.snapshot()
                        DiagnosticLog.i(
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

    private fun startInboundDiagnosticsLogger(session: ReceiverSession) {
        appendInboundLog("session start: pid=${android.os.Process.myPid()}")
        serviceScope.launch {
            session.completions.collect { completion ->
                val line =
                    "completion id=${completion.connectionId} " +
                        "ref=${System.identityHashCode(completion.connection)} " +
                        "result=${completion.result}"
                DiagnosticLog.e(INBOUND_DIAG_TAG, line)
                appendInboundLog(line)
            }
        }
        serviceScope.launch {
            session.activeConnections.collect { conn ->
                val ref = System.identityHashCode(conn)
                val accepted = "accepted ref=$ref"
                DiagnosticLog.e(INBOUND_DIAG_TAG, accepted)
                appendInboundLog(accepted)
                serviceScope.launch {
                    conn.state.collect { st ->
                        val line = "state ref=$ref -> $st"
                        DiagnosticLog.e(INBOUND_DIAG_TAG, line)
                        appendInboundLog(line)
                    }
                }
            }
        }
    }

    private fun appendInboundLog(line: String) {
        runCatching {
            val dir = getExternalFilesDir(null) ?: return
            val f = java.io.File(dir, "libredrop-inbound.log")
            f.appendText("${System.currentTimeMillis()} $line\n")
        }
    }

    /**
     * Wire a [ConsentCoordinator] over the session's flows so that
     * each `WaitingForUserConsent` transition is surfaced through the
     * appropriate UI: an in-app modal when LibreDrop is foregrounded
     * (#151) or a heads-up notification when it isn't.
     */
    private fun startConsentCoordinator(session: ReceiverSession) {
        val ctx = applicationContext
        val foregroundState = obtainAppForegroundState()
        val coordinator =
            ConsentCoordinator(
                activeConnections = session.activeConnections,
                results = session.completions,
                registry = ConsentRegistry.instance,
                diagnostic = { line -> ConsentDiagnostic.log(ctx, line) },
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

                        override fun launchModal(
                            connectionId: Long,
                            entry: ConsentRegistry.Entry,
                        ) {
                            launchConsentTrampolineAsModal(connectionId)
                        }

                        override fun dismissModal(connectionId: Long) {
                            ConsentModalRegistry.instance.dismiss(connectionId)
                        }
                    },
                scope = serviceScope,
                appForegroundState = foregroundState,
            )
        coordinator.start()
        consentCoordinator = coordinator
    }

    /**
     * Lazily construct the [ProcessLifecycleOwnerAppForegroundState]
     * adapter on the main thread. Reused across coordinator restarts
     * so the listener subscription stays attached even if a session is
     * recycled — the lifecycle-process API does not support detaching
     * an observer cleanly without the underlying [LifecycleOwner]
     * cooperating, and the adapter is cheap to keep alive.
     */
    private fun obtainAppForegroundState(): AppForegroundState {
        appForegroundState?.let { return it }
        val state = AtomicReference<AppForegroundState>()
        // Construct on the main thread per ProcessLifecycleOwner's
        // contract. `Service.onStartCommand` already runs on the main
        // thread so this is normally inline; the marshalling exists
        // for any future call site that might run on a different
        // thread.
        val latch = java.util.concurrent.CountDownLatch(1)
        runOnMainThread {
            state.set(ProcessLifecycleOwnerAppForegroundState())
            latch.countDown()
        }
        latch.await()
        val created = state.get()
        appForegroundState = created
        return created
    }

    /**
     * Launch the consent trampoline activity (#22) as the foreground
     * modal surface (#151). No-op when the host application has not
     * registered a trampoline class — same fallback the notification
     * path uses, so the user still gets a usable consent surface
     * (heads-up notification) when only the activity launch is
     * unavailable.
     */
    private fun launchConsentTrampolineAsModal(connectionId: Long) {
        val target = consentTrampolineTarget
        if (target == null) {
            ConsentDiagnostic.log(this, "service.launchModal id=$connectionId target=null skip=true")
            return
        }
        ConsentDiagnostic.log(this, "service.launchModal id=$connectionId target=${target.simpleName}")
        val intent =
            Intent(this, target).apply {
                action = ConsentIntents.ACTION_SHOW_CONSENT
                putExtra(ConsentIntents.EXTRA_CONNECTION_ID, connectionId)
                // FLAG_ACTIVITY_NEW_TASK is mandatory when launching
                // an Activity from a non-Activity Context (the
                // service). FLAG_ACTIVITY_REORDER_TO_FRONT brings an
                // existing trampoline instance to the top of its task
                // (in combination with launchMode="singleTop") rather
                // than creating a duplicate. SINGLE_TOP routes the new
                // intent through onNewIntent on the existing activity
                // when one is already alive.
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }
        try {
            startActivity(intent)
            ConsentDiagnostic.log(this, "service.launchModal id=$connectionId startActivity=ok")
        } catch (e: SecurityException) {
            // Vendor builds occasionally restrict background-activity
            // launches even from foreground services. Fall through
            // silently — the heads-up notification path is the
            // documented fallback for exactly this case.
            DiagnosticLog.w(MODAL_TAG, "Could not launch consent trampoline as modal: ${e.message}", e)
            ConsentDiagnostic.log(
                this,
                "service.launchModal id=$connectionId startActivity=SecurityException msg=${e.message}",
            )
        }
    }

    /**
     * Wire a [TransferProgressCoordinator] over the session's flows
     * so that each `Receiving` transition is surfaced as a progress
     * notification (#46), and so the Cancel action on that
     * notification routes back to the live `InboundConnection` via
     * [TransferCancelRegistry].
     */
    private fun startProgressCoordinator(session: ReceiverSession) {
        val ctx = applicationContext
        val coordinator =
            TransferProgressCoordinator(
                activeConnections = session.activeConnections,
                results = session.completions,
                sink =
                    object : TransferProgressCoordinator.Sink {
                        override fun postProgress(
                            connectionId: Long,
                            sourceDeviceName: String?,
                            progress: dev.bluehouse.libredrop.protocol.connection.TransferProgress,
                        ) {
                            TransferProgressNotification.post(
                                context = ctx,
                                connectionId = connectionId,
                                sourceDeviceName = sourceDeviceName,
                                progress = progress,
                            )
                        }

                        override fun dismissProgress(connectionId: Long) {
                            TransferProgressNotification.dismiss(ctx, connectionId)
                        }

                        override fun registerCancel(
                            connectionId: Long,
                            onCancel: () -> Unit,
                        ) {
                            TransferCancelRegistry.instance.register(connectionId, onCancel)
                        }

                        override fun unregisterCancel(connectionId: Long) {
                            TransferCancelRegistry.instance.unregister(connectionId)
                        }
                    },
                scope = serviceScope,
            )
        coordinator.start()
        progressCoordinator = coordinator
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

    private fun stopActiveReceiverSession() {
        consentCoordinator?.stop()
        consentCoordinator = null
        progressCoordinator?.stop()
        progressCoordinator = null
        // Dismiss every pending consent notification we know about.
        // The connection itself will be torn down when the session
        // stops; without explicit dismissal the heads-up would linger
        // pointing at a dead connection.
        val ctx = applicationContext
        ConsentRegistry.instance.snapshotIds().forEach { id ->
            ConsentRegistry.instance.unregister(id)
            ConsentNotification.dismiss(ctx, id)
        }
        // Same hygiene for any in-app modal trampoline activities that
        // are still alive (#151). Calling dismiss invokes their finish
        // hook so the user is not left staring at a modal whose
        // backing connection has been torn down.
        ConsentModalRegistry.instance.snapshotIds().forEach { id ->
            ConsentModalRegistry.instance.dismiss(id)
        }
        // Same hygiene for the in-flight progress notifications
        // posted by #46 — a dangling progress card pointing at a
        // dead connection would invite the user to tap a Cancel
        // action that lands in /dev/null.
        TransferCancelRegistry.instance.snapshotIds().forEach { id ->
            TransferCancelRegistry.instance.unregister(id)
            TransferProgressNotification.dismiss(ctx, id)
        }

        // Stop the gate before the session so its delayed unpublish
        // coroutine can no longer fire after `session.stop()` has
        // already torn the AdvertiseHandle down.
        mdnsGate?.stop()
        mdnsGate = null

        session?.stop()
        session = null
        ActiveDiscoveryHolder.clear()
        discoveryDiagnosticsJob?.cancel()
        discoveryDiagnosticsJob = null

        // Stop the receiver-side BLE pulse advertiser after the mDNS
        // gate and session are down so a stale registration never
        // outlives the old identity while a new session is starting.
        bleAdvertiser?.stop()
    }

    private fun stopReceiverAndExit() {
        stopActiveReceiverSession()
        unregisterConsentReceiverIfNeeded()

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
        bleAdvertiser = null

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
        public const val ACTION_STOP: String = "dev.bluehouse.libredrop.service.receiver.ACTION_STOP"

        /**
         * Intent extra carrying a serialized [EndpointInfo] when the
         * caller wants to override the service's identity (e.g. tests
         * or a future "rename device" feature). Optional — when absent
         * the service falls back to a process-default identity.
         */
        public const val EXTRA_ENDPOINT_INFO: String = "libredrop.endpoint_info"

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
                val identity =
                    EndpointIdentityHolder.snapshot.get()
                        ?: AdvertisedDeviceNames.createEndpointInfo(context).also {
                            EndpointIdentityHolder.snapshot.compareAndSet(null, it)
                        }
                // Keep one Discovery per session so the periodic
                // diagnostic snapshot in [startReceiverSession] reflects
                // the same instance the advertise lambda is using.
                val discovery =
                    Discovery(
                        context = context,
                        instanceEndpointIdProvider = { BleEndpointIdHolder.bytesFor() },
                    )
                ActiveDiscoveryHolder.set(discovery)
                ReceiverSession(
                    tcpServerFactory =
                        TcpServerFactory.default(
                            logger = { line -> logInboundDiagnostic(context.applicationContext, line) },
                        ),
                    advertiser =
                        DiscoveryAdvertiser { endpointInfo, port ->
                            discovery.advertise(endpointInfo, port)
                        },
                    factoryProvider = { DownloadsWriterFactory.create(context) },
                    endpointInfo = identity,
                    mediumRegistry = MediumRegistries.defaultForContext(context.applicationContext),
                    initialControlServers =
                        listOf(
                            // Keep the production receiver bootstrap GATT-only for now.
                            // Advertising an LE CoC PSM makes One UI prefer L2CAP over
                            // the verified GATT socket path and fail before protocol
                            // consent reaches the app.
                            BleGattInitialControlServer(
                                context = context.applicationContext,
                                endpointIdProvider = { BleEndpointIdHolder.bytesFor() },
                            ),
                        ),
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

        private fun logInboundDiagnostic(
            context: Context,
            line: String,
        ) {
            DiagnosticLog.e(INBOUND_DIAG_TAG, line)
            runCatching {
                val dir = context.getExternalFilesDir(null) ?: return
                val file = java.io.File(dir, "libredrop-inbound.log")
                file.appendText("${System.currentTimeMillis()} $line\n")
            }
        }

        /**
         * Cadence for the discovery diagnostics logger spawned in
         * [startDiscoveryDiagnosticsLogger]. 10 s strikes a balance
         * between catching the silent-failure window from issue #83
         * (peers should be visible within a few seconds) and not
         * flooding logcat in steady state.
         */
        private const val DIAGNOSTICS_LOG_INTERVAL_MS: Long = 10_000L

        /** logcat tag for the diagnostics line — matches the discovery module. */
        private const val DIAGNOSTICS_TAG: String = "LibreDropDiscovery"
        private const val INBOUND_DIAG_TAG: String = "LibreDropInbound"

        /** logcat tag for the foreground-modal launch path (#151). */
        private const val MODAL_TAG: String = "LibreDropConsentModal"
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
 * Process-wide flag for "an outbound send is currently in progress".
 *
 * Flipped on by `SendActivity.onCreate` and off by `onDestroy`. When set,
 * [MdnsAdvertisementGate] vetoes its publish decision and tears the
 * receiver-side mDNS record down for the duration of the send.
 *
 * **Why veto?** Empirical observation against Samsung Galaxy S24 Ultra
 * (One UI 8.0.5 / Android 16): when LibreDrop concurrently publishes its
 * receiver-side mDNS record AND opens an outbound `OutboundConnection`
 * to the same Galaxy peer, Samsung's GMS Nearby (`NearbyConnections`)
 * caches state for our endpoint from the WIFI_LAN service it
 * discovered, then on the incoming TCP connect — same source IP,
 * different endpoint id, sender role — Samsung's
 * `securegcm::UKey2Handshake::ParseHandshakeMessage` fails on
 * `client_finished` (verified ~73-267 ms after Samsung writes
 * `server_init`, far below the 15-second `CancelableAlarm` timeout).
 * Force-stopping the receiver service before a send unblocks UKEY2
 * cleanly (verified end-to-end on the same hotspot setup); pausing the
 * gate is the same effect, scoped automatically to the send.
 *
 * The gate's existing 30-second idle debounce remains in effect on the
 * resume side: when `SendActivity` finishes and the flag flips off, the
 * gate re-evaluates and re-publishes mDNS if any of the existing
 * `bleActive` / `overrideOn` / `qrActive` signals call for it.
 */
public object OutboundSessionActiveHolder {
    private val state: kotlinx.coroutines.flow.MutableStateFlow<Boolean> =
        kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Hot flow signalling whether an outbound send is in progress. */
    public val activeFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = state

    /** Current snapshot of [activeFlow]. */
    public val isActive: Boolean
        get() = state.value

    /**
     * Set the outbound-session flag. The host activity flips this on
     * when [dev.bluehouse.libredrop.send.SendActivity] enters the
     * foreground and off when it leaves.
     */
    public fun setOutboundSessionActive(active: Boolean) {
        state.value = active
    }
}

/**
 * Process-wide mirror of the receiver mDNS advertisement state.
 *
 * `SendActivity` uses this as a small synchronization point for vivo /
 * Funtouch / OriginOS devices whose `NsdManager` can wedge a browse
 * listener when the same process starts browsing the Quick Share service
 * type before its own receiver-side publish has fully torn down.
 */
public object ReceiverAdvertisementStateHolder {
    private val state: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** Hot flow signalling whether the receiver mDNS record is active. */
    public val advertisingFlow: StateFlow<Boolean> = state

    /** Current snapshot of [advertisingFlow]. */
    public val isAdvertising: Boolean
        get() = state.value

    /**
     * Wait until receiver mDNS is no longer advertised, bounded so a
     * stale state signal cannot block the sender picker indefinitely.
     */
    public suspend fun awaitNotAdvertising(
        timeoutMillis: Long = DEFAULT_AWAIT_NOT_ADVERTISING_TIMEOUT_MILLIS,
    ): Boolean {
        if (!state.value) return true
        return withTimeoutOrNull(timeoutMillis) {
            advertisingFlow
                .filter { advertising -> !advertising }
                .first()
        } != null
    }

    internal fun setAdvertising(active: Boolean) {
        state.value = active
    }

    public const val DEFAULT_AWAIT_NOT_ADVERTISING_TIMEOUT_MILLIS: Long = 2_000L
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
    private var ref: dev.bluehouse.libredrop.discovery.Discovery? = null

    fun set(discovery: dev.bluehouse.libredrop.discovery.Discovery) {
        ref = discovery
    }

    fun current(): dev.bluehouse.libredrop.discovery.Discovery? = ref

    fun clear() {
        ref = null
    }
}

/**
 * Process-singleton holder for the receiver's current 4-byte ASCII
 * endpoint_id slug used by the BLE pulse advertiser (#121).
 *
 * The same slug is the natural primary key Quick Share peers use to
 * dedupe sightings of a device across BLE and mDNS. We cache it here so
 * every active advertise surface in the process uses the same value, then
 * rotate it after an inbound transfer reaches a terminal state so stock
 * senders do not reuse stale Wi-Fi Direct handoff state on the next tap.
 *
 * Production `Discovery` is configured to reuse this slug inside every
 * mDNS instance name, so BLE fast advertisements and mDNS service
 * records identify the same endpoint.
 */
internal object BleEndpointIdHolder {
    /** The cached 4-byte slug shared by BLE, GATT bootstrap, and mDNS. */
    private val cached: AtomicReference<ByteArray?> = AtomicReference(null)

    fun bytesFor(): ByteArray {
        val existing = staged.get() ?: cached.get()
        if (existing != null) return existing.copyOf()
        val generated = generate()
        val selected =
            if (cached.compareAndSet(null, generated.copyOf())) {
                generated
            } else {
                cached.get()!!
            }
        return selected.copyOf()
    }

    fun snapshot(): ByteArray? = cached.get()?.copyOf()

    fun newCandidate(): ByteArray = generate()

    fun commit(endpointId: ByteArray) {
        cached.set(endpointId.copyOf())
    }

    fun rotate(): ByteArray {
        val generated = newCandidate()
        commit(generated)
        return generated
    }

    fun restore(endpointId: ByteArray?) {
        cached.set(endpointId?.copyOf())
    }

    fun applyCandidate(
        endpointId: ByteArray,
        block: () -> Boolean,
    ): Boolean {
        val previous = staged.getAndSet(endpointId.copyOf())
        return try {
            val applied = block()
            if (applied) {
                commit(endpointId)
            }
            applied
        } finally {
            staged.set(previous)
        }
    }

    private fun generate(): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = java.security.SecureRandom()
        return ByteArray(BleServiceData.ENDPOINT_ID_LEN) {
            alphabet[random.nextInt(alphabet.length)].code.toByte()
        }
    }

    fun clear() {
        cached.set(null)
        staged.set(null)
    }

    private val staged: AtomicReference<ByteArray?> = AtomicReference(null)
}

internal fun ByteArray.toAsciiLabel(): String = String(this, Charsets.US_ASCII)

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
