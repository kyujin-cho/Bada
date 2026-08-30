/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver.consent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.bluehouse.bada.service.R

/**
 * Notification builder + channel installer for the consent prompt.
 *
 * ### Two layers, one notification
 *
 * The consent UX is two-layered (#22, reshaped in #279):
 *
 *  1. **High-importance heads-up notification** — a PLAIN banner
 *     (title + payload summary, no action buttons): it announces the
 *     incoming transfer and routes every interaction to layer 2.
 *  2. **Consent bottom sheet** (the trampoline activity) — opened by
 *     tapping the notification body, or raised directly via the
 *     full-screen intent when the device is locked / screen-off. It
 *     shows the full transfer details (file list, PIN) and hosts the
 *     Accept / Decline decision, which it dispatches through
 *     [ConsentBroadcastReceiver].
 *
 * Both layers share the same [ConsentRegistry] entry, so whichever
 * surface resolves the prompt, the resulting `submitUserConsent`
 * lands on the same connection.
 *
 * ### Channel design
 *
 * The channel id `incoming_transfer` is separate from the
 * persistent-foreground-service channel
 * [dev.bluehouse.bada.service.receiver.ReceiverNotification.CHANNEL_ID].
 * `IMPORTANCE_HIGH` so the notification peeks (heads-up), plays a
 * sound, and is unmissable on a busy device. The user can downgrade
 * the channel via system settings if they ever need to — but the
 * default has to be loud, because a missed consent prompt looks like
 * a hung sender to the peer.
 *
 * ### Per-pending notification id
 *
 * Each pending consent gets its own notification id derived from the
 * `connectionId` so multiple in-flight transfers each get their own
 * heads-up — re-using a single id would collapse them, hiding all
 * but the most recent. The id is stable for the lifetime of one
 * connection so the dismiss path (after consent or terminal state)
 * matches the post path.
 */
public object ConsentNotification {
    /**
     * Channel id for the consent prompt. Stable across upgrades so
     * historical user-visible channel customisations (mute, dismiss
     * behaviour) survive an app update.
     */
    public const val CHANNEL_ID: String = "incoming_transfer"

    /**
     * Mask applied to a connection id to derive a positive Android
     * notification id. The receiver-foreground notification id
     * (`ReceiverNotification.NOTIFICATION_ID`) is `0x4C424452`
     * ("LBDR"), which is well outside this range, so the foreground
     * notification cannot be accidentally cancelled by the consent
     * dismiss path.
     *
     * `connectionId` itself is a monotonically-incrementing `Long`
     * that starts at 1; we keep the low 31 bits and bias by a fixed
     * offset to reserve a contiguous range for consent notifications.
     */
    internal const val CONSENT_ID_BASE: Int = 0x6357_5663 // "cWVc"

    /**
     * Stable Android notification id for the consent notification of a
     * given connection. Same input always yields the same id, so the
     * post / dismiss / re-post calls all target the same notification
     * slot in the system shade.
     */
    public fun stableNotificationIdFor(connectionId: Long): Int {
        // Fold the connection id into 31 bits and offset away from the
        // foreground-service notification id range. The fold preserves
        // the low bits so a fresh process's first transfer (id=1) gets
        // a deterministic notification id useful for log correlation.
        val low31 = (connectionId and POSITIVE_INT_MASK_LONG).toInt()
        // Bias by the base in such a way that overflow stays inside
        // the positive int range (Notification ids must be != 0; any
        // non-zero int is otherwise legal).
        var biased = (CONSENT_ID_BASE + low31) and POSITIVE_INT_MASK
        if (biased == 0) biased = 1
        return biased
    }

