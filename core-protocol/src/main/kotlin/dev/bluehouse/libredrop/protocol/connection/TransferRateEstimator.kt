/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.connection

import kotlin.math.exp

/**
 * Smoothed bytes-per-second estimator for an in-flight transfer.
 *
 * Implements an **exponential moving average** of the instantaneous
 * rate samples (`Δbytes / Δseconds`). The smoothing factor is derived
 * from a half-life expressed in seconds — a half-life of 2 s means a
 * rate sample's weight halves every 2 s, so the EMA "remembers" the
 * last few seconds and rejects single-chunk jitter.
 *
 * ### Why EMA over a fixed-size sliding window
 *
 * The Quick Share chunk size is 512 KiB and chunk arrival is bursty:
 * Wi-Fi LAN can deliver several chunks back-to-back in under 10 ms
 * and then stall while the receiver writes to MediaStore. A
 * fixed-size window over chunk count would oscillate between
 * "infinite rate" (zero-time burst) and "zero rate" (write stall).
 * Time-based EMA naturally smooths both extremes — it weights every
 * sample by elapsed wall time, so a write stall draws the average
 * down gradually rather than to zero.
 *
 * ### Numerical stability
 *
 * The EMA uses `α = 1 - exp(-Δt / τ)` where `τ = halfLife / ln(2)`.
 * `Δt` is clamped to `[1 ms, halfLife * 4]` so a clock jump backwards
 * (or a sample arriving more than 4 half-lives apart, which is
 * effectively a fresh transfer) does not produce a negative or
 * runaway alpha.
 *
 * ### Thread-safety
 *
 * **Not thread-safe.** The connection drivers update the estimator
 * from a single per-connection coroutine; sharing one across
 * coroutines requires external synchronization.
 *
 * @param halfLifeMillis Half-life of the EMA in milliseconds. Default
 *   `1500` ms — a 1.5 s half-life rejects the bursty 512 KiB-chunk
 *   jitter while still tracking a real rate change (e.g. the user
 *   moves between rooms and the Wi-Fi link drops a tier) within ~3 s.
 */
public class TransferRateEstimator(
    private val halfLifeMillis: Long = DEFAULT_HALF_LIFE_MILLIS,
) {
    init {
        require(halfLifeMillis > 0) {
            "halfLifeMillis must be positive, got $halfLifeMillis"
        }
    }

    /** `τ = halfLife / ln(2)`, the time-constant of the EMA. */
    private val tauMillis: Double = halfLifeMillis.toDouble() / LN_2

    /** Last sample timestamp, or `null` if no sample has been recorded. */
    private var lastSampleAtMillis: Long? = null

    /** Cumulative bytes at the last sample, or `null` if no sample yet. */
    private var lastBytes: Long? = null

    /** Current EMA estimate in bytes/second. `0.0` until two samples are seen. */
    private var emaBytesPerSecond: Double = 0.0

    /**
     * Record a cumulative-bytes sample taken at [nowMillis].
     *
     * The first sample only seeds the estimator; the second and
     * subsequent samples update the EMA. Edge cases:
     *
     *  - `Δt <= 0` (same timestamp or a backwards clock) leaves the
     *    EMA unchanged. The "last sample" pointer still advances so
     *    the next forward sample produces a sensible Δt.
     *  - `Δt > halfLife * 4` (a stalled transfer that resumed)
     *    drops the prior EMA and treats the new sample as a fresh
     *    seed. Keeping a tiny residual would let an arbitrarily-old
     *    rate bleed into the displayed figure.
     */
    public fun sample(
        bytesTransferred: Long,
        nowMillis: Long,
    ) {
        require(bytesTransferred >= 0) {
            "bytesTransferred must be non-negative, got $bytesTransferred"
        }

        val previousAt = lastSampleAtMillis
        val previousBytes = lastBytes
        lastSampleAtMillis = nowMillis
        lastBytes = bytesTransferred

        // First sample — seed only.
        if (previousAt == null || previousBytes == null) return

        val rawDeltaMs = nowMillis - previousAt
        val maxDeltaMillis = halfLifeMillis * MAX_DELTA_HALFLIVES

        when {
            // Backwards or zero-elapsed sample: hold the EMA flat. We
            // cannot trust the elapsed time, so blending in a sample
            // would drift the average for no good reason.
            rawDeltaMs <= 0L -> Unit

            // Long-gap reset: a sample arriving more than
            // [MAX_DELTA_HALFLIVES] half-lives after the previous one
            // signals a stalled transfer. Treat the new sample as a
            // fresh seed (drop the prior EMA) — keeping a tiny
            // residual alpha would let an arbitrarily-old high rate
            // bleed into the displayed figure, which is exactly what
            // the user does NOT want to see when their Wi-Fi link
            // recovered after a long pause.
            rawDeltaMs > maxDeltaMillis -> emaBytesPerSecond = 0.0

            else -> {
                val deltaMs = rawDeltaMs.coerceAtLeast(MIN_DELTA_MILLIS)
                val deltaBytes = (bytesTransferred - previousBytes).coerceAtLeast(0L)
                val instant = deltaBytes.toDouble() * MILLIS_PER_SECOND / deltaMs.toDouble()
                val alpha = 1.0 - exp(-deltaMs.toDouble() / tauMillis)
                emaBytesPerSecond = alpha * instant + (1.0 - alpha) * emaBytesPerSecond
            }
        }
    }

    /**
     * Current smoothed rate in bytes/second. Returns `0` when fewer
     * than two samples have been recorded (warming up) or when the
     * EMA has decayed below 1 B/s.
     */
    public fun bytesPerSecond(): Long {
        // Return 0 while warming up (no sample yet) or while the EMA
        // has decayed below 1 B/s — both are user-facing "calculating"
        // states that the UI renders without a numeric rate.
        val warming = lastSampleAtMillis == null || emaBytesPerSecond < 1.0
        return if (warming) 0L else emaBytesPerSecond.toLong()
    }

    /**
     * Reset the estimator to its initial empty state. Used when a
     * connection is re-bound (e.g. retry-on-fail) so a stale rate
     * does not leak across attempts.
     */
    public fun reset() {
        lastSampleAtMillis = null
        lastBytes = null
        emaBytesPerSecond = 0.0
    }

    public companion object {
        /**
         * Default EMA half-life. 1500 ms strikes the same balance as
         * the receiver foreground notification's 500 ms refresh
         * cadence: every notification update sees roughly three
         * half-lives of history, which is enough to smooth chunk
         * jitter without lagging behind a sustained rate change.
         */
        public const val DEFAULT_HALF_LIFE_MILLIS: Long = 1500L

        private const val LN_2: Double = 0.6931471805599453
        private const val MIN_DELTA_MILLIS: Long = 1L

        /**
         * Clamp `Δt` to at most this many half-lives. A gap longer
         * than this makes α saturate at 1.0, which means the EMA
         * effectively forgets its prior value — i.e. treats the next
         * sample as a fresh start. Four half-lives keeps weight at
         * about 6.25% of any pre-gap history, which is small enough
         * to ignore.
         */
        private const val MAX_DELTA_HALFLIVES: Long = 4L

        private const val MILLIS_PER_SECOND: Long = 1000L
    }
}
