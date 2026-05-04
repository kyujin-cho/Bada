/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.service.receiver.consent

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.libredrop.protocol.connection.InboundConnection
import org.junit.jupiter.api.Test
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure-JVM tests for [ConsentRouter.dispatch].
 *
 * The router is the bridge between the parsed
 * [ConsentIntents.Payload] and the live
 * [dev.bluehouse.libredrop.protocol.connection.InboundConnection] in
 * [ConsentRegistry]. The full lifecycle of an `InboundConnection` is
 * exercised in `:core-protocol`'s tests; here we only need to
 * confirm:
 *
 *  1. Accept / Reject on a registered id reaches submitConsent with
 *     the correct boolean.
 *  2. The registry entry is removed after dispatch so a duplicate
 *     broadcast is a no-op.
 *  3. Unknown ids and unknown actions silently drop.
 *  4. The optional onConsentSubmitted hook fires exactly once on
 *     successful dispatch.
 *
 * The router consults `Entry.submitConsent` (a `(Boolean) -> Unit`
 * lambda) rather than `entry.connection.submitUserConsent` directly,
 * so we inject a recording lambda — `InboundConnection` itself stays
 * a real (unstarted) instance for identity assertions.
 */
class ConsentRouterTest {
    @Test
    fun `accept dispatches submitConsent(true) and unregisters`() {
        val registry = ConsentRegistry()
        val recorded = mutableListOf<Boolean>()
        registry.register(connectionId = 5, entry = sampleEntry(submit = recorded::add))
        val hookFires = AtomicInteger(0)

        ConsentRouter.dispatch(
            intent = intent(ConsentIntents.ACTION_ACCEPT, 5L),
            registry = registry,
            onConsentSubmitted = { hookFires.incrementAndGet() },
        )

        assertThat(recorded).containsExactly(true)
        assertThat(registry.lookup(5)).isNull()
        assertThat(hookFires.get()).isEqualTo(1)
    }

    @Test
    fun `reject dispatches submitConsent(false) and unregisters`() {
        val registry = ConsentRegistry()
        val recorded = mutableListOf<Boolean>()
        registry.register(connectionId = 5, entry = sampleEntry(submit = recorded::add))

        ConsentRouter.dispatch(
            intent = intent(ConsentIntents.ACTION_REJECT, 5L),
            registry = registry,
        )

        assertThat(recorded).containsExactly(false)
        assertThat(registry.lookup(5)).isNull()
    }

    @Test
    fun `dispatch on an unregistered id is a no-op`() {
        val registry = ConsentRegistry()
        val hookFires = AtomicInteger(0)

        ConsentRouter.dispatch(
            intent = intent(ConsentIntents.ACTION_ACCEPT, 99L),
            registry = registry,
            onConsentSubmitted = { hookFires.incrementAndGet() },
        )

        assertThat(hookFires.get()).isEqualTo(0)
    }

    @Test
    fun `dispatch with unrecognised action is a no-op`() {
        val registry = ConsentRegistry()
        val recorded = mutableListOf<Boolean>()
        registry.register(connectionId = 1, entry = sampleEntry(submit = recorded::add))

        ConsentRouter.dispatch(
            intent = intent("io.github.bogus", 1L),
            registry = registry,
        )

        // No consent submitted, registration intact.
        assertThat(recorded).isEmpty()
        assertThat(registry.lookup(1)).isNotNull()
    }

    @Test
    fun `duplicate dispatch is filtered after the first call unregisters`() {
        // The classic stale-PendingIntent scenario: the user
        // double-taps the action button before the system can
        // dismiss it. We expect exactly one submitConsent call.
        val registry = ConsentRegistry()
        val recorded = mutableListOf<Boolean>()
        registry.register(connectionId = 7, entry = sampleEntry(submit = recorded::add))

        ConsentRouter.dispatch(intent(ConsentIntents.ACTION_ACCEPT, 7L), registry)
        ConsentRouter.dispatch(intent(ConsentIntents.ACTION_ACCEPT, 7L), registry)

        assertThat(recorded).containsExactly(true)
    }

    private fun sampleEntry(submit: (Boolean) -> Unit): ConsentRegistry.Entry =
        ConsentRegistry.Entry(
            connection = InboundConnection(socket = Socket()),
            sourceDeviceName = "Test",
            pin = "1234",
            itemCount = 1,
            totalSize = 1L,
            submitConsent = submit,
        )

    private fun intent(
        action: String?,
        id: Long,
    ): ConsentIntents.IntentLike =
        object : ConsentIntents.IntentLike {
            override val action: String? = action

            override fun getLongExtra(
                name: String,
                default: Long,
            ): Long =
                if (name == ConsentIntents.EXTRA_CONNECTION_ID) {
                    id
                } else {
                    default
                }
        }
}
