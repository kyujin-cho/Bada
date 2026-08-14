/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver

import dev.bluehouse.bada.discovery.ble.ScanActivity
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives the mDNS advertisement lifecycle from BLE pulse activity and
 * a small set of override flags (issue #34).
 *
 * Stock Quick Share does not broadcast mDNS continuously — only while a
 * sender's BLE pulse is detected. This gate matches that behaviour by
 * subscribing to:
 *
 *  * [bleActivity] — `StateFlow<ScanActivity>` from
 *    `BleQuickShareScanner` (#33). When the most recent value is
 *    [ScanActivity.Active] we want to publish.
 *  * [alwaysVisibleOverride] — sticky user-controlled "always visible"
 *    toggle. When `true`, we publish unconditionally so the receiver
 *    works on devices where BLE scan is unavailable (no permission, no
 *    LE hardware) or where the user simply wants to be discoverable
 *    while the BLE side is asleep.
 *  * [qrSessionActive] — when a QR-code-based receive flow is in
 *    progress (the in-app counterpart to #1.16, hooked into the
 *    existing #84/#86 QR display path), the gate is bypassed entirely
 *    and the advertisement stays up. The current build only exposes
 *    the hook; the QR flow itself toggles it when the relevant code
 *    lands.
 *
 * ### Debounce
 *
 * After mDNS goes up, it stays up for at least
 * [debounceIdleMillis] (default 30 s) to avoid flapping if the BLE
 * pulse is intermittent. Concretely:
 *
 *  * BLE flips Idle → Active: publish immediately, cancel any pending
 *    unpublish.
 *  * BLE flips Active → Idle: schedule an unpublish [debounceIdleMillis]
 *    later.
 *  * BLE flips back to Active before the timer fires: cancel the timer,
 *    keep the advertisement up.
 *  * Override flips on: publish immediately, cancel any pending
 *    unpublish.
 *  * Override flips off (and BLE is Idle): schedule an unpublish
 *    [debounceIdleMillis] later.
 *
 * The debounce window is chosen to match #34's acceptance criterion
 * verbatim.
 *
 * ### Concurrency
 *
 * The gate runs on a single coroutine launched in [scope]. All the
 * publish/unpublish state is owned by that coroutine — no locks are
 * needed beyond the ones [ReceiverSession] already holds internally.
 *
 * The gate does **not** own the publish lifecycle past its own scope:
 * if the receiver service stops, [stop] cancels the gate but leaves the
 * session to run its own teardown order (which closes any in-flight
 * AdvertiseHandle as part of its `stop()` path). This keeps the gate's
 * stop-order trivially safe regardless of which side cancels first.
 *
 * @param session the receiver session whose advertisement is gated.
 * @param bleActivity scanner activity flow sourced from
 *   `BleQuickShareScanner.activity` via [ActiveBleScannerHolder].
 * @param alwaysVisibleOverride sticky user-controlled override.
 * @param qrSessionActive QR-code-flow bypass flag.
 * @param outboundSessionActive when `true`, vetoes any publish decision
 *   and tears the mDNS record down. Used by `SendActivity` to prevent
 *   the receiver-side mDNS publish from racing with our outbound
 *   connection — Samsung One UI 8.0.5's GMS Nearby caches state for
 *   our endpoint from the discovered WIFI_LAN service and then fails
 *   `securegcm::UKey2Handshake::ParseHandshakeMessage` on the incoming
 *   `client_finished` from the same IP. See
 *   [OutboundSessionActiveHolder] for the full rationale.
 * @param debounceIdleMillis time after the last "should publish" signal
 *   before the gate unpublishes the advertisement. 30 s by default per
 *   issue #34.
 * @param publishRetryDelayMillis time after a failed mDNS publish before
 *   retrying while any publish signal remains active. This covers radio
 *   recovery cases where the first publish attempt happens while Wi-Fi
 *   or Bluetooth is still unavailable, and Android does not emit a gate
 *   input change after the radio becomes usable.
 * @param wallClockMillis wall-clock source for the failure-log throttle.
 *   Injectable so JVM tests can drive the throttle window
 *   deterministically; production uses [System.currentTimeMillis].
 *
 * ### Diagnostics hygiene (issue #262)
 *
 * `BleQuickShareScanner` republishes `ScanActivity.Active(lastSeenAtMillis)`
 * on every sighting of a sender's BLE pulse — each carries a fresh
 * timestamp, so `StateFlow`'s equality conflation never collapses them and
 * [apply] re-runs several times per second while a peer session is active.
 * The gate therefore must not treat "apply ran" as a loggable event:
 *
 *  * [startBleSafely] logs only when the (decision, outcome) pair changes
 *    since the last BLE start log (reset when the BLE advertise stops), so
 *    steady-state pulse traffic produces a single line per transition.
 *  * Publish-path failures are logged as a single summary line (exception
 *    class + message chain, no stack trace) and throttled to at most one
 *    line per [FAILURE_LOG_THROTTLE_MILLIS] per (site, exception class):
 *    an NsdManager registration stall on vivo otherwise floods the 512 KB
 *    [DiagnosticLog] rotation with identical `TimeoutCancellationException`
 *    stacks — and that file is the only field evidence on those devices.
 *  * A failed mDNS publish is NOT re-attempted by every pulse-driven
 *    [apply]: while the scheduled retry is pending and the decision is
 *    unchanged, the (2-second-blocking) `NsdManager.registerService` call
 *    is skipped so the retry cadence stays at [publishRetryDelayMillis].
 */
@Suppress("LongParameterList")
public class MdnsAdvertisementGate(
    private val session: ReceiverSession,
    private val bleActivity: StateFlow<ScanActivity>,
    private val alwaysVisibleOverride: StateFlow<Boolean>,
    private val qrSessionActive: StateFlow<Boolean>,
    private val outboundSessionActive: StateFlow<Boolean> = MutableStateFlow(false),
    private val debounceIdleMillis: Long = DEFAULT_DEBOUNCE_IDLE_MILLIS,
    private val publishRetryDelayMillis: Long = DEFAULT_PUBLISH_RETRY_DELAY_MILLIS,
    /**
     * Optional sink for the receiver-side BLE pulse advertiser (#121).
     *
     * When supplied, the gate calls [BleVisibilityBroadcaster.start] /
     * [BleVisibilityBroadcaster.stop] in lock-step with the mDNS
     * publish/unpublish decisions. Same debounce window, same outbound
     * veto — BLE and mDNS advertise (and unpublish) symmetrically so
     * peers see consistent presence across both channels.
     *
     * Defaults to a no-op [BleVisibilityBroadcaster.Noop]; tests and
     * the production wiring inject a real implementation backed by
     * `BleQuickShareAdvertiser`. Wiring it through the gate (rather
     * than spawning a parallel observer) keeps the publish/unpublish
     * decisions trivially in sync — there is one decision, applied to
     * both sinks.
     */
    private val bleBroadcaster: BleVisibilityBroadcaster = BleVisibilityBroadcaster.Noop,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var collectorJob: Job? = null

    @Volatile
    private var debounceJob: Job? = null

    @Volatile
    private var publishRetryJob: Job? = null

    @Volatile
    private var bleAdvertising: Boolean = false

    /**
     * Serialises [apply] across the three child collectors so a racing
     * BLE/override/QR transition cannot interleave the
     * `session.isAdvertising` check with the publish/schedule write.
     * Synchronous and held only over fast in-memory state transitions
     * — the actual `session.publishAdvertisement` / `unpublish` calls
     * are themselves serialised by the session's internal lock.
     */
    private val applyLock: Any = Any()

    /**
     * Guards the pure log-dedupe/throttle state below. Kept separate from
     * [applyLock] because [stopBleSafely] also runs from the debounce
     * coroutine outside the apply path, and the log bookkeeping must not
     * change the existing locking semantics of the publish machinery.
     */
    private val logStateLock: Any = Any()

    /**
     * Last (decision, started) pair logged by [startBleSafely]. Consecutive
     * identical outcomes are not re-logged; reset by [stopBleSafely] so the
     * next start after a stop logs the transition again. Guarded by
     * [logStateLock].
     */
    private var lastBleStartLog: Pair<Decision, Boolean>? = null

    /**
     * Wall-clock time of the last emitted throttled warning, keyed by
     * `"<site>:<exception class>"`. Guarded by [logStateLock].
     */
    private val throttledWarningLastEmitMillis: MutableMap<String, Long> = HashMap()

    /**
     * Decision snapshot of the most recent failed publish attempt whose
     * retry is still pending. While set (and the retry job is active),
     * pulse-driven re-applies of the same decision skip the blocking
     * `NsdManager.registerService` re-attempt. Guarded by [applyLock].
     */
    private var pendingRetryDecision: Decision? = null

    /**
     * Begin observing the gating signals on [scope].
     *
     * Idempotent: a second call while the gate is already running is a
     * no-op. Cancelling [scope] (or calling [stop]) tears down the
     * subscription; the receiver session is unaffected.
     */
    public fun start(scope: CoroutineScope) {
        if (collectorJob != null) return
        // Re-evaluate the decision whenever any of the three input flows
        // changes. We collect each flow in its own child coroutine so
        // the suspending collector doesn't interleave with the
        // pure-side-effect [apply] body — every state change just
        // recomputes from the current StateFlow snapshots.
        //
        // We deliberately do NOT use [combine] here: in unit tests under
        // `runTest`, combine's per-source debouncing behaviour interacts
        // poorly with `StandardTestDispatcher`'s scheduler — the first
        // emission can be delivered late enough to skip an initial
        // "publish on already-active state" decision. Three independent
        // collectors plus a recomputed snapshot keep the dispatch order
        // explicit and let virtual-time tests reason about it
        // deterministically.
        collectorJob =
            scope.launch {
                // Force an initial evaluation so the gate's
                // "publish if currently active" decision applies even
                // before any of the upstream flows emit a *change*.
                apply(currentDecision(), scope)
                launch {
                    bleActivity.collect { apply(currentDecision(), scope) }
                }
                launch {
                    alwaysVisibleOverride.collect { apply(currentDecision(), scope) }
                }
                launch {
                    qrSessionActive.collect { apply(currentDecision(), scope) }
                }
                launch {
                    outboundSessionActive.collect { apply(currentDecision(), scope) }
                }
            }
    }

    private fun currentDecision(): Decision =
        Decision(
            bleActive = bleActivity.value is ScanActivity.Active,
            overrideOn = alwaysVisibleOverride.value,
            qrActive = qrSessionActive.value,
            outboundActive = outboundSessionActive.value,
        )

    /**
     * Cancel the gate's coroutines. Idempotent. Does not unpublish — if
     * an advertisement is in flight at stop time it remains up until
     * [ReceiverSession.stop] tears it down. (Stopping the gate is not a
     * user-visible signal; tearing the receiver down is.)
     */
    public fun stop() {
        debounceJob?.cancel()
        debounceJob = null
        publishRetryJob?.cancel()
        publishRetryJob = null
        collectorJob?.cancel()
        collectorJob = null
    }

    /**
     * Apply a [Decision] to the receiver session. Pure side-effect: the
     * decision either publishes (synchronously, via the session) or
     * schedules an unpublish on the supplied [scope].
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private fun apply(
        decision: Decision,
        scope: CoroutineScope,
    ) = synchronized(applyLock) {
        // An active outbound send unconditionally vetoes publishing,
        // regardless of any other "should publish" signal — and tears
        // down immediately, bypassing the 30-second debounce window.
        // The race window with Samsung's UKEY2 server is on the order of
        // milliseconds; honouring the BLE-pulse debounce here would
        // leave the receiver-side mDNS advertised throughout the entire
        // outbound session and re-trigger Samsung's
        // `securegcm::UKey2Handshake::ParseHandshakeMessage` failure on
        // every connect.
        if (decision.outboundActive) {
            debounceJob?.cancel()
            debounceJob = null
            publishRetryJob?.cancel()
            publishRetryJob = null
            pendingRetryDecision = null
            if (session.isAdvertising) {
                try {
                    session.unpublishAdvertisement()
                    DiagnosticLog.w(TAG, "unpublish: outbound send active; mDNS taken down immediately")
                } catch (t: Throwable) {
                    DiagnosticLog.w(TAG, "unpublish: threw during outbound veto", t)
                }
            }
            stopBleSafely(reason = "outbound send active")
            return
        }
        val shouldPublish =
            decision.bleActive || decision.overrideOn || decision.qrActive
        if (shouldPublish) {
            // Cancel any pending unpublish — we're back inside the
            // "should publish" half of the gate.
            debounceJob?.cancel()
            debounceJob = null
            // Bring up initial-control listeners before the BLE pulse so
            // the advertised service data can include dynamic listener
            // metadata such as an LE CoC PSM.
            try {
                session.prepareInitialControlServers()
            } catch (t: Throwable) {
                logThrottledWarning(
                    site = "prepare-initial-control",
                    message = "publish: failed to prepare initial control (decision=$decision)",
                    cause = t,
                )
            }
            // Bring up BLE before the potentially slow mDNS path. Some
            // vivo builds stall inside NsdManager registration; the
            // receiver still needs to be BLE connectible while that
            // platform callback is pending.
            startBleSafely(decision)
            attemptPublishLocked(decision, scope)
            return
        }

        publishRetryJob?.cancel()
        publishRetryJob = null
        pendingRetryDecision = null
        // No "should publish" signal active: schedule an unpublish if
        // the advertisement is currently up. If the timer is already
        // running we leave it alone — re-arming it would extend the
        // debounce window unnecessarily. The two early returns plus
        // the returning `if (shouldPublish)` block above push the
        // function past detekt's default of 2; suppressed inline since
        // the alternative (nested if/else) would be harder to read.
        if (!session.isAdvertising) {
            stopBleSafely(reason = "idle with no mDNS advertisement")
            return
        }
        if (debounceJob?.isActive == true) return
        debounceJob =
            scope.launch {
                delay(debounceIdleMillis)
                if (isActive) {
                    try {
                        session.unpublishAdvertisement()
                        DiagnosticLog.w(TAG, "unpublish: idle for ${debounceIdleMillis}ms; mDNS taken down")
                    } catch (t: Throwable) {
                        // Best-effort: the session may have been stopped
                        // first, in which case the advertisement is
                        // already torn down.
                        DiagnosticLog.w(TAG, "unpublish: threw", t)
                    }
                    // Symmetric BLE teardown — same debounce window so
                    // the BLE advertiser does not flap on intermittent
                    // pulses, and so peers stop seeing us on both
                    // channels at the same wall-clock moment.
                    stopBleSafely(reason = "idle for ${debounceIdleMillis}ms")
                }
            }
    }

    /**
     * Publish the mDNS advertisement if it is not already up. Called
     * under [applyLock] from [apply].
     *
     * A failed attempt schedules a retry [publishRetryDelayMillis] later
     * and records the failing [decision]; while that retry is pending,
     * re-applies of the *same* decision (which arrive several times per
     * second from BLE pulse sightings, see the class KDoc) skip the
     * re-attempt entirely. `NsdManager.registerService` blocks for up to
     * its 2 s registration timeout per attempt, so re-attempting at pulse
     * cadence would hammer the platform NSD stack and flood the
     * diagnostics log with identical timeout failures (issue #262). A
     * decision *change* still attempts immediately.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun attemptPublishLocked(
        decision: Decision,
        scope: CoroutineScope,
    ) {
        if (session.isAdvertising) {
            publishRetryJob?.cancel()
            publishRetryJob = null
            pendingRetryDecision = null
            return
        }
        if (publishRetryJob?.isActive == true && decision == pendingRetryDecision) return
        try {
            session.publishAdvertisement()
            publishRetryJob?.cancel()
            publishRetryJob = null
            pendingRetryDecision = null
            DiagnosticLog.w(TAG, "publish: published mDNS (decision=$decision)")
        } catch (t: Throwable) {
            // The session may have been stopped between the collector
            // firing and the publish call, or Android's NSD stack may be
            // temporarily unavailable while Wi-Fi is still coming up.
            // Keep retrying while the current decision still wants us to
            // be published. Expected/retried failure: summary line only,
            // rate-limited — no stack trace (issue #262).
            logThrottledWarning(
                site = "publish",
                message = "publish: failed (decision=$decision)",
                cause = t,
            )
            pendingRetryDecision = decision
            schedulePublishRetry(scope)
        }
    }

    private fun schedulePublishRetry(scope: CoroutineScope) {
        if (publishRetryJob?.isActive == true) return
        var retryJob: Job? = null
        retryJob =
            scope.launch {
                delay(publishRetryDelayMillis)
                if (isActive) {
                    if (publishRetryJob === retryJob) {
                        publishRetryJob = null
                    }
                    apply(currentDecision(), scope)
                }
            }
        publishRetryJob =
            retryJob
    }

    /**
     * Best-effort `bleBroadcaster.start` that swallows all exceptions
     * with a logged warning. The broadcaster itself is supposed to fail
     * silently (no advertise permission, no LE peripheral, etc.); this
     * wrapper exists so a misbehaving fake in tests cannot poison the
     * gate's mDNS path.
     *
     * Logging is transition-only: BLE pulse sightings re-run [apply] (and
     * therefore this call) several times per second during a peer
     * session, but only a *change* in the (decision, outcome) pair is a
     * loggable event (issue #262). The broadcaster call itself is still
     * issued every time — the production implementation is an
     * identity-unchanged no-op, and skipping it here would break identity
     * refresh on decision changes.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun startBleSafely(decision: Decision) {
        try {
            val started = bleBroadcaster.start()
            if (started) {
                bleAdvertising = true
            }
            val shouldLog =
                synchronized(logStateLock) {
                    val logKey = decision to started
                    val changed = lastBleStartLog != logKey
                    if (changed) lastBleStartLog = logKey
                    changed
                }
            if (shouldLog) {
                if (started) {
                    DiagnosticLog.w(TAG, "publish: BLE pulse advertise started (decision=$decision)")
                } else {
                    DiagnosticLog.w(TAG, "publish: BLE pulse advertise unavailable (decision=$decision)")
                }
            }
        } catch (t: Throwable) {
            logThrottledWarning(
                site = "ble-start",
                message = "publish: BLE pulse advertise threw (decision=$decision)",
                cause = t,
            )
        }
    }

    /**
     * Best-effort `bleBroadcaster.stop` that swallows all exceptions.
     * Logging mirrors [startBleSafely] so the lifecycle is greppable
     * end-to-end. Also resets the start-log dedupe state so the next
     * start after this stop logs its transition again.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun stopBleSafely(reason: String) {
        if (!bleAdvertising) return
        try {
            bleBroadcaster.stop()
            bleAdvertising = false
            synchronized(logStateLock) { lastBleStartLog = null }
            DiagnosticLog.w(TAG, "unpublish: BLE pulse advertise stopped ($reason)")
        } catch (t: Throwable) {
            DiagnosticLog.w(TAG, "unpublish: BLE pulse advertise stop threw ($reason)", t)
        }
    }

    /**
     * Emit `"<message>: <cause summary>"` as a single-line warning, at
     * most once per [FAILURE_LOG_THROTTLE_MILLIS] per (site, exception
     * class). The summary is the exception class + message chain — no
     * stack trace. Used for expected/retried failures on the publish path
     * so an NsdManager stall cannot flood the size-capped diagnostics
     * rotation (issue #262); rare teardown-path failures keep their full
     * stacks.
     */
    private fun logThrottledWarning(
        site: String,
        message: String,
        cause: Throwable,
    ) {
        val now = wallClockMillis()
        val throttleKey = "$site:${cause.javaClass.name}"
        val shouldLog =
            synchronized(logStateLock) {
                val last = throttledWarningLastEmitMillis[throttleKey]
                if (last != null && now - last < FAILURE_LOG_THROTTLE_MILLIS) {
                    false
                } else {
                    throttledWarningLastEmitMillis[throttleKey] = now
                    true
                }
            }
        if (shouldLog) {
            DiagnosticLog.w(TAG, "$message: ${summaryLine(cause)}")
        }
    }

    /**
     * Single-line `Class: message` rendering of [cause] and its cause
     * chain (bounded to [MAX_SUMMARY_CAUSE_DEPTH] links so a cyclic chain
     * cannot loop).
     */
    private fun summaryLine(cause: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = cause
        var depth = 0
        while (current != null && depth < MAX_SUMMARY_CAUSE_DEPTH) {
            parts += "${current.javaClass.simpleName}: ${current.message}"
            current = current.cause
            depth += 1
        }
        return parts.joinToString(separator = " caused by ")
    }

    /**
     * Snapshot of the three input flags. Lifted to a data class with
     * named fields rather than a `Triple<Boolean, Boolean, Boolean>` so
     * the meaning of each value stays explicit at every call site.
     */
    private data class Decision(
        val bleActive: Boolean,
        val overrideOn: Boolean,
        val qrActive: Boolean,
        val outboundActive: Boolean,
    )

    public companion object {
        /** logcat tag for gate-related lines. */
        internal const val TAG: String = "BadaMdnsGate"

        /**
         * Default 30 s idle window before the gate unpublishes the
         * advertisement. Pinned by #34's acceptance criterion: "After
         * mDNS goes up, it stays up for at least 30 s (debounce) to
         * avoid flapping if the BLE pulse is intermittent."
         */
        public const val DEFAULT_DEBOUNCE_IDLE_MILLIS: Long = 30_000L

        /**
         * Default delay before retrying a failed publish while a publish
         * signal remains active. Short enough to recover after radio
         * toggles without requiring user interaction, long enough to avoid
         * hammering Android NSD when it is stuck.
         */
        public const val DEFAULT_PUBLISH_RETRY_DELAY_MILLIS: Long = 5_000L

        /**
         * Minimum spacing between two throttled failure warnings for the
         * same (site, exception class). Chosen well above the 5 s publish
         * retry cadence so a stuck NsdManager produces one summary line
         * per window instead of one stack trace per retry (issue #262).
         */
        public const val FAILURE_LOG_THROTTLE_MILLIS: Long = 30_000L

        /** Bound on the cause chain rendered by the failure summary line. */
        private const val MAX_SUMMARY_CAUSE_DEPTH: Int = 4
    }
}

/**
 * Sink for the receiver-side BLE pulse advertiser (#121).
 *
 * Lifted out of [MdnsAdvertisementGate] so the gate stays platform-
 * neutral and so JVM unit tests can record start/stop calls without
 * standing up an Android `BluetoothLeAdvertiser`. Production wires this
 * to a closure that captures the gate's `BleQuickShareAdvertiser` plus
 * the receiver's stable [EndpointInfo] / endpoint_id.
 *
 * Implementations must be idempotent: the gate calls [start] every time
 * its publish decision flips back on, regardless of whether the
 * underlying advertiser is already running.
 */
public interface BleVisibilityBroadcaster {
    /**
     * Advertise the receiver's BLE pulse. Idempotent.
     *
     * Failures (no permission, no peripheral mode, adapter off) are
     * the broadcaster's responsibility to swallow — the gate logs but
     * does not retry.
     *
     * @return `true` when a platform advertisement is active after the
     * call, `false` when BLE advertising was unavailable or intentionally
     * skipped.
     */
    public fun start(): Boolean

    /**
     * Stop advertising. Idempotent.
     */
    public fun stop()

    /**
     * No-op broadcaster used when the gate is constructed without BLE
     * support — early test fixtures, or production paths where the
     * `BleQuickShareAdvertiser` could not be initialised.
     */
    public object Noop : BleVisibilityBroadcaster {
        override fun start(): Boolean {
            // Intentionally empty.
            return false
        }

        override fun stop() {
            // Intentionally empty.
        }
    }
}