    /**
     * Idempotently install the consent notification channel on API 26+.
     *
     * Pre-26 devices ignore notification channels entirely; we still
     * post the notification using `NotificationCompat`, which silently
     * degrades the channel-related fields on older platforms (and the
     * notification is delivered with the platform default behaviour).
     *
     * The channel is created with `IMPORTANCE_HIGH` so the
     * notification peeks. `setSound(null, null)` is **not** applied —
     * the consent prompt is exactly the kind of notification that
     * should make a sound, the same way an incoming call does.
     */
    public fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.consent_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.consent_notification_channel_description)
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
            }
        manager.createNotificationChannel(channel)
    }

    /**
     * Build the consent notification for a pending registry entry.
     *
     * Construction is delegated to a pure-JVM
     * [ConsentNotificationContent] data object so the textual content
     * (title, body, action labels, PIN string) can be unit-tested
     * without instantiating a real `NotificationCompat.Builder`.
     *
     * @param context Used for string lookup and as the
     *   `NotificationCompat.Builder` context.
     * @param connectionId Stable id of the in-flight transfer.
     * @param entry Snapshot of the registry entry — the consent UI
     *   reads device name, item count, total size, PIN from here.
     * @param trampolineTarget Activity class to open when the user
     *   taps the notification body. Constructed by the foreground
     *   service so this builder does not need a static dependency on
     *   `:app`.
     */
    public fun build(
        context: Context,
        connectionId: Long,
        entry: ConsentRegistry.Entry,
        trampolineTarget: Class<*>?,
    ): Notification {
        val content = ConsentNotificationContent.from(context.resources, entry)

        val tapIntent =
            if (trampolineTarget != null) {
                PendingIntent.getActivity(
                    context,
                    contentRequestCodeFor(connectionId),
                    Intent(context, trampolineTarget).apply {
                        action = ConsentIntents.ACTION_SHOW_CONSENT
                        putExtra(ConsentIntents.EXTRA_CONNECTION_ID, connectionId)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                null
            }

        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(content.title)
                .setContentText(content.body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                // Tints the small-icon circle brand blue — same accent the
                // consent sheet uses for its PIN chip and Accept button.
                .setColor(ContextCompat.getColor(context, R.color.consent_accent))
                // ALWAYS ongoing: the consent prompt is the only surface that
                // resolves the peer's pending request, and there is no
                // deleteIntent / re-post path — a swipe-dismiss would silently
                // strand the sender until timeout. Keeping it non-dismissible
                // on every path guarantees the prompt survives a stray swipe
                // on an unlocked device.
                .setOngoing(true)
                .setAutoCancel(false)
                .setShowWhen(true)

        // The notification is deliberately PLAIN — title + summary only,
        // no action buttons, no PIN, no custom RemoteViews body. Two
        // richer presentations were tried and both failed on OEM shades
        // (vivo / OriginOS, #279): a DecoratedCustomViewStyle body with
        // in-layout Accept/Decline buttons gets its collapsed height
        // hard-capped so the button row renders clipped, and a CallStyle
        // upgrade — the platform's own always-visible-actions template —
        // still came up with the actions cut off. Android offers no
        // public API to post a notification pre-expanded (expansion is
        // SystemUI's call), so the decision UI moved off the notification
        // entirely: the heads-up is a plain banner, and tapping it (or
        // the lock-screen full-screen intent below) opens the bottom-
        // sheet consent surface where Accept / Decline live.

        if (tapIntent != null) {
            // Tapping the notification body opens the consent sheet.
            builder.setContentIntent(tapIntent)
            // Full-screen intent on EVERY post, not just locked / screen-off
            // (#279 reversed the earlier keyguard gating): the consent sheet
            // should open BY ITSELF the moment a transfer arrives, the way
            // stock Quick Share raises its receive prompt, with the
            // notification landing alongside it in the shade. The FSI is the
            // only sanctioned background-activity-launch path for that.
            // Platform behaviour varies by state and skin — locked /
            // screen-off raises the sheet over the keyguard everywhere;
            // unlocked-and-in-use auto-opens on skins that honour the FSI
            // live (vivo / OriginOS — previously treated as misbehaviour
            // when the surface was a jarring full dialog, now the point:
            // the sheet is a light translucent overlay in its own task),
            // while stock Android degrades to the heads-up banner whose tap
            // opens the same sheet. Android 14+ additionally gates FSI
            // behind the user-manageable special permission; when revoked
            // the platform silently falls back to the banner, which is the
            // same acceptable degradation.
            builder.setFullScreenIntent(tapIntent, true)
        }

        return builder.build()
    }

    /**
     * Post the consent notification for [connectionId] using the given
     * [entry] context. Idempotent: re-posting under the same id replaces
     * the prior notification.
     *
     * Returns the notification id used so callers (foreground service,
     * tests) can correlate.
     *
     * [diagnostic] records the post attempt with the two states that make
     * `NotificationManager.notify` a SILENT no-op — app-level
     * notifications disabled, and the effective channel importance —
     * plus the notify outcome. Field evidence for #263: on a vivo
     * (OriginOS) receiver the consent notification never appeared in
     * `dumpsys notification` while this channel was registered at
     * IMPORTANCE_HIGH, and without this line there is no way to tell an
     * app-side skipped post from an OEM-suppressed one.
     */
    public fun post(
        context: Context,
        connectionId: Long,
        entry: ConsentRegistry.Entry,
        trampolineTarget: Class<*>?,
        diagnostic: (String) -> Unit = {},
    ): Int {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager == null) {
            diagnostic("notification post id=- result=no-notification-manager")
            return -1
        }
        val id = stableNotificationIdFor(connectionId)
        val outcome =
            runCatching { manager.notify(id, build(context, connectionId, entry, trampolineTarget)) }
        diagnostic(
            "notification post id=$id " +
                "appEnabled=${manager.areNotificationsEnabled()} " +
                "channelImportance=${manager.getNotificationChannel(CHANNEL_ID)?.importance} " +
                "result=${outcome.fold({ "ok" }, { e -> "${e::class.simpleName}: ${e.message}" })}",
        )
        // Keep the pre-instrumentation contract: a throwing notify still
        // propagates to the caller after the evidence is written.
        outcome.getOrThrow()
        return id
    }

    /**
     * Dismiss the consent notification for [connectionId]. Safe to call
     * before [post] — the platform `NotificationManager.cancel` is a
     * no-op for unknown ids.
     */
    public fun dismiss(
        context: Context,
        connectionId: Long,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(stableNotificationIdFor(connectionId))
    }

    /**
     * `PendingIntent` request code namespace for the content-tap intent.
     * The stride/offset scheme predates the removal of the notification's
     * Accept / Reject broadcast actions (their decision surface moved to
     * the consent bottom sheet); the stride is kept so the content-tap
     * request codes stay identical across the change and a pending
     * intent posted by an older build cannot collide with a new one.
     * Folded into 31 bits because request codes must be int.
     */
    private fun contentRequestCodeFor(connectionId: Long): Int =
        ((connectionId * REQUEST_CODE_STRIDE + CONTENT_OFFSET) and POSITIVE_INT_MASK_LONG).toInt()

    /** Mask that strips the sign bit for use as a positive int notification id. */
    private const val POSITIVE_INT_MASK: Int = 0x7FFF_FFFF
    private const val POSITIVE_INT_MASK_LONG: Long = 0x7FFF_FFFFL

    private const val REQUEST_CODE_STRIDE = 3L
    private const val CONTENT_OFFSET = 2L
}
