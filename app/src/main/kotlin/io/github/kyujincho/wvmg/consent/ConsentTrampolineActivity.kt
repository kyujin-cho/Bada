/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.consent

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.kyujincho.wvmg.R
import io.github.kyujincho.wvmg.protocol.connection.TransferItem
import io.github.kyujincho.wvmg.service.receiver.consent.ConsentIntents
import io.github.kyujincho.wvmg.service.receiver.consent.ConsentNotification
import io.github.kyujincho.wvmg.service.receiver.consent.ConsentNotificationContent
import io.github.kyujincho.wvmg.service.receiver.consent.ConsentRegistry

/**
 * Layer 2 of the consent UI (#22): a screen-on, lock-screen-bypassing
 * activity that shows the full transfer details (sender device name,
 * file list with sizes, 4-digit PIN) plus Accept / Reject buttons.
 *
 * ### Same UX as an incoming call
 *
 * On API 27+ we set [setShowWhenLocked] and [setTurnScreenOn] before
 * the activity is laid out so the device wakes from a screen-off state
 * with the consent dialog visible — same UX an incoming phone call
 * uses. This is critical: the receiver service may be started by the
 * user setting the device down on the table, and we cannot rely on
 * them looking at the lock screen for a heads-up.
 *
 * ### Routing
 *
 * The activity is launched by the consent notification's `setContentIntent`
 * (and `setFullScreenIntent`) with [ConsentIntents.EXTRA_CONNECTION_ID].
 * On Accept / Reject it submits the decision through the
 * [ConsentRegistry] entry's live `InboundConnection.submitUserConsent` —
 * exactly the path the broadcast receiver uses, so the FSM cannot
 * tell whether the user clicked the notification action or the
 * dialog.
 *
 * ### Cancel semantics
 *
 * Pressing the system back button or tapping the Reject button both
 * route to the same `submitUserConsent(false)` path. Per the issue
 * acceptance criteria, simply dismissing the activity by closing the
 * window does NOT auto-reject — the activity finishes, but the
 * notification remains so the user can still decide. This matches
 * NearDrop's behaviour where the connection only resolves on an
 * explicit user choice.
 */
