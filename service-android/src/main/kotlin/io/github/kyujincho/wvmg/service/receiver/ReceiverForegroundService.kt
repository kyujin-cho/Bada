/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.receiver

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import io.github.kyujincho.wvmg.discovery.Discovery
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import io.github.kyujincho.wvmg.service.downloads.DownloadsWriterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * `MediaStoreDownloadsFactory`, real `Discovery`, and a real multicast
 * lock against the application context. The override is process-wide
 * but only consulted on `onCreate`; a unit test can install a fake,
 * spawn a service via Robolectric, and observe the resulting
 * [ReceiverSession] interactions without touching real Android
 * subsystems. Phase 1 keeps the override path purely as a seam — the
 * `ReceiverSession` itself is exhaustively unit-tested on plain JVM
 * (see [ReceiverSession]) so the service body remains the only
 * Android-coupled surface.
 */
public class ReceiverForegroundService : Service() {
    private val serviceJob: Job = SupervisorJob()
    private val serviceScope: CoroutineScope = CoroutineScope(serviceJob + Dispatchers.IO)

    @Volatile
    private var session: ReceiverSession? = null

    override fun onCreate() {
        super.onCreate()
        ReceiverNotification.ensureChannel(this)
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
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Tear the receiver down before the scope is cancelled so the
        // JmDNS goodbye packet and TCP listener close get a chance to
        // run on the IO dispatcher.
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
        serviceScope.launch {
            try {
                newSession.start()
            } catch (
                @Suppress("SwallowedException") t: Throwable,
            ) {
                // Setup failed — bring the service down so the user
                // notification doesn't claim we're listening when we
                // aren't. The error path is intentionally quiet here;
                // surfacing the throwable to the UI is the
                // consent-screen / error-toast story tracked in #22,
                // which will subscribe to a richer error flow added by
                // that PR. Until then we deliberately swallow the
                // throwable: the alternative (logging via android.util.Log
                // here) drags an Android dependency into the otherwise
                // pure-coordination path and pre-empts the design space
                // for #22.
                stopReceiverAndExit()
            }
        }
    }

    private fun stopReceiverAndExit() {
        session?.stop()
        session = null
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
         * real `Discovery` + `DownloadsWriterFactory` + multicast lock.
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
                ReceiverSession(
                    tcpServerFactory = TcpServerFactory.default(),
                    advertiser =
                        DiscoveryAdvertiser { endpointInfo, port ->
                            Discovery(context).advertise(endpointInfo, port)
                        },
                    multicastLock = AndroidMulticastLockController(context),
                    factoryProvider = { DownloadsWriterFactory.create(context) },
                    endpointInfo = identity,
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
         * The device name defaults to the application label (typically
         * "Quick Share" for this build), capped at
         * [MAX_DEFAULT_NAME_BYTES] so it always fits the 255-byte
         * single-byte length field on the wire. Visibility is set to
         * "visible" — the device is discoverable by all peers on the
         * LAN. A user-facing settings UI for changing the device name
         * and toggling hidden / contacts-only visibility is the
         * responsibility of #22; for now this default is the production
         * identity.
         *
         * Random salt + encrypted-metadata-key bytes are indistinguishable
         * to peers from real GMS-issued ones for the GMS-free use case
         * targeted by this project.
         */
        private fun defaultEndpointInfo(context: Context): EndpointInfo {
            val name =
                context.applicationInfo
                    .loadLabel(context.packageManager)
                    .toString()
                    .ifBlank { DEFAULT_DEVICE_NAME }
                    .take(MAX_DEFAULT_NAME_BYTES)
            return EndpointInfo(
                version = 1,
                hidden = false,
                deviceType =
                    io.github.kyujincho.wvmg.protocol.endpoint.DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN).also { java.security.SecureRandom().nextBytes(it) },
                deviceName = name,
                tlvRecords = emptyList(),
            )
        }

        private const val DEFAULT_DEVICE_NAME = "Quick Share"

        // 64 bytes leaves comfortable headroom under the 255-byte
        // single-byte length limit and is more than enough for typical
        // app-label names ("Quick Share" is 11).
        private const val MAX_DEFAULT_NAME_BYTES = 64
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
 * Production [MulticastLockController] that holds a single Wi-Fi
 * multicast lock for the duration of the service. We tag the lock with a
 * service-specific name so it shows up correctly in `dumpsys wifi` for
 * diagnostics.
 */
internal class AndroidMulticastLockController(
    context: Context,
    tag: String = DEFAULT_TAG,
) : MulticastLockController {
    private val wifi: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val lock: WifiManager.MulticastLock =
        wifi.createMulticastLock(tag).apply {
            // The receiver service holds one lock for its entire
            // lifetime; we don't need the WifiManager's reference
            // counting on top of our own boolean tracking in
            // ReceiverSession.
            setReferenceCounted(false)
        }

    override fun acquire() {
        if (!lock.isHeld) lock.acquire()
    }

    override fun release() {
        if (lock.isHeld) lock.release()
    }

    private companion object {
        const val DEFAULT_TAG = "wvmg-receiver-foreground"
    }
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
