/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.radio

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog

/**
 * ShareRadioController — the ONE place that forces Wi-Fi + Bluetooth on for a
 * share and restores them after, on behalf of every share entry point.
 *
 * ### Why this exists
 *
 * Three flows need the radios on for a transfer: the SENDER
 * ([dev.bluehouse.bada.send.SendActivity]), the cold NFC tap-to-receive wake, and
 * the Quick Settings tile (both via
 * [dev.bluehouse.bada.service.receiver.ReceiverForegroundService]). Each used to
 * re-implement the identical dance — create a [RadioHelperClient], `connect`,
 * `prepareForShare(RADIO_BOTH)`, track a "prepared" flag, then on teardown
 * `transferFinished` + `disconnect`. This class holds that logic once so the
 * call sites just say [requestRadiosOn] at share start and [restoreRadios] at
 * the terminal.
 *
 * The actual capture-original / enable-only-off / restore-only-ours logic lives
 * in the `:radio-helper` app's SESSION mode (the helper owns it and persists it
 * across a process kill); this controller is only the client-side lease.
 *
 * ### Contract / behaviour (preserved verbatim from the old call sites)
 *
 *  - [requestRadiosOn] is best-effort and async (binds on the caller's thread,
 *    work happens on the helper's): if the helper isn't installed / has the
 *    wrong signing key / is force-stopped / times out, the radios are left
 *    as-is and the caller's own fallback applies. No ANR.
 *  - [restoreRadios] tells the helper the share is over so it restores ONLY the
 *    radios it turned on, then unbinds. `finishSession` lets a caller unbind
 *    WITHOUT ending the helper session — the sender uses this on a
 *    config-change recreate (`isFinishing == false`): drop this dead instance's
 *    binding but leave the persisted, re-entrant helper session for the
 *    recreated instance to re-prepare. The receiver always ends the session.
 *  - [logTag] is the logcat tag for observability; pass `null` for none.
 *
 * Holds at most one client at a time; not thread-safe — call from a single
 * thread per instance (the main thread, as both existing call sites do).
 */
public class ShareRadioController(
    private val context: Context,
    private val logTag: String? = null,
) {
    private var client: RadioHelperClient? = null

    /** `true` once the helper has acknowledged a [requestRadiosOn] prepare. */
    public var isPrepared: Boolean = false
        private set

    // mainHandler / heartbeatTick — a 5 s keep-alive loop that runs for the life of
    // the share (started once the helper acknowledges prepare, stopped in
    // restoreRadios). Each tick sends RadioHelperClient.heartbeat() so that if THIS
    // host (activity / foreground service) is killed mid-transfer without calling
    // restoreRadios, the helper restores the radios ~20 s after the last beat instead
    // of holding them until its 20-min watchdog. A live transfer keeps beating, so a
    // long transfer is never cut. Runs on the main looper (heartbeat() is non-blocking).
    private val mainHandler = Handler(Looper.getMainLooper())
    private val heartbeatTick =
        object : Runnable {
            override fun run() {
                val c = client ?: return
                c.heartbeat()
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }

    /**
     * Bind the `:radio-helper` and force [radios] ON for the share. Idempotent
     * against repeat calls on the same instance (reuses the existing client).
     * [onPrepared] reports whether the helper acknowledged readiness, allowing
     * NFC callers to avoid advertising a ready-to-tap state before radios are on.
     */
    public fun requestRadiosOn(
        radios: Int = RadioHelperClient.RADIO_BOTH,
        onPrepared: (Boolean) -> Unit = {},
    ) {
        if (isPrepared) {
            onPrepared(true)
            return
        }
        val c = client ?: RadioHelperClient(context).also { client = it }
        log("requesting radios (bitmask=$radios) via radio-helper")
        c.connect { connected ->
            if (!connected) {
                log("radio-helper unavailable (not installed / wrong signing key / force-stopped) -> radios left as-is")
                onPrepared(false)
                return@connect
            }
            c.prepareForShare(radios) { nowOn ->
                isPrepared = true
                log("radio-helper prepared radios, now-on bitmask=$nowOn")
                // Begin the keep-alive heartbeat NOW that the radios are on. The first
                // beat fires IMMEDIATELY, so the helper's restore is armed to 20 s right
                // at enable; then every HEARTBEAT_INTERVAL_MS beat RESETS that 20 s. So
                // a crash mid-transfer (beats stop) restores ~20 s after the LAST beat —
                // even if it crashes before the first interval elapsed.
                mainHandler.removeCallbacks(heartbeatTick)
                mainHandler.post(heartbeatTick)
                onPrepared(true)
            }
        }
    }

    /**
     * Release the radio lease. When [finishSession] is true (the default —
     * a real terminal: transfer done / declined / cancelled / sheet closed)
     * the helper restores only what it turned on. When false (config-change
     * recreate) the session is left intact and we only unbind.
     */
    public fun restoreRadios(finishSession: Boolean = true) {
        val c = client ?: return
        // Stop the keep-alive heartbeat first — this instance is releasing its lease,
        // whether it's a real terminal or a config-change unbind.
        mainHandler.removeCallbacks(heartbeatTick)
        if (finishSession && isPrepared) {
            log("share finished -> radio-helper restores the radios it enabled")
            c.transferFinished()
            isPrepared = false
        }
        c.disconnect()
        client = null
    }

    private fun log(message: String) {
        if (logTag != null) DiagnosticLog.w(logTag, message)
    }

    private companion object {
        /** Keep-alive heartbeat interval. Must be well below the helper's 20 s
         *  post-heartbeat restore so a beat always lands before it would fire. */
        const val HEARTBEAT_INTERVAL_MS = 5_000L
    }
}
