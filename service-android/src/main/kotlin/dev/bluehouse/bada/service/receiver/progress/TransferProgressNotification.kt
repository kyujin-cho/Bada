/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver.progress

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.bluehouse.bada.protocol.connection.TransferProgress
import dev.bluehouse.bada.service.R
import dev.bluehouse.bada.service.receiver.consent.ConsentBroadcastReceiver
import dev.bluehouse.bada.service.receiver.consent.ConsentIntents

/**
 * Notification surface for an in-flight transfer (#46).
 *
 * Sits beside [dev.bluehouse.bada.service.receiver.consent.ConsentNotification]
 * in the consent / progress / completion notification trio:
 *
 *  - **Consent** (high-importance, channel `incoming_transfer`):
 *    surfaced when the receiver enters `WaitingForUserConsent`.
 *  - **Progress** (low-importance, this object's channel
 *    [CHANNEL_ID]): surfaced after the user accepts and the FSM
 *    enters `Receiving`. Updates every ~500 ms with the current
 *    progress percentage, smoothed bytes/sec rate, and ETA.
 *  - **Result** (informational; future work): a brief "delivered"
 *    or "failed" notification once the connection is terminal.
 *
 * Issue #46 acceptance criteria covered here:
 *
 *  - Foreground-service progress notification updates with
 *    percentage complete, transfer rate, and ETA.
 *  - Notification has a Cancel action that dispatches an
 *    [ConsentIntents.ACTION_CANCEL_TRANSFER] broadcast.
 *  - Accessibility-friendly text comes from
 *    [TransferProgressNotificationContent], which uses spelled-out
 *    durations ("30 seconds remaining") rather than abbreviations.
 *
 * ### Why a separate channel from the receiver-foreground notification
 *
 * The persistent foreground-service notification
 * ([dev.bluehouse.bada.service.receiver.ReceiverNotification])
 * is the device-discoverable indicator that lives across the entire
 * service lifetime; per-transfer progress notifications come and go
 * with each accepted connection. Splitting them onto separate
 * channels lets the user mute progress updates (channel
 * [CHANNEL_ID] silenced) without losing the discoverability
 * indicator, and vice versa.
 *
 * `IMPORTANCE_LOW` because progress updates should not peek over
 * other UI — the user already saw the consent prompt; they don't
 * need another sound for every percentage update.
 */
public object TransferProgressNotification {
    /** Channel id for in-flight transfer progress notifications. */
    public const val CHANNEL_ID: String = "transfer_progress"

    /**
     * Mask applied to fold a connection id into the Android positive
     * notification id range. The receiver-foreground notification id
     * is `0x4C42_4452` ("LBDR"); the consent notifications are biased
     * by `0x6357_5663` ("cWVc"); we bias progress notifications by
     * [PROGRESS_ID_BASE] so all three ranges stay disjoint.
     */
    internal const val PROGRESS_ID_BASE: Int = 0x7057_5663 // "pWVc"

    /**
     * Stable Android notification id for the progress notification of
     * a given connection. Same `connectionId` always yields the same
     * id, so post / update / dismiss calls all target the same
     * notification slot in the system shade.
     */
    public fun stableNotificationIdFor(connectionId: Long): Int {
        val low31 = (connectionId and POSITIVE_INT_MASK_LONG).toInt()
        var biased = (PROGRESS_ID_BASE + low31) and POSITIVE_INT_MASK
        if (biased == 0) biased = 1
        return biased
    }

