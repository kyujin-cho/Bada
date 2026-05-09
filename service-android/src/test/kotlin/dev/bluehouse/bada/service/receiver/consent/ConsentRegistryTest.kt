/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver.consent

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.bada.protocol.connection.InboundConnection
import org.junit.jupiter.api.Test
import java.net.Socket

/**
 * Pure-JVM tests for [ConsentRegistry].
 *
 * The registry is the rendezvous between the consent notification
 * surface and the `BroadcastReceiver` that fires `submitUserConsent`,
 * so the contract worth pinning here is the basic register / lookup /
 * unregister set-semantics plus the snapshot helper.
 *
 * No real `InboundConnection` lifecycle is exercised — we just need an
 * unstarted instance for identity comparisons. The connection's
 * `submitUserConsent` is a no-op when the channel is unstarted, so
 * accidentally invoking it on a registry-resident connection in a
 * later test would still be safe.
 */
class ConsentRegistryTest {
    @Test
    fun `register stores entries that can be looked up by id`() {
        val registry = ConsentRegistry()
        val entry = sampleEntry(deviceName = "Pixel 8", pin = "1234")

        registry.register(connectionId = 7, entry = entry)

        assertThat(registry.lookup(7)).isEqualTo(entry)
        assertThat(registry.lookup(8)).isNull()
    }

    @Test
    fun `register replaces a prior entry under the same id and returns the previous one`() {
        val registry = ConsentRegistry()
        val first = sampleEntry(pin = "1111")
        val second = sampleEntry(pin = "2222")

        assertThat(registry.register(connectionId = 1, entry = first)).isNull()
        val replaced = registry.register(connectionId = 1, entry = second)

        assertThat(replaced).isEqualTo(first)
        assertThat(registry.lookup(1)).isEqualTo(second)
    }

    @Test
    fun `unregister removes the entry and returns it`() {
        val registry = ConsentRegistry()
        val entry = sampleEntry()
        registry.register(connectionId = 42, entry = entry)

        val removed = registry.unregister(42)

        assertThat(removed).isEqualTo(entry)
        assertThat(registry.lookup(42)).isNull()
    }

    @Test
    fun `unregister on an unknown id returns null without raising`() {
        val registry = ConsentRegistry()
        assertThat(registry.unregister(0xDEAD_BEEF)).isNull()
    }

    @Test
    fun `snapshotIds returns a defensive copy of all registered ids`() {
        val registry = ConsentRegistry()
        registry.register(1, sampleEntry())
        registry.register(2, sampleEntry())
        registry.register(3, sampleEntry())

        val snapshot = registry.snapshotIds()

        assertThat(snapshot).containsExactly(1L, 2L, 3L)
        // Mutating the registry must not mutate the snapshot.
        registry.unregister(2)
        assertThat(snapshot).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun `singleton instance is process-wide and shared across lookups`() {
        // The instance is a public field; this test pins that it is
        // genuinely process-wide rather than a per-lookup factory.
        assertThat(ConsentRegistry.instance).isSameInstanceAs(ConsentRegistry.instance)
    }

    private fun sampleEntry(
        deviceName: String? = "Test Phone",
        pin: String = "9999",
        itemCount: Int = 1,
        totalSize: Long = 1024L,
    ): ConsentRegistry.Entry =
        ConsentRegistry.Entry(
            connection = InboundConnection(socket = Socket()),
            sourceDeviceName = deviceName,
            pin = pin,
            itemCount = itemCount,
            totalSize = totalSize,
        )
}
