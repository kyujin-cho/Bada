/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.connection

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Outbound `KEEP_ALIVE` ticker shared infrastructure.
 *
 * PROTOCOL.md ("Connection management"):
 * > Android sends offline frames of type `KEEP_ALIVE` every 10 seconds
 * > and expects the server to do the same. If you don't, it will
 * > terminate the connection after a while thinking your app crashed
 * > or something. This especially comes into play when sending large
 * > files.
 *
 * Without this ticker the upper bound on idle time is the advertised
 * `keep_alive_timeout_millis` (we set 10 minutes); a chunk-to-chunk
 * stall on a slow link, plus the receiver writing to slow storage,
 * can blow past it. The ticker fires `KEEP_ALIVE{ack=false}` at the
 * configured cadence and lets `SecureChannel`'s send mutex serialize
 * its writes against payload chunks and FSM frames.
 *
 * Factored out from [OutboundConnectionDriver] purely for testability:
 * the driver hot path passes the production cadence ([DEFAULT_INTERVAL_MILLIS])
 * and a `SecureChannel::sendOfflineFrame` reference; tests pass a tiny
 * cadence and a recording sink to exercise the loop in finite time.
 */
internal object KeepAliveTicker {
    /**
     * Production cadence. Matches PROTOCOL.md's documented stock-Android
     * behaviour and the value advertised in our
     * `ConnectionRequestFrame.keep_alive_interval_millis`.
     */
    const val DEFAULT_INTERVAL_MILLIS: Long = 10_000L

    /**
     * Run the ticker loop until cancelled.
     *
     * The first tick fires after [intervalMillis] of "quiet time"
     * (i.e. there is no immediate-tick on entry). Cancellation is
     * cooperative: any `CancellationException` is propagated; any
     * other throwable from [send] (typically a socket I/O failure
     * because the connection is being torn down) is forwarded to
     * [onError] and the loop exits. The driver's receive loop is
     * authoritative for the connection's terminal outcome; the
     * ticker simply stops.
     *
     * @param intervalMillis Time between ticks. Production:
     *   [DEFAULT_INTERVAL_MILLIS]. Tests inject smaller values.
     * @param send Sink for one outbound `KEEP_ALIVE` frame. In
     *   production this is `SecureChannel::sendOfflineFrame`; the
     *   send mutex inside [dev.bluehouse.bada.protocol.crypto.securemessage.SecureChannel]
     *   provides per-connection serialization, satisfying the
     *   acceptance criterion that the ticker not interleave with
     *   payload writes.
     * @param onError Invoked once when [send] fails. Default is a
     *   silent no-op so production loops fail quietly during teardown.
     */
    suspend fun run(
        intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
        send: suspend (OfflineFrame) -> Unit,
        onError: (Throwable) -> Unit = {},
    ) {
        require(intervalMillis > 0L) {
            "KeepAliveTicker.run: intervalMillis must be positive, got $intervalMillis"
        }
        try {
            while (true) {
                delay(intervalMillis)
                send(OfflineFrames.keepAlive(ack = false))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            onError(e)
        }
    }
}