    /**
     * Idempotently install the progress notification channel on API
     * 26+. Pre-26 devices ignore notification channels; we still post
     * via `NotificationCompat`, which silently degrades the
     * channel-related fields.
     */
    public fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.transfer_progress_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.transfer_progress_channel_description)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
        manager.createNotificationChannel(channel)
    }

    /**
     * Build the progress notification for [connectionId] with the
     * given [content]. The Cancel action wires a broadcast through
     * [ConsentBroadcastReceiver] so the cancel dispatches even when
     * the screen is off.
     */
    public fun build(
        context: Context,
        connectionId: Long,
        content: TransferProgressNotificationContent,
    ): Notification {
        val cancelIntent =
            PendingIntent.getBroadcast(
                context,
                cancelRequestCodeFor(connectionId),
                cancelBroadcastIntent(context, connectionId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(content.title)
                .setContentText(content.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content.bigText))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setSilent(true)
                .addAction(
                    NotificationCompat.Action
                        .Builder(
                            android.R.drawable.ic_menu_close_clear_cancel,
                            context.getString(R.string.transfer_progress_action_cancel),
                            cancelIntent,
                        ).build(),
                )

        if (content.progressIsDeterminate) {
            builder.setProgress(PERCENT_FULL, content.progressPercent, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    /**
     * Post (or update) the progress notification for [connectionId].
     * Idempotent: re-posting under the same id replaces the prior
     * notification without re-alerting the user (the channel is
     * `IMPORTANCE_LOW` and the builder calls `setOnlyAlertOnce`).
     *
     * Returns the notification id used so callers can correlate.
     */
    public fun post(
        context: Context,
        connectionId: Long,
        content: TransferProgressNotificationContent,
    ): Int {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return -1
        val id = stableNotificationIdFor(connectionId)
        manager.notify(id, build(context, connectionId, content))
        return id
    }

    /**
     * Dismiss the progress notification for [connectionId]. Safe to
     * call before [post] — the platform `cancel` is a no-op for
     * unknown ids.
     */
    public fun dismiss(
        context: Context,
        connectionId: Long,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(stableNotificationIdFor(connectionId))
    }

    /**
     * Build a [TextResolver][TransferProgressNotificationContent.TextResolver]
     * that resolves [TransferProgressNotificationContent.StringKey] values
     * against the supplied [context]'s string resources.
     */
    public fun textResolverFor(context: Context): TransferProgressNotificationContent.TextResolver =
        TransferProgressNotificationContent.TextResolver { key, args ->
            val resId = stringResIdFor(key)
            // The args array is forwarded to a varargs-accepting Java
            // API; the spread is unavoidable here because Kotlin does
            // not synthesise an Object[]-overload of getString.
            // Suppression keeps the call site clean.
            @Suppress("SpreadOperator")
            context.getString(resId, *args)
        }

    private fun stringResIdFor(key: TransferProgressNotificationContent.StringKey): Int =
        when (key) {
            TransferProgressNotificationContent.StringKey.TITLE_WITH_NAME ->
                R.string.transfer_progress_title_with_name
            TransferProgressNotificationContent.StringKey.TITLE_UNKNOWN_SENDER ->
                R.string.transfer_progress_title_unknown_sender
            TransferProgressNotificationContent.StringKey.BODY_SIZE ->
                R.string.transfer_progress_body_size
            TransferProgressNotificationContent.StringKey.BODY_RATE ->
                R.string.transfer_progress_body_rate
            TransferProgressNotificationContent.StringKey.BODY_ETA ->
                R.string.transfer_progress_body_eta
            TransferProgressNotificationContent.StringKey.DURATION_FEW_SECONDS ->
                R.string.transfer_progress_duration_few_seconds
            TransferProgressNotificationContent.StringKey.DURATION_SECONDS ->
                R.string.transfer_progress_duration_seconds
            TransferProgressNotificationContent.StringKey.DURATION_ABOUT_MINUTES ->
                R.string.transfer_progress_duration_about_minutes
            TransferProgressNotificationContent.StringKey.DURATION_ABOUT_HOURS ->
                R.string.transfer_progress_duration_about_hours
        }

    private fun cancelBroadcastIntent(
        context: Context,
        connectionId: Long,
    ): Intent =
        Intent(ConsentIntents.ACTION_CANCEL_TRANSFER).apply {
            // setPackage-only routing. ConsentBroadcastReceiver is
            // registered dynamically (RECEIVER_NOT_EXPORTED) — explicit
            // setClass(receiver::class) does not resolve at the
            // BroadcastQueue and silently drops the broadcast on stricter
            // Android 14+ devices (Vivo Funtouch 16 / OnePlus 15 Android
            // 16 ColorOS). setPackage keeps delivery inside our process.
            setPackage(context.packageName)
            putExtra(ConsentIntents.EXTRA_CONNECTION_ID, connectionId)
        }

    private fun cancelRequestCodeFor(connectionId: Long): Int =
        ((connectionId * REQUEST_CODE_STRIDE + CANCEL_OFFSET) and POSITIVE_INT_MASK_LONG).toInt()

    private const val POSITIVE_INT_MASK: Int = 0x7FFF_FFFF
    private const val POSITIVE_INT_MASK_LONG: Long = 0x7FFF_FFFFL

    private const val REQUEST_CODE_STRIDE = 1L
    private const val CANCEL_OFFSET = 0L
    private const val PERCENT_FULL = 100

    /**
     * Build a fast convenience overload that takes the raw
     * [TransferProgress] snapshot. Equivalent to
     *
     *     post(context, connectionId,
     *          TransferProgressNotificationContent.from(
     *              textResolverFor(context), sourceDeviceName, progress))
     *
     * but cuts a layer of boilerplate at every call site.
     */
    public fun post(
        context: Context,
        connectionId: Long,
        sourceDeviceName: String?,
        progress: TransferProgress,
    ): Int {
        val content =
            TransferProgressNotificationContent.from(
                resolver = textResolverFor(context),
                sourceDeviceName = sourceDeviceName,
                progress = progress,
            )
        return post(context, connectionId, content)
    }
}
