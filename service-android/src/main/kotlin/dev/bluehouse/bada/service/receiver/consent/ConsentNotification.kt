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
import dev.bluehouse.bada.service.R

/**
 * Notification builder + channel installer for the consent prompt.
 *
 * ### Two layers, one notification
 *
 * The issue calls for a two-layered consent UX (#22):
 *
 *  1. **High-importance heads-up notification** with Accept / Reject
 *     `Notification.Action`s — fires through
 *     [ConsentBroadcastReceiver] so consent works even when the
 *     screen is off.
 *  2. **Trampoline activity** for users who tap the notification body
 *     to see file details. Same pattern as an incoming call: the
 *     activity uses `setShowWhenLocked(true) + setTurnScreenOn(true)`
 *     to wake the screen.
 *
 * Both layers share the same [ConsentRegistry] entry, so whichever
 * surface the user chooses, the resulting `submitUserConsent` lands
 * on the same connection.
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

        val acceptIntent =
            PendingIntent.getBroadcast(
                context,
                acceptRequestCodeFor(connectionId),
                consentBroadcastIntent(context, ConsentIntents.ACTION_ACCEPT, connectionId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val rejectIntent =
            PendingIntent.getBroadcast(
                context,
                rejectRequestCodeFor(connectionId),
                consentBroadcastIntent(context, ConsentIntents.ACTION_REJECT, connectionId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

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
                .setStyle(NotificationCompat.BigTextStyle().bigText(content.bigText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                // Dismissing the notification (swipe) does NOT auto-reject
                // — the peer is left waiting for an explicit decision via
                // the trampoline activity. This matches NearDrop behaviour
                // (issue #22 acceptance criteria).
                .setOngoing(true)
                .setAutoCancel(false)
                .setShowWhen(true)
                .addAction(
                    NotificationCompat.Action
                        .Builder(
                            android.R.drawable.ic_menu_send,
                            content.acceptLabel,
                            acceptIntent,
                        ).build(),
                ).addAction(
                    NotificationCompat.Action
                        .Builder(
                            android.R.drawable.ic_menu_close_clear_cancel,
                            content.rejectLabel,
                            rejectIntent,
                        ).build(),
                )

        if (tapIntent != null) {
            builder
                .setContentIntent(tapIntent)
                // Make sure the heads-up tap routes to the trampoline
                // activity rather than just expanding the shade. The
                // full-screen-intent path is what wakes the device on
                // API 27+; the trampoline activity itself handles the
                // setShowWhenLocked / setTurnScreenOn flags.
                // highPriority = true so API 29+ surfaces the activity
                // immediately rather than collapsing the heads-up.
                .setFullScreenIntent(tapIntent, true)
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
     */
    public fun post(
        context: Context,
        connectionId: Long,
        entry: ConsentRegistry.Entry,
        trampolineTarget: Class<*>?,
    ): Int {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return -1
        val id = stableNotificationIdFor(connectionId)
        manager.notify(id, build(context, connectionId, entry, trampolineTarget))
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

    private fun consentBroadcastIntent(
        context: Context,
        action: String,
        connectionId: Long,
    ): Intent =
        Intent(action).apply {
            // Dispatch via setPackage + action only. ConsentBroadcastReceiver
            // is registered dynamically by ReceiverForegroundService with
            // RECEIVER_NOT_EXPORTED — there is no manifest entry, so an
            // explicit setClass(receiver::class) component reference does
            // not resolve at the BroadcastQueue and the broadcast is
            // silently dropped (observed on Vivo Funtouch 16 during the
            // #151 hardware loop, and on OnePlus 15 / Android 16 / ColorOS
            // 16 where the strict broadcast routing also drops it).
            // setPackage(packageName) is enough to keep delivery inside
            // our process; the dynamic receiver's IntentFilter is the
            // only listener for ACCEPT / REJECT in the package.
            setPackage(context.packageName)
            putExtra(ConsentIntents.EXTRA_CONNECTION_ID, connectionId)
        }

    /**
     * `PendingIntent` request code namespaces. Three slots per
     * connection (accept / reject / content tap) so updating one does
     * not implicitly mutate another. Folded into 31 bits because
     * `getBroadcast` request codes must be int.
     */
    private fun acceptRequestCodeFor(connectionId: Long): Int =
        ((connectionId * REQUEST_CODE_STRIDE + ACCEPT_OFFSET) and POSITIVE_INT_MASK_LONG).toInt()

    private fun rejectRequestCodeFor(connectionId: Long): Int =
        ((connectionId * REQUEST_CODE_STRIDE + REJECT_OFFSET) and POSITIVE_INT_MASK_LONG).toInt()

    private fun contentRequestCodeFor(connectionId: Long): Int =
        ((connectionId * REQUEST_CODE_STRIDE + CONTENT_OFFSET) and POSITIVE_INT_MASK_LONG).toInt()

    /** Mask that strips the sign bit for use as a positive int notification id. */
    private const val POSITIVE_INT_MASK: Int = 0x7FFF_FFFF
    private const val POSITIVE_INT_MASK_LONG: Long = 0x7FFF_FFFFL

    private const val REQUEST_CODE_STRIDE = 3L
    private const val ACCEPT_OFFSET = 0L
    private const val REJECT_OFFSET = 1L
    private const val CONTENT_OFFSET = 2L
}
