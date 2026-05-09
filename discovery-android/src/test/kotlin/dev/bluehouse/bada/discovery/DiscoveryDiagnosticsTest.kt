/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.bada.protocol.endpoint.DeviceType
import dev.bluehouse.bada.protocol.endpoint.EndpointInfo
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Tests for [Discovery.snapshot] — the diagnostic API added for issue
 * #83 so on-device debugging can show whether discovery was actually
 * running and which events flowed.
 *
 * After the #98 NsdManager migration the `multicastLockHeld` flag
 * always reports `false` (the system mDNS responder owns the multicast
 * filter exemption); the field is retained on the data class for source
 * compatibility but is no longer meaningful diagnostically.
 */
class DiscoveryDiagnosticsTest {
    @Test
    fun `snapshot reports quiescent flags before any operation`() {
        val discovery =
            Discovery.forTesting(
                registrar = CountingNsdRegistrar(),
                browser = NoopNsdBrowser,
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
        val nsd = FakeNsd()
        val discovery =
            Discovery.forTesting(
                registrar = nsd.registrar,
                browser = nsd.browser,
            )
        val handle = discovery.advertise(sampleEndpointInfo(), port = 23_456)
        try {
            val active = discovery.snapshot()
            assertThat(active.advertising).isTrue()
            // multicastLockHeld is always false post-#98.
            assertThat(active.multicastLockHeld).isFalse()
            // FakeNsd surfaces its advertise address as the bound IP so
            // the snapshot has something concrete to display.
            assertThat(active.advertiseBoundAddress).isNotNull()
        } finally {
            handle.close()
        }

        val closed = discovery.snapshot()
        assertThat(closed.advertising).isFalse()
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
        // After #98 the logcat trail looks like:
        //   1. Install on Device A, start the receiver foreground service.
        //   2. Install on Device B, open a share intent → SendActivity.
        //   3. Within 5s, Device B's peer list shows Device A.
        //   4. Tag `BadaDiscovery` in logcat shows on both devices:
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
}
