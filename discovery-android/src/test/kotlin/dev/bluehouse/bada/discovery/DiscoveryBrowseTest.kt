/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.bada.protocol.endpoint.DeviceType
import dev.bluehouse.bada.protocol.endpoint.EndpointInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

/**
 * Integration-style tests exercising [Discovery] against the in-memory
 * [FakeNsd]. These verify that the publish + browse paths line up
 * correctly: an `advertise` call surfaces a `Resolved` event on a
 * concurrently-collected `browse` flow, including the bit-exact binary
 * TXT round-trip.
 *
 * The same coverage was previously provided by JmDNS-loopback tests; the
 * fake here replaces them so the suite continues to run on every JVM.
 */
class DiscoveryBrowseTest {
    @Test
    fun `advertised peer surfaces as a resolved DiscoveryEvent`() =
        runBlocking {
            val nsd = FakeNsd()
            val discovery =
                Discovery.forTesting(
                    registrar = nsd.registrar,
                    browser = nsd.browser,
                )

            val endpointInfo = sampleEndpointInfo()
            val handle = discovery.advertise(endpointInfo, port = 30_001)
            try {
                val resolved: DiscoveryEvent.Resolved =
                    withTimeout(5_000L) {
                        discovery.browse().first { it is DiscoveryEvent.Resolved } as DiscoveryEvent.Resolved
                    }
                assertThat(resolved.service.instanceName).isEqualTo(handle.instanceName)
                assertThat(resolved.service.port).isEqualTo(30_001)
                assertThat(resolved.service.endpointInfo).isEqualTo(endpointInfo)
            } finally {
                handle.close()
            }
        }

    @Test
    fun `closing the advertise handle emits a Lost event`() =
        runBlocking {
            val nsd = FakeNsd()
            val discovery =
                Discovery.forTesting(
                    registrar = nsd.registrar,
                    browser = nsd.browser,
                )

            val handle = discovery.advertise(sampleEndpointInfo(), port = 30_002)
            // Pipe events through a channel so the test can advance the
            // collector deterministically: pull the first Resolved
            // event, then close, then pull the Lost event. Using one
            // long-lived flow collection avoids the race where a
            // second browse() call would re-subscribe after the
            // unpublish broadcast already fired.
            val events = kotlinx.coroutines.channels.Channel<DiscoveryEvent>(capacity = 16)
            val collectorJob =
                this.launch {
                    discovery.browse().collect { events.send(it) }
                }
            try {
                withTimeout(10_000L) {
                    val first = events.receive()
                    assertThat(first).isInstanceOf(DiscoveryEvent.Resolved::class.java)
                    assertThat((first as DiscoveryEvent.Resolved).service.instanceName)
                        .isEqualTo(handle.instanceName)
                    handle.close()
                    val second = events.receive()
                    assertThat(second).isInstanceOf(DiscoveryEvent.Lost::class.java)
                    assertThat((second as DiscoveryEvent.Lost).instanceName)
                        .isEqualTo(handle.instanceName)
                }
            } finally {
                collectorJob.cancel()
                events.close()
            }
        }

    @Test
    fun `Found event is recorded in diagnostics before Resolved`() =
        runBlocking {
            val nsd = FakeNsd()
            val discovery =
                Discovery.forTesting(
                    registrar = nsd.registrar,
                    browser = nsd.browser,
                )
            val handle = discovery.advertise(sampleEndpointInfo(), port = 30_003)
            try {
                withTimeout(5_000L) {
                    discovery.browse().first { it is DiscoveryEvent.Resolved }
                }
                val snapshot = discovery.snapshot()
                val kinds = snapshot.recentEvents.map { it.kind }
                // The Resolved Discovery event implies both Found and
                // Resolved diagnostic events were recorded.
                assertThat(kinds).contains(DiagnosticEvent.Kind.ADDED)
                assertThat(kinds).contains(DiagnosticEvent.Kind.RESOLVED)
                // Order must be ADDED before RESOLVED for the same name.
                val firstAdded = kinds.indexOf(DiagnosticEvent.Kind.ADDED)
                val firstResolved = kinds.indexOf(DiagnosticEvent.Kind.RESOLVED)
                assertThat(firstAdded).isLessThan(firstResolved)
            } finally {
                handle.close()
            }
        }

    private fun sampleEndpointInfo(): EndpointInfo =
        EndpointInfo(
            version = 1,
            hidden = false,
            deviceType = DeviceType.PHONE,
            reserved = false,
            // Stress every byte position with a non-trivial pattern.
            metadata = ByteArray(EndpointInfo.METADATA_LEN) { (it * 31 + 7).toByte() },
            deviceName = "Browse Test",
            tlvRecords = emptyList(),
        )
}
