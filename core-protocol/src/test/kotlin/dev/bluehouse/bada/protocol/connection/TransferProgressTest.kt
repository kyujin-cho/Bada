/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.connection

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * JVM-only invariants for the [TransferProgress] value type used by
 * [InboundConnectionState.Receiving] and [OutboundConnectionState.Sending].
 *
 * The maths are tiny but security-relevant: ETA is shown to the user
 * during a transfer, so a corner-case-busting `null` here would make
 * the receiver foreground notification render "unknown" forever.
 */
class TransferProgressTest {
    @Test
    fun `of computes ETA from the simple formula when all inputs are positive`() {
        val progress =
            TransferProgress.of(
                bytesTransferred = 50L * MIB,
                totalSize = 100L * MIB,
                bytesPerSecond = 5L * MIB,
            )
        // Remaining = 50 MIB; rate = 5 MIB/s; ETA = 10 s.
        assertThat(progress.etaSeconds).isEqualTo(10L)
        assertThat(progress.bytesPerSecond).isEqualTo(5L * MIB)
        assertThat(progress.bytesTransferred).isEqualTo(50L * MIB)
        assertThat(progress.totalSize).isEqualTo(100L * MIB)
    }

    @Test
    fun `of returns null ETA while warming up (rate is zero)`() {
        val progress =
            TransferProgress.of(
                bytesTransferred = 1L * MIB,
                totalSize = 100L * MIB,
                bytesPerSecond = 0L,
            )
        assertThat(progress.etaSeconds).isNull()
    }

    @Test
    fun `of returns null ETA when nothing has been announced`() {
        val progress =
            TransferProgress.of(
                bytesTransferred = 0L,
                totalSize = 0L,
                bytesPerSecond = 1024L,
            )
        assertThat(progress.etaSeconds).isNull()
    }

    @Test
    fun `of returns null ETA when transferred meets total (essentially done)`() {
        val progress =
            TransferProgress.of(
                bytesTransferred = 100L * MIB,
                totalSize = 100L * MIB,
                bytesPerSecond = 5L * MIB,
            )
        assertThat(progress.etaSeconds).isNull()
    }

    @Test
    fun `of rounds the ETA up so a 1-byte tail at 1 byte per second shows 1s not 0s`() {
        val progress =
            TransferProgress.of(
                bytesTransferred = 99L,
                totalSize = 100L,
                bytesPerSecond = 1L,
            )
        // Remaining = 1 byte; ceil(1 / 1) = 1 s.
        assertThat(progress.etaSeconds).isEqualTo(1L)
    }

    @Test
    fun `of ceils mid-bucket ETAs (not truncated to zero)`() {
        val progress =
            TransferProgress.of(
                bytesTransferred = 9_500L,
                totalSize = 10_000L,
                bytesPerSecond = 1_000L,
            )
        // Remaining = 500 bytes; 500 / 1000 = 0.5; ceil -> 1.
        assertThat(progress.etaSeconds).isEqualTo(1L)
    }

    @Test
    fun `fraction is 0 when totalSize is 0 (no NaN leak)`() {
        val progress = TransferProgress.UNKNOWN
        assertThat(progress.fraction).isEqualTo(0.0)
    }

    @Test
    fun `fraction is clamped to 1 when transferred exceeds total (rare overshoot)`() {
        val progress =
            TransferProgress.of(
                bytesTransferred = 200L,
                totalSize = 100L,
                bytesPerSecond = 0L,
            )
        assertThat(progress.fraction).isEqualTo(1.0)
    }

    @Test
    fun `fraction reads halfway through a transfer`() {
        val progress =
            TransferProgress.of(
                bytesTransferred = 50L,
                totalSize = 100L,
                bytesPerSecond = 0L,
            )
        assertThat(progress.fraction).isEqualTo(0.5)
    }

    @Test
    fun `constructor rejects negative bytesTransferred`() {
        assertThrows<IllegalArgumentException> {
            TransferProgress(
                bytesTransferred = -1L,
                totalSize = 100L,
                bytesPerSecond = 0L,
                etaSeconds = null,
            )
        }
    }

    @Test
    fun `constructor rejects negative totalSize`() {
        assertThrows<IllegalArgumentException> {
            TransferProgress(
                bytesTransferred = 0L,
                totalSize = -1L,
                bytesPerSecond = 0L,
                etaSeconds = null,
            )
        }
    }

    @Test
    fun `constructor rejects negative bytesPerSecond`() {
        assertThrows<IllegalArgumentException> {
            TransferProgress(
                bytesTransferred = 0L,
                totalSize = 100L,
                bytesPerSecond = -1L,
                etaSeconds = null,
            )
        }
    }

    @Test
    fun `constructor rejects negative etaSeconds`() {
        assertThrows<IllegalArgumentException> {
            TransferProgress(
                bytesTransferred = 0L,
                totalSize = 100L,
                bytesPerSecond = 1L,
                etaSeconds = -1L,
            )
        }
    }

    @Test
    fun `UNKNOWN sentinel has all-zero counters and null ETA`() {
        val unknown = TransferProgress.UNKNOWN
        assertThat(unknown.bytesTransferred).isEqualTo(0L)
        assertThat(unknown.totalSize).isEqualTo(0L)
        assertThat(unknown.bytesPerSecond).isEqualTo(0L)
        assertThat(unknown.etaSeconds).isNull()
    }

    private companion object {
        const val MIB: Long = 1024L * 1024L
    }
}
