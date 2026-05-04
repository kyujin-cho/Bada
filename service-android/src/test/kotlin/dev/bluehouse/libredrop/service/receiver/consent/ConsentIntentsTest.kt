/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.service.receiver.consent

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-JVM tests for [ConsentIntents.parsePayload].
 *
 * The parser is the entire wire surface between the notification's
 * `PendingIntent.getBroadcast` action and the
 * [ConsentBroadcastReceiver]. Pinning these semantics keeps a
 * future refactor of action strings / extra names from silently
 * breaking the consent path.
 *
 * The tests use the [ConsentIntents.IntentLike] indirection rather
 * than a real Android [android.content.Intent] so the file stays on
 * plain Junit5.
 */
class ConsentIntentsTest {
    @Test
    fun `accept action with valid id parses to ACCEPT decision`() {
        val payload = ConsentIntents.parsePayload(intent(ConsentIntents.ACTION_ACCEPT, 1L))
        assertThat(payload).isNotNull()
        assertThat(payload!!.decision).isEqualTo(ConsentIntents.Decision.ACCEPT)
        assertThat(payload.connectionId).isEqualTo(1L)
        assertThat(payload.accepted).isTrue()
    }

    @Test
    fun `reject action with valid id parses to REJECT decision`() {
        val payload = ConsentIntents.parsePayload(intent(ConsentIntents.ACTION_REJECT, 42L))
        assertThat(payload).isNotNull()
        assertThat(payload!!.decision).isEqualTo(ConsentIntents.Decision.REJECT)
        assertThat(payload.accepted).isFalse()
    }

    @Test
    fun `unknown action returns null payload`() {
        val payload = ConsentIntents.parsePayload(intent("io.github.unknown.OTHER", 1L))
        assertThat(payload).isNull()
    }

    @Test
    fun `null action returns null payload`() {
        val payload = ConsentIntents.parsePayload(intent(action = null, id = 1L))
        assertThat(payload).isNull()
    }

    @Test
    fun `missing connection id extra returns null payload`() {
        // Sentinel value: intent with the action but no extra. The
        // parser must distinguish "no extra" from "extra value
        // happens to equal MISSING_CONNECTION_ID" — the only signal
        // is the default returned by getLongExtra.
        val payload =
            ConsentIntents.parsePayload(
                intent(action = ConsentIntents.ACTION_ACCEPT, id = ConsentIntents.MISSING_CONNECTION_ID),
            )
        assertThat(payload).isNull()
    }

    @Test
    fun `accept action with show consent action returns null`() {
        // ACTION_SHOW_CONSENT is an activity action, not a broadcast
        // action; it must not be routed to the broadcast receiver
        // through parsePayload.
        val payload =
            ConsentIntents.parsePayload(intent(ConsentIntents.ACTION_SHOW_CONSENT, 1L))
        assertThat(payload).isNull()
    }

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
