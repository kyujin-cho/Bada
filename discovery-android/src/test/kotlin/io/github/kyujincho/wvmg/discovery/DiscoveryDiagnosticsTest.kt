/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

import com.google.common.truth.Truth.assertThat
import io.github.kyujincho.wvmg.protocol.endpoint.DeviceType
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.jmdns.JmDNS

/**
 * Tests for [Discovery.snapshot] — the diagnostic API added for issue
 * #83 so on-device debugging can show whether the multicast lock was
 * actually held, which interface JmDNS bound to, and whether any
 * service events flowed.
 *
 * These tests cover the snapshot transitions on the publish side
 * (advertise → close) and ensure the recent-events buffer is bounded.
 * The browse-side transitions are exercised by an instrumentation /
 * on-device test (see the @Disabled placeholder at the bottom).
 */
class DiscoveryDiagnosticsTest {
    @Test
    fun `snapshot reports lock state and quiescent flags before any operation`() {
        val locks = ToggleLockController()
        val discovery =
            Discovery.forTesting(
                locks = locks,
                jmdnsProvider = { error("not used") },
            )
        val snapshot = discovery.snapshot()
        assertThat(snapshot.advertising).isFalse()
        assertThat(snapshot.browsing).isFalse()
        assertThat(snapshot.multicastLockHeld).isFalse()
        assertThat(snapshot.advertiseBoundAddress).isNull()
        assertThat(snapshot.browseBoundAddress).isNull()
        assertThat(snapshot.recentEvents).isEmpty()
    }

    @Test
    fun `advertise transitions advertising flag and bound address`() {
        val locks = ToggleLockController()
        val discovery =
            Discovery.forTesting(
                locks = locks,
                jmdnsProvider = { name -> JmDNS.create(InetAddress.getLoopbackAddress(), name) },
            )
        val handle = discovery.advertise(sampleEndpointInfo(), port = 23_456)
        try {
            val active = discovery.snapshot()
            assertThat(active.advertising).isTrue()
            assertThat(active.multicastLockHeld).isTrue()
            assertThat(active.advertiseBoundAddress).isNotNull()
        } finally {
            handle.close()
        }

        val closed = discovery.snapshot()
        assertThat(closed.advertising).isFalse()
        assertThat(closed.multicastLockHeld).isFalse()
        assertThat(closed.advertiseBoundAddress).isNull()
    }

    @Test
    fun `recent events buffer bounds at the configured cap`() {
        val state = DiagnosticsState(maxEvents = 4)
        for (i in 1..10) {
            state.recordEvent(
                DiagnosticEvent(
                    kind = DiagnosticEvent.Kind.ADDED,
                    instanceName = "name-$i",
                    timestampMillis = i.toLong(),
                ),
            )
        }
        val snapshot = state.snapshot(multicastLockHeld = false)
        assertThat(snapshot.recentEvents).hasSize(4)
        // Oldest-to-newest ordering, with the four most recent surviving.
        assertThat(snapshot.recentEvents.map { it.instanceName })
            .containsExactly("name-7", "name-8", "name-9", "name-10")
            .inOrder()
    }

    /**
     * Placeholder marker for the on-device verification that needs two
     * physical Android phones on the same Wi-Fi network — the canonical
     * repro for issue #83. Mirrors the `@Disabled` pattern adopted in
     * #28 for connection-loop integration tests; flip the annotation
     * (or run via the Android instrumentation runner) once the manual
     * setup is in place.
     */
    @Disabled(
        "Requires two physical Android devices on the same Wi-Fi network; " +
            "tracked in #83's manual verification checklist.",
    )
    @Test
    fun `two devices on the same Wi-Fi network discover each other within five seconds`() {
        // Manual / instrumentation verification only — see issue #83.
        // Steps:
        //   1. Install on Device A, start the receiver foreground service.
        //   2. Install on Device B, open a share intent → SendActivity.
        //   3. Within 5s, Device B's peer list shows Device A.
        //   4. Tag `WvmgDiscovery` in logcat shows on both devices:
        //        - "MulticastLockHolder ... acquired"
        //        - "JmdnsFactory.create: binding ... to wifi address=<LAN IP>"
        //        - "advertise: registered ..." on Device A
        //        - "browse: serviceResolved ..." on Device B
    }

    private fun sampleEndpointInfo(): EndpointInfo =
        EndpointInfo(
            version = 1,
            hidden = false,
            deviceType = DeviceType.PHONE,
            reserved = false,
            metadata = ByteArray(EndpointInfo.METADATA_LEN) { it.toByte() },
            deviceName = "Diag Test",
            tlvRecords = emptyList(),
        )

    /**
     * [LockController] that mirrors a real ref-counted lock: tracks
     * acquire/release counts and surfaces `isHeld` from those.
     */
    private class ToggleLockController : LockController {
        private val ref = AtomicInteger(0)
        val held = AtomicBoolean(false)

        override fun acquire() {
            if (ref.getAndIncrement() == 0) held.set(true)
        }

        override fun release() {
            if (ref.decrementAndGet() == 0) held.set(false)
        }

        override fun isHeld(): Boolean = held.get()
    }
}
