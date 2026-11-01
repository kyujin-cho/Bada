/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver

import dev.bluehouse.bada.protocol.connection.InboundConnectionState

/**
 * Picks which state emissions of one inbound connection the receiver's
 * diagnostic logger writes (#304).
 *
 * The driver republishes [InboundConnectionState.Receiving] once per payload
 * chunk, about 840 times a second on a fast Wi-Fi LAN transfer. Logging every
 * one wrote about 41,000 lines per 2.7 GB transfer and pushed everything else
 * out of the size-capped `bada-diagnostics.log`.
 *
 * Every other state is logged. A `Receiving` state is logged when it is the
 * first one, when [InboundConnectionState.Receiving.currentItemPayloadId]
 * changes (including to and from `null` at an item boundary), or when at least
 * [intervalMillis] have passed since the last logged one.
 *
 * One instance per connection. Not thread-safe; the per-connection state
 * collector calls it sequentially.
 */
internal class InboundStateLogThrottle(
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
) {
    private var lastLogged: InboundConnectionState.Receiving? = null
    private var lastLoggedAtMillis: Long = 0L

    /** [nowMillis] must come from a monotonic clock. */
    fun shouldLog(
        state: InboundConnectionState,
        nowMillis: Long,
    ): Boolean {
        if (state !is InboundConnectionState.Receiving) return true
        val previous = lastLogged
        val log =
            previous == null ||
                previous.currentItemPayloadId != state.currentItemPayloadId ||
                nowMillis - lastLoggedAtMillis >= intervalMillis
        if (log) {
            lastLogged = state
            lastLoggedAtMillis = nowMillis
        }
        return log
    }

    companion object {
        const val DEFAULT_INTERVAL_MILLIS: Long = 1_000L
    }
}
