/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.kyujincho.wvmg.service.R

/**
 * Notification channel + builder for the persistent foreground notification
 * the receiver service posts while listening for incoming Quick Share
 * transfers.
 *
 * Why an `IMPORTANCE_LOW` channel: the foreground notification is
 * informational, not actionable. We do not want it to make a sound, vibrate,
 * or peek over other UI; the user already opted in to "stay reachable as a
 * Quick Share target" by starting the service. Persistent low-priority is
 * the same pattern Google's Files / Quick Share uses for its long-running
 * background presence.
 *
 * The channel is created idempotently — the system tolerates re-creation
 * with the same id, but we still gate on API 26+ where channels exist.
 */
internal object ReceiverNotification {
    /**
     * Channel id used by the foreground notification. Stable across
     * upgrades so historical user-visible channel customizations
     * (notification settings page) survive an app update.
     */
    internal const val CHANNEL_ID: String = "wvmg.receiver.foreground"

    /**
     * Notification id used by `Service.startForeground`. Any positive
     * non-zero value works; this constant just keeps the value out of
     * the body of the service.
     */
    internal const val NOTIFICATION_ID: Int = 0x57_56_4D_47 and 0x7F_FF_FF_FF // "WVMG"

    /**
     * Idempotently install the notification channel on API 26+. Pre-API-26
     * devices ignore notification channels entirely; we still post the
     * notification using `NotificationCompat`, which silently degrades
     * the channel-related fields on older platforms.
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Re-creating a channel with the same id is a no-op on the system
        // side, so we don't need to check for an existing channel first.
        // The user-visible importance can only be lowered by us after
        // initial creation; raising it requires user action, matching
        // platform policy.
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.receiver_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.receiver_notification_channel_description)
                setShowBadge(false)
                // No sound, no vibration, no lights -- this is a silent
                // status indicator, not a notification the user should
                // ever be interrupted by.
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
        manager.createNotificationChannel(channel)
    }

    /**
     * Build the persistent foreground notification.
     *
     * @param context the service / app context.
     * @param contentIntent a `PendingIntent` opening the app when the
     *   notification is tapped. Constructed by the service so the
     *   builder does not need to know about `MainActivity`.
     * @param ssid the stripped Wi-Fi SSID to surface in the body, or
     *   `null` when unavailable. The same-Wi-Fi-network UX (#85)
     *   appends `Receiving on "<SSID>"` so the user can verify both
     *   ends match without leaving the app; passing `null` falls back
     *   to a generic "Receiving on this network" copy.
     */
    fun build(
        context: Context,
        contentIntent: PendingIntent?,
        ssid: String? = null,
    ): Notification =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.receiver_notification_title))
            .setContentText(buildContentText(context, ssid))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Foreground-service notifications are inherently ongoing;
            // setting it explicitly makes the swipe-to-dismiss behavior
            // consistent across launchers.
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .build()

    /**
     * Pick the notification body string given the current SSID lookup
     * result. A non-null, non-blank SSID renders as
     * `Receiving on "<SSID>"`; everything else falls back to the
     * generic "Receiving on this network" string. Public for unit-test
     * coverage of the per-state body selection.
     */
    internal fun buildContentText(
        context: Context,
        ssid: String?,
    ): String =
        if (ssid.isNullOrBlank()) {
            context.getString(R.string.receiver_notification_text_unknown_ssid)
        } else {
            context.getString(R.string.receiver_notification_text_with_ssid, ssid)
        }

    /**
     * Build a `PendingIntent` that opens the supplied [target] activity.
     * Returns `null` when [target] is `null`, which the service uses on
     * the rare path where it cannot resolve `MainActivity` (e.g.
     * stripped-down test app).
     *
     * `FLAG_IMMUTABLE` is mandatory on API 31+ and recommended elsewhere;
     * we always set it because the intent doesn't need to be mutated
     * after creation.
     */
    fun buildOpenAppIntent(
        context: Context,
        target: Class<*>?,
    ): PendingIntent? {
        if (target == null) return null
        val intent =
            Intent(context, target).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        // FLAG_IMMUTABLE was introduced in API 23. The :service-android
        // module's minSdk is 24 (see libs.versions.toml), so the flag is
        // always available — no SDK_INT branch needed.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, OPEN_APP_REQUEST_CODE, intent, flags)
    }

    private const val OPEN_APP_REQUEST_CODE = 0
}
