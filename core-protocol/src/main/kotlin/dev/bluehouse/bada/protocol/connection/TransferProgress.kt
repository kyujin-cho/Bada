/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.connection

/**
 * Snapshot of an in-flight Quick Share transfer's throughput.
 *
 * Surfaced as part of [InboundConnectionState.Receiving] /
 * [OutboundConnectionState.Sending] so observers can render rate +
 * ETA without reaching into the connection driver internals.
 *
 * Pure-JVM value type — no Android imports — so the same shape can
 * back the receiver foreground notification (#46), the sender status
 * panel (#46), and any future surface (e.g. a transfer history log)
 * without re-deriving the maths.
 *
 * Issue #46 acceptance criteria:
 *
 *  - Rate calculated as exponential moving average of recent
 *    bytes/second (smooths over jitter).
 *  - ETA = `(total - transferred) / current_rate`.
 *
 * Both fields are *defensive* about insufficient data: a brand-new
 * transfer with one sample has [bytesPerSecond] = 0 and [etaSeconds] =
 * `null`, which the UI is expected to render as "calculating…" rather
 * than "0 B/s, unknown ETA".
 *
 * @property bytesTransferred Cumulative bytes transferred so far.
 *   Always `>= 0`. Caps at `totalSize` once the transfer completes.
 * @property totalSize Total bytes announced for the transfer. Always
 *   `>= 0`. May be `0` for transfers that announced no payloads
 *   (rare; the orchestrator publishes a placeholder snapshot during
 *   negotiation).
 * @property bytesPerSecond Smoothed instantaneous throughput in bytes
 *   per second. `0` while warming up (fewer than two samples) or after
 *   a long idle gap that drains the EMA below 1 B/s. Always `>= 0`.
 * @property etaSeconds Estimated seconds until completion. `null` when
 *   the rate is `0` (cannot divide), when [bytesTransferred] already
 *   meets [totalSize] (transfer is essentially done), or when
 *   [totalSize] is `0` (no announced payload). Always `>= 0` when
 *   non-null.
 */
public data class TransferProgress(
    val bytesTransferred: Long,
    val totalSize: Long,
    val bytesPerSecond: Long,
    val etaSeconds: Long?,
) {
    init {
        require(bytesTransferred >= 0) {
            "bytesTransferred must be non-negative, got $bytesTransferred"
        }
        require(totalSize >= 0) {
            "totalSize must be non-negative, got $totalSize"
        }
        require(bytesPerSecond >= 0) {
            "bytesPerSecond must be non-negative, got $bytesPerSecond"
        }
        require(etaSeconds == null || etaSeconds >= 0) {
            "etaSeconds must be non-negative when present, got $etaSeconds"
        }
    }

    /**
     * Fraction of the transfer completed in `[0.0, 1.0]`. Returns
     * `0.0` when [totalSize] is `0` so callers can multiply directly
     * by a notification progress-bar resolution without a NaN check.
     */
    public val fraction: Double
        get() = if (totalSize <= 0L) 0.0 else (bytesTransferred.toDouble() / totalSize.toDouble()).coerceIn(0.0, 1.0)

    public companion object {
        /**
         * Snapshot used before any byte has been transferred. The UI
         * renders this as "0% • calculating…" — both the rate and the
         * ETA are unknown.
         */
        @JvmField
        public val UNKNOWN: TransferProgress =
            TransferProgress(
                bytesTransferred = 0L,
                totalSize = 0L,
                bytesPerSecond = 0L,
                etaSeconds = null,
            )

        /**
         * Build a [TransferProgress] from raw byte counts plus a
         * smoothed rate. Computes [etaSeconds] from the same inputs
         * the constructor would otherwise require the caller to
         * derive by hand:
         *
         *  - `null` if [bytesPerSecond] is `0` (cannot divide).
         *  - `null` if [totalSize] is `0` (nothing was announced).
         *  - `null` if [bytesTransferred] already meets [totalSize]
         *    (the transfer is essentially done — the UI typically
         *    transitions to the terminal state moments later).
         *  - otherwise `ceil((total - transferred) / bytesPerSecond)`,
         *    rounded up so the user does not see a 0-second ETA for
         *    the final tail of a transfer.
         */
        public fun of(
            bytesTransferred: Long,
            totalSize: Long,
            bytesPerSecond: Long,
        ): TransferProgress {
            val eta =
                when {
                    bytesPerSecond <= 0L -> null
                    totalSize <= 0L -> null
                    bytesTransferred >= totalSize -> null
                    else -> {
                        val remaining = totalSize - bytesTransferred
                        // Ceil division so a 1-byte tail at 1 B/s
                        // shows up as "1s" rather than "0s".
                        (remaining + bytesPerSecond - 1L) / bytesPerSecond
                    }
                }
            return TransferProgress(
                bytesTransferred = bytesTransferred,
                totalSize = totalSize,
                bytesPerSecond = bytesPerSecond,
                etaSeconds = eta,
            )
        }
    }
}
