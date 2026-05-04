/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.service.receiver.consent

/**
 * Pure-JVM dispatcher tying a parsed consent
 * [ConsentIntents.Payload] to the live
 * [dev.bluehouse.libredrop.protocol.connection.InboundConnection] in
 * the [ConsentRegistry].
 *
 * Lifted out of [ConsentBroadcastReceiver] so unit tests can validate
 * the routing semantics — "Accept on a registered id flips the
 * decision through to submitUserConsent(true) and unregisters" — on a
 * plain JVM with no `Intent`, no `Context`, no Robolectric.
 */
public object ConsentRouter {
    /**
     * Decode the intent and, if it carries a recognised consent
     * payload that targets a live registered connection, deliver the
     * decision to that connection.
     *
     * The decision is propagated through the same `submitUserConsent`
     * API the in-app consent dialog uses (#22's trampoline activity),
     * so the FSM treats Accept / Reject identically regardless of
     * which UI surfaced it.
     *
     * After delivering consent, the registration is removed so a
     * re-fired stale `PendingIntent` (e.g. user double-tapped the
     * notification action before the system could dismiss it) is a
     * no-op rather than a duplicate `submitUserConsent`.
     *
     * @param intent An [ConsentIntents.IntentLike] view of the
     *   broadcast intent. Production passes
     *   `ConsentIntents.IntentLike.from(intent)`; tests pass a
     *   fake.
     * @param registry The [ConsentRegistry] to look up the connection
     *   in. Production passes [ConsentRegistry.instance]; tests pass
     *   a fresh registry.
     * @param onConsentSubmitted Optional hook fired once the
     *   `submitUserConsent` call returns, with the connection id.
     *   Production uses this to dismiss the notification; tests use
     *   it to assert the dispatch fired.
     */
    public fun dispatch(
        intent: ConsentIntents.IntentLike,
        registry: ConsentRegistry,
        onConsentSubmitted: (connectionId: Long) -> Unit = {},
    ) {
        val payload = ConsentIntents.parsePayload(intent) ?: return
        val entry = registry.unregister(payload.connectionId) ?: return
        entry.submitConsent(payload.accepted)
        onConsentSubmitted(payload.connectionId)
    }
}
