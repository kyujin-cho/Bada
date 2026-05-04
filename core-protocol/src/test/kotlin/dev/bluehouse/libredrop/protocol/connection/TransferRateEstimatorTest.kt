/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.connection

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * JVM-only behavioural tests for [TransferRateEstimator].
 *
 * The EMA is the user-visible side of issue #46's "transfer rate"
 * acceptance criterion — the estimator's job is to smooth bursty
 * 512 KiB chunk arrival into a stable bytes/sec figure. The cases
 * below pin the smoothing behaviour at three points in the parameter
 * space:
 *
 *  - The first sample is seed-only (no rate yet).
 *  - A steady stream of evenly-spaced samples converges to the true
 *    rate.
 *  - A long idle gap drains the EMA back toward the new sample.
 */
class TransferRateEstimatorTest {
    @Test
    fun `bytesPerSecond is zero before any sample`() {
        val estimator = TransferRateEstimator()
        assertThat(estimator.bytesPerSecond()).isEqualTo(0L)
    }

    @Test
    fun `first sample is seed only and does not produce a rate`() {
        val estimator = TransferRateEstimator()
        estimator.sample(bytesTransferred = 0L, nowMillis = 0L)
        assertThat(estimator.bytesPerSecond()).isEqualTo(0L)
    }

    @Test
    fun `two samples one second apart produce the instantaneous rate`() {
        val estimator = TransferRateEstimator(halfLifeMillis = 1500L)
        estimator.sample(bytesTransferred = 0L, nowMillis = 0L)
        estimator.sample(bytesTransferred = 1_000_000L, nowMillis = 1_000L)
        // First non-seed sample: α = 1 - exp(-1000/(1500/ln2)) ≈ 0.37,
        // and the prior EMA is 0, so ema = 0.37 * 1_000_000 ≈ 370 KB/s.
        // We pin a generous range so a tiny floating-point drift does
        // not flake the test.
        val rate = estimator.bytesPerSecond()
        assertThat(rate).isGreaterThan(300_000L)
        assertThat(rate).isLessThan(450_000L)
    }

    @Test
    fun `steady stream converges to the true rate`() {
        val estimator = TransferRateEstimator(halfLifeMillis = 500L)
        // Stream 1 MB/s for 5 seconds, sampled every 100 ms.
        var bytes = 0L
        var t = 0L
        estimator.sample(bytesTransferred = bytes, nowMillis = t)
        repeat(50) {
            bytes += 100_000L // 100 KB per 100 ms = 1 MB/s
            t += 100L
            estimator.sample(bytesTransferred = bytes, nowMillis = t)
        }
        // After many half-lives at the same rate, the EMA should
        // sit very close to 1 MB/s. Allow ±10% for residual ramp.
        val rate = estimator.bytesPerSecond()
        assertThat(rate).isGreaterThan(900_000L)
        assertThat(rate).isLessThan(1_100_000L)
    }

    @Test
    fun `non-monotonic clock holds the EMA flat`() {
        val estimator = TransferRateEstimator(halfLifeMillis = 500L)
        // Establish a non-zero rate.
        estimator.sample(bytesTransferred = 0L, nowMillis = 0L)
        estimator.sample(bytesTransferred = 1_000_000L, nowMillis = 1_000L)
        val before = estimator.bytesPerSecond()

        // Backwards clock: Δt clamps to 1 ms, α ≈ 0, EMA stays put.
        estimator.sample(bytesTransferred = 1_000_000L, nowMillis = 999L)
        val after = estimator.bytesPerSecond()
        assertThat(after).isEqualTo(before)
    }

    @Test
    fun `long idle gap saturates alpha so the EMA forgets prior history`() {
        val estimator = TransferRateEstimator(halfLifeMillis = 500L)
        // Establish a high rate.
        estimator.sample(bytesTransferred = 0L, nowMillis = 0L)
        estimator.sample(bytesTransferred = 10_000_000L, nowMillis = 1_000L)
        val priorRate = estimator.bytesPerSecond()
        assertThat(priorRate).isGreaterThan(5_000_000L)

        // Long gap (>> 4 half-lives) with a single tiny chunk. The
        // saturated α means the EMA forgets the high prior and
        // collapses toward the new tiny instantaneous rate.
        val tNow = 1_000L + (500L * 100L) // 50 s later
        estimator.sample(bytesTransferred = 10_001_000L, nowMillis = tNow)
        val newRate = estimator.bytesPerSecond()
        // Instantaneous rate is 1000 bytes / 50 s = 20 B/s; saturated
        // α should put us very close.
        assertThat(newRate).isLessThan(1_000L)
    }

    @Test
    fun `reset clears state so the estimator behaves like fresh`() {
        val estimator = TransferRateEstimator()
        estimator.sample(bytesTransferred = 0L, nowMillis = 0L)
        estimator.sample(bytesTransferred = 1_000_000L, nowMillis = 1_000L)
        assertThat(estimator.bytesPerSecond()).isGreaterThan(0L)

        estimator.reset()
        assertThat(estimator.bytesPerSecond()).isEqualTo(0L)
        // First sample after reset is seed-only.
        estimator.sample(bytesTransferred = 100L, nowMillis = 2_000L)
        assertThat(estimator.bytesPerSecond()).isEqualTo(0L)
    }

    @Test
    fun `negative bytes input raises`() {
        val estimator = TransferRateEstimator()
        assertThrows<IllegalArgumentException> {
            estimator.sample(bytesTransferred = -1L, nowMillis = 0L)
        }
    }

    @Test
    fun `non-positive halfLife raises in the constructor`() {
        assertThrows<IllegalArgumentException> {
            TransferRateEstimator(halfLifeMillis = 0L)
        }
        assertThrows<IllegalArgumentException> {
            TransferRateEstimator(halfLifeMillis = -1L)
        }
    }

    @Test
    fun `same-timestamp resamples do not produce a divide-by-zero`() {
        val estimator = TransferRateEstimator()
        // Two samples at the same time: Δt clamps to 1 ms; α near 0;
        // EMA stays at zero.
        estimator.sample(bytesTransferred = 0L, nowMillis = 100L)
        estimator.sample(bytesTransferred = 1_000L, nowMillis = 100L)
        // No NaN, no infinity, no negative; just a very small EMA.
        val rate = estimator.bytesPerSecond()
        assertThat(rate).isAtLeast(0L)
    }
}