class ConsentTrampolineActivity : AppCompatActivity() {
    private var connectionId: Long = ConsentIntents.MISSING_CONNECTION_ID
    private var decisionSubmitted: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // setShowWhenLocked / setTurnScreenOn must be called BEFORE
        // setContentView on API 27+ so the platform brings the activity
        // up over the keyguard immediately. Pre-API-27 devices fall
        // back to FLAG_DISMISS_KEYGUARD / FLAG_TURN_SCREEN_ON window
        // flags via the shim below.
        applyIncomingCallFlags()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_consent_trampoline)

        connectionId =
            intent?.getLongExtra(
                ConsentIntents.EXTRA_CONNECTION_ID,
                ConsentIntents.MISSING_CONNECTION_ID,
            ) ?: ConsentIntents.MISSING_CONNECTION_ID

        val entry =
            connectionId
                .takeIf { it != ConsentIntents.MISSING_CONNECTION_ID }
                ?.let { ConsentRegistry.instance.lookup(it) }

        if (entry == null) {
            // The notification fired but the underlying connection has
            // already terminated (e.g. the peer cancelled). Show a brief
            // toast and finish — the surface keeps its incoming-call
            // flags so the user is never left staring at a blank lock
            // screen.
            Toast
                .makeText(this, R.string.consent_dismissed, Toast.LENGTH_SHORT)
                .show()
            finish()
            return
        }

        renderEntry(entry)
        wireButtons(entry)
    }

    override fun onDestroy() {
        // If the user dismissed the activity without an explicit
        // decision (e.g. swipe-back, screen lock), DO NOT auto-reject —
        // the issue's acceptance criteria explicitly call out that
        // dismissal must not be treated as a reject. The notification
        // continues to live on so the user can still decide.
        super.onDestroy()
    }

    /**
     * Wake-the-screen / show-over-keyguard flags. API 27+ uses the
     * documented Activity setters; older devices fall back to window
     * flags. The activity's manifest entry doesn't need
     * `showOnLockScreen` because we set the flag programmatically.
     */
    @Suppress("DEPRECATION")
    private fun applyIncomingCallFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            // Pre-O_MR1 fallbacks. KEEP_SCREEN_ON ensures the screen
            // does not blank during the consent prompt; SHOW_WHEN_LOCKED
            // / TURN_SCREEN_ON / DISMISS_KEYGUARD are the legacy
            // counterparts of the API 27+ setters.
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
    }

    private fun renderEntry(entry: ConsentRegistry.Entry) {
        val titleView = findViewById<TextView>(R.id.consent_title)
        val subtitleView = findViewById<TextView>(R.id.consent_subtitle)
        val pinView = findViewById<TextView>(R.id.consent_pin)
        val list = findViewById<LinearLayout>(R.id.consent_files_list)

        val device = entry.sourceDeviceName?.takeIf { it.isNotBlank() }
        titleView.text =
            if (device != null) {
                getString(R.string.consent_title_with_name, device)
            } else {
                getString(R.string.consent_unknown_sender)
            }

        // Folder-share summary (#39): when every announced file shares
        // the same root parent_folder we show a single folder summary
        // instead of the per-file count. Fall through to the generic
        // count summary otherwise.
        val folderName = ConsentNotificationContent.sharedRootFolder(entry.items)
        subtitleView.text =
            when {
                entry.itemCount <= 0 -> getString(R.string.consent_summary_no_items)
                folderName != null ->
                    getString(
                        R.string.consent_summary_folder,
                        folderName,
                        entry.itemCount,
                        ConsentNotificationContent.humanReadableSize(entry.totalSize),
                    )
                else ->
                    getString(
                        R.string.consent_summary_n_items,
                        entry.itemCount,
                        ConsentNotificationContent.humanReadableSize(entry.totalSize),
                    )
            }

        pinView.text = entry.pin

        // Render the snapshot captured by the coordinator at the moment
        // the consent state was reached. Reading state.value would race
        // with the FSM advancing past WaitingForUserConsent (e.g. the
        // peer cancelled while the user was reaching for the screen).
        renderItemList(list, entry.items)
    }

    private fun renderItemList(
        list: LinearLayout,
        items: List<TransferItem>,
    ) {
        list.removeAllViews()
        if (items.isEmpty()) {
            list.visibility = View.GONE
            return
        }
        list.visibility = View.VISIBLE
        val cap = MAX_ITEM_LINES.coerceAtMost(items.size)
        for (i in 0 until cap) {
            list.addView(itemLine(items[i]))
        }
        if (items.size > cap) {
            val extra = TextView(this)
            extra.text = getString(R.string.consent_more_items, items.size - cap)
            list.addView(extra)
        }
    }

    private fun itemLine(item: TransferItem): TextView {
        val view = TextView(this)
        view.text =
            when (item) {
                is TransferItem.File ->
                    getString(
                        R.string.consent_file_line,
                        item.name,
                        ConsentNotificationContent.humanReadableSize(item.size),
                    )

                is TransferItem.Text ->
                    getString(
                        R.string.consent_text_line_with_title,
                        item.title.ifBlank { item.kind.name },
                        ConsentNotificationContent.humanReadableSize(item.size),
                    )
            }
        return view
    }

    private fun wireButtons(entry: ConsentRegistry.Entry) {
        findViewById<Button>(R.id.consent_accept_button).setOnClickListener {
            submit(entry, accepted = true)
        }
        findViewById<Button>(R.id.consent_reject_button).setOnClickListener {
            submit(entry, accepted = false)
        }
    }

    private fun submit(
        entry: ConsentRegistry.Entry,
        accepted: Boolean,
    ) {
        if (decisionSubmitted) return
        decisionSubmitted = true
        // Unregister BEFORE submitting so a racing broadcast (the user
        // managed to tap a notification action a millisecond before
        // the activity decision) cannot double-submit.
        ConsentRegistry.instance.unregister(connectionId)
        entry.connection.submitUserConsent(accepted)
        ConsentNotification.dismiss(this, connectionId)
        finish()
    }

    private companion object {
        /** Cap on the number of file lines rendered before truncation. */
        private const val MAX_ITEM_LINES = 8
    }
}
