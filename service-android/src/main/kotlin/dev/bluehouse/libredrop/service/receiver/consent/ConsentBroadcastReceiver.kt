/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.service.receiver.consent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.bluehouse.libredrop.service.receiver.progress.TransferCancelRegistry
import dev.bluehouse.libredrop.service.receiver.progress.TransferProgressNotification

/**
 * Broadcast receiver that translates an Accept / Reject notification
 * action into a call to
 * [dev.bluehouse.libredrop.protocol.connection.InboundConnection.submitUserConsent].
 *
 * ### Why a `BroadcastReceiver`
 *
 * The consent action must work even when the screen is off and the
 * launcher has killed the app's activities. `Notification.Action`s
 * with `PendingIntent.getBroadcast(...)` are the documented Android
 * way to deliver the user's input straight to a process-resident
 * receiver without going through the activity stack: the system
 * holds the foreground service alive long enough for the receiver to
 * run.
 *
 * ### Lifecycle
 *
 * Android instantiates this class fresh every time it delivers an
 * intent — `onReceive` runs on the main thread and must finish in
 * under 10 seconds (we finish in microseconds). Because the receiver
 * has no constructor injection point, it consults
 * [ConsentRegistry.instance] for the live connection. The foreground
 * service is responsible for keeping the registry populated.
 *
 * The actual `submitUserConsent` call is non-blocking — the
 * [dev.bluehouse.libredrop.protocol.connection.InboundConnection]
 * implementation queues consent into an in-memory channel and returns
 * immediately, so we never need a `goAsync()`.
 *
 * ### Routing
 *
 * Routing is delegated to the pure-JVM
 * [ConsentRouter] so the wire-decoding logic can be unit-tested
 * without Robolectric. This class is the thin Android-side adapter:
 * grab the intent, hand it to the router, fire any side-effects the
 * router asks for, return.
 */
public class ConsentBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context?,
        intent: Intent?,
    ) {
        if (intent == null) return
        val ctx = context
        when (intent.action) {
            ConsentIntents.ACTION_CANCEL_TRANSFER -> handleCancelTransfer(ctx, intent)
            else ->
                ConsentRouter.dispatch(
                    intent = ConsentIntents.IntentLike.from(intent),
                    registry = ConsentRegistry.instance,
                    onConsentSubmitted = { connectionId ->
                        // Best-effort dismissal of the notification once the
                        // user has acted on it. Without a Context we cannot
                        // reach the NotificationManager — that path is exercised
                        // when the broadcast was triggered programmatically by
                        // a test rather than through the system, so silently
                        // skipping is fine.
                        if (ctx != null) {
                            ConsentNotification.dismiss(ctx, connectionId)
                        }
                    },
                )
        }
    }

    /**
     * Translate a Cancel-button broadcast (from the in-flight
     * progress notification, #46) into a call on the registered
     * cancel callback. Idempotent: a stale broadcast for an already-
     * terminated transfer finds no entry in the registry and is
     * silently dropped.
     */
    private fun handleCancelTransfer(
        ctx: Context?,
        intent: Intent,
    ) {
        val connectionId =
            intent.getLongExtra(ConsentIntents.EXTRA_CONNECTION_ID, ConsentIntents.MISSING_CONNECTION_ID)
        if (connectionId == ConsentIntents.MISSING_CONNECTION_ID) return
        val callback = TransferCancelRegistry.instance.unregister(connectionId) ?: return
        callback.invoke()
        if (ctx != null) {
            // Dismiss the progress notification immediately. The
            // connection's terminal state will flow up through the
            // existing observer and trigger a redundant dismiss; that
            // call is a no-op on the now-unknown notification id.
            TransferProgressNotification.dismiss(ctx, connectionId)
        }
    }

    public companion object {
        /**
         * Public action filter the foreground service uses to register
         * this receiver dynamically. Static manifest registration is
         * not used because the registry is in-process state — a
         * receiver that survives process death would have no
         * connection to dispatch to anyway.
         */
        @JvmField
        public val ACTION_FILTER: Array<String> =
            arrayOf(
                ConsentIntents.ACTION_ACCEPT,
                ConsentIntents.ACTION_REJECT,
                ConsentIntents.ACTION_CANCEL_TRANSFER,
            )
    }
}
