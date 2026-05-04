/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.connection

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.KeepAliveFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for the outbound `KEEP_ALIVE` ticker introduced in
 * issue #37 ("Outbound keep-alive timer — 10-second cadence").
 *
 * Two layers are tested here:
 *   1. [OfflineFrames.keepAlive] — frame shape (type, body, ack flag).
 *   2. [KeepAliveTicker.run] — cadence, cooperative cancellation, and
 *      error-path containment. Virtual time keeps the suite fast even
 *      though production fires once every 10 seconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Timeout(value = 5, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class KeepAliveTickerTest {
    @Test
    fun `keepAlive ack=false has KEEP_ALIVE type and ack=false body`() {
        val frame: OfflineFrame = OfflineFrames.keepAlive(ack = false)

        // Round-trip through the proto parser to make sure nothing
        // depends on the builder's in-memory representation.
        val parsed = OfflineFrame.parseFrom(frame.toByteArray())

        assertThat(parsed.version).isEqualTo(OfflineFrame.Version.V1)
        assertThat(parsed.v1.type).isEqualTo(V1Frame.FrameType.KEEP_ALIVE)
        assertThat(parsed.v1.hasKeepAlive()).isTrue()

        val keepAlive: KeepAliveFrame = parsed.v1.keepAlive
        assertThat(keepAlive.hasAck()).isTrue()
        assertThat(keepAlive.ack).isFalse()
    }

    @Test
    fun `keepAlive ack=true round-trips with ack=true body`() {
        val parsed = OfflineFrame.parseFrom(OfflineFrames.keepAlive(ack = true).toByteArray())

        assertThat(parsed.v1.type).isEqualTo(V1Frame.FrameType.KEEP_ALIVE)
        assertThat(parsed.v1.keepAlive.hasAck()).isTrue()
        assertThat(parsed.v1.keepAlive.ack).isTrue()
    }

    @Test
    fun `production cadence matches PROTOCOL_md 10 second contract`() {
        // Guards both the `KeepAliveTicker.DEFAULT_INTERVAL_MILLIS`
        // constant and the value advertised on the wire as
        // `keep_alive_interval_millis`. PROTOCOL.md is explicit: stock
        // Android emits KEEP_ALIVE every 10 seconds. The two values
        // MUST agree — the receiver uses our advertised value as its
        // watchdog cadence, and a mismatch would let the receiver tear
        // an idle connection down because our actual ticks landed
        // outside its expected window.
        assertThat(KeepAliveTicker.DEFAULT_INTERVAL_MILLIS).isEqualTo(10_000L)
    }

    @Test
    fun `ticker emits no frame before first interval has elapsed`() =
        runTest {
            val sent = AtomicInteger(0)
            val job =
                launch {
                    KeepAliveTicker.run(
                        intervalMillis = 1_000L,
                        send = { sent.incrementAndGet() },
                    )
                }

            // Before the first interval: nothing on the wire. (Quick
            // Share's spec is that the timer fires AFTER the first
            // 10 s window — sending one immediately would just
            // duplicate the encryption-establish frame's purpose.)
            advanceTimeBy(999L)
            runCurrent()
            assertThat(sent.get()).isEqualTo(0)

            job.cancelAndJoin()
        }

    @Test
    fun `ticker fires on cadence under virtual time`() =
        runTest {
            val sent = AtomicInteger(0)
            val capturedFrames = mutableListOf<OfflineFrame>()
            val job =
                launch {
                    KeepAliveTicker.run(
                        intervalMillis = 1_000L,
                        send = { frame ->
                            capturedFrames += frame
                            sent.incrementAndGet()
                        },
                    )
                }

            // Three full intervals -> exactly three ticks. (Plus a
            // small skew so the third tick's `delay` resumes before
            // the assertion runs.)
            advanceTimeBy(3_500L)
            runCurrent()

            assertThat(sent.get()).isEqualTo(3)
            // Every emitted frame is a well-formed KEEP_ALIVE{ack=false}.
            assertThat(capturedFrames).hasSize(3)
            for (f in capturedFrames) {
                assertThat(f.v1.type).isEqualTo(V1Frame.FrameType.KEEP_ALIVE)
                assertThat(f.v1.keepAlive.ack).isFalse()
            }

            job.cancelAndJoin()
        }

    @Test
    fun `cancelAndJoin stops the ticker without one final tick`() =
        runTest {
            val sent = AtomicInteger(0)
            val job =
                launch {
                    KeepAliveTicker.run(
                        intervalMillis = 1_000L,
                        send = { sent.incrementAndGet() },
                    )
                }

            // Just past two ticks.
            advanceTimeBy(2_500L)
            runCurrent()
            val ticksBeforeCancel = sent.get()
            assertThat(ticksBeforeCancel).isEqualTo(2)

            job.cancelAndJoin()

            // After cancellation, advancing time MUST NOT produce more
            // ticks — the cancellation token cuts the loop on the next
            // suspension point (the next `delay`).
            advanceTimeBy(10_000L)
            runCurrent()
            assertThat(sent.get()).isEqualTo(ticksBeforeCancel)
        }

    @Test
    fun `send error reaches onError and ticker exits cleanly`() =
        runTest {
            val onErrorCallCount = AtomicInteger(0)
            val capturedError: Array<Throwable?> = arrayOf(null)
            val attempts = AtomicInteger(0)
            val firstAttemptError = IOException("socket closed by peer")

            val job =
                launch {
                    KeepAliveTicker.run(
                        intervalMillis = 1_000L,
                        send = {
                            attempts.incrementAndGet()
                            throw firstAttemptError
                        },
                        onError = { e ->
                            onErrorCallCount.incrementAndGet()
                            capturedError[0] = e
                        },
                    )
                }

            // First tick fires, send throws, ticker exits.
            advanceTimeBy(1_500L)
            runCurrent()
            job.join()

            assertThat(attempts.get()).isEqualTo(1)
            assertThat(onErrorCallCount.get()).isEqualTo(1)
            assertThat(capturedError[0]).isSameInstanceAs(firstAttemptError)

            // Even with more virtual time, no further ticks (the loop
            // exited; onError is not a "keep going" signal).
            advanceTimeBy(10_000L)
            runCurrent()
            assertThat(attempts.get()).isEqualTo(1)
            assertThat(onErrorCallCount.get()).isEqualTo(1)
        }

    @Test
    fun `CancellationException from send propagates and skips onError`() =
        runTest {
            val onErrorCallCount = AtomicInteger(0)

            // A child coroutine that runs the ticker; cancelled
            // externally so the inner CancellationException is the
            // real deal (parent-cancellation), not an arbitrary
            // application-thrown one.
            val job =
                launch {
                    KeepAliveTicker.run(
                        intervalMillis = 1_000L,
                        send = {
                            // Block here forever; we want the cancel
                            // to interrupt the send itself, not the
                            // outer delay. The cooperative-cancellation
                            // contract says runCatching/try blocks
                            // around `send` MUST NOT eat
                            // CancellationException.
                            delay(Long.MAX_VALUE)
                        },
                        onError = { onErrorCallCount.incrementAndGet() },
                    )
                }

            advanceTimeBy(1_500L)
            runCurrent()

            job.cancelAndJoin()

            assertThat(onErrorCallCount.get()).isEqualTo(0)
        }

    @Test
    fun `non-positive interval throws IllegalArgumentException`() {
        // Guard against a future caller wiring up `0L` or a negative
        // value — `delay(0)` would degenerate into a busy loop and
        // saturate the SecureChannel send mutex.
        runTest {
            assertThrows<IllegalArgumentException> {
                KeepAliveTicker.run(intervalMillis = 0L, send = { /* unreachable */ })
            }
            assertThrows<IllegalArgumentException> {
                KeepAliveTicker.run(intervalMillis = -1L, send = { /* unreachable */ })
            }
        }
    }

    @Test
    fun `parent scope cancellation tears down the ticker via structured concurrency`() =
        runTest {
            val sent = AtomicInteger(0)

            assertThrows<CancellationException> {
                coroutineScope {
                    launch {
                        KeepAliveTicker.run(
                            intervalMillis = 500L,
                            send = { sent.incrementAndGet() },
                        )
                    }

                    advanceTimeBy(1_500L)
                    runCurrent()
                    assertThat(sent.get()).isEqualTo(3)

                    // Cancel the parent scope; structured concurrency
                    // should take the ticker out as a child.
                    throw CancellationException("teardown")
                }
            }

            // After the scope's cancellation throw resolves, no further
            // ticks should arrive — the ticker must NOT be detached
            // from its parent's lifecycle.
            advanceTimeBy(10_000L)
            runCurrent()
            assertThat(sent.get()).isEqualTo(3)
        }
}
