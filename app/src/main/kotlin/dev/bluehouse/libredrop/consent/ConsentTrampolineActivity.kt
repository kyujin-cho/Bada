/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.consent

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.bluehouse.libredrop.R
import dev.bluehouse.libredrop.protocol.connection.TransferItem
import dev.bluehouse.libredrop.service.receiver.consent.ConsentBroadcastReceiver
import dev.bluehouse.libredrop.service.receiver.consent.ConsentDiagnostic
import dev.bluehouse.libredrop.service.receiver.consent.ConsentIntents
import dev.bluehouse.libredrop.service.receiver.consent.ConsentModalRegistry
import dev.bluehouse.libredrop.service.receiver.consent.ConsentNotificationContent
import dev.bluehouse.libredrop.service.receiver.consent.ConsentRegistry

/**
 * Layer 2 of the consent UI (#22): a screen-on, lock-screen-bypassing
 * activity that shows the full transfer details (sender device name,
 * file list with sizes, 4-digit PIN) plus Accept / Reject buttons.
 *
 * Also serves as the foreground in-app modal surface added in #151:
 * when LibreDrop is foregrounded at the moment a `WaitingForUserConsent`
 * arrives, the [dev.bluehouse.libredrop.service.receiver.consent.ConsentCoordinator]
 * launches this activity instead of posting a heads-up notification.
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
 * The activity is launched either by:
 *
 *  - the consent notification's `setContentIntent` /
 *    `setFullScreenIntent` (background path, user tapped the heads-up),
 *    or
 *  - a programmatic launch from
 *    [dev.bluehouse.libredrop.service.receiver.consent.ConsentCoordinator]
 *    when LibreDrop is already foregrounded (issue #151 modal path).
 *
 * Both paths pass [ConsentIntents.EXTRA_CONNECTION_ID] and look up the
 * live [InboundConnection] in [ConsentRegistry]. On Accept / Reject the
 * activity dispatches a broadcast through
 * [ConsentBroadcastReceiver]'s action filter — exactly the same path
 * the heads-up notification's action buttons use — so the inbound FSM
 * cannot tell which UI surfaced the decision (#151 acceptance
 * criterion).
 *
 * ### Cancel semantics
 *
 * Pressing the system back button or tapping the Reject button both
 * route to the same `submitUserConsent(false)` path. Per the issue
 * acceptance criteria, simply dismissing the activity by closing the
 * window does NOT auto-reject — the activity finishes, but the
 * notification re-raises (handled by the coordinator's
 * foreground-listener path) so the user can still decide. This matches
 * NearDrop's behaviour where the connection only resolves on an
 * explicit user choice.
 *
 * ### Coordinator-driven dismiss
 *
 * On `onCreate` the activity registers itself with
 * [ConsentModalRegistry] under its connection id; on `onDestroy` (or
 * after submitting a decision) it unregisters. The
 * [dev.bluehouse.libredrop.service.receiver.consent.ConsentCoordinator]
 * uses the registry to call [finish] when the user backgrounds
 * LibreDrop while the modal is up — the modal closes, the coordinator
 * raises the heads-up notification, and the user can resume from the
 * shade.
 */
class ConsentTrampolineActivity : AppCompatActivity() {
    private var connectionId: Long = ConsentIntents.MISSING_CONNECTION_ID
    private var decisionSubmitted: Boolean = false
    private var modalRegistered: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // setShowWhenLocked / setTurnScreenOn must be called BEFORE
        // setContentView on API 27+ so the platform brings the activity
        // up over the keyguard immediately. Pre-API-27 devices fall
        // back to FLAG_DISMISS_KEYGUARD / FLAG_TURN_SCREEN_ON window
        // flags via the shim below.
        applyIncomingCallFlags()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_consent_trampoline)

        ConsentDiagnostic.log(this, "trampoline.onCreate intent.id=${incomingId(intent)}")
        bindIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        // Activity is `singleTask`/`singleTop` style — when a fresh
        // launch arrives for the same activity instance (e.g. another
        // pending consent under a different id, or a coordinator
        // re-launch after the user briefly backgrounded), rebind to
        // the new intent so the right entry shows.
        super.onNewIntent(intent)
        setIntent(intent)
        ConsentDiagnostic.log(this, "trampoline.onNewIntent intent.id=${incomingId(intent)}")
        bindIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        ConsentDiagnostic.log(this, "trampoline.onStart id=$connectionId")
    }

    override fun onResume() {
        super.onResume()
        ConsentDiagnostic.log(this, "trampoline.onResume id=$connectionId")
    }

    override fun onPause() {
        super.onPause()
        ConsentDiagnostic.log(this, "trampoline.onPause id=$connectionId finishing=$isFinishing")
    }

    override fun onStop() {
        super.onStop()
        ConsentDiagnostic.log(this, "trampoline.onStop id=$connectionId finishing=$isFinishing")
    }

    private fun incomingId(intent: Intent?): Long =
        intent?.getLongExtra(
            ConsentIntents.EXTRA_CONNECTION_ID,
            ConsentIntents.MISSING_CONNECTION_ID,
        ) ?: ConsentIntents.MISSING_CONNECTION_ID

    private fun bindIntent(intent: Intent?) {
        // Unregister any prior modal binding before adopting the new
        // one so a re-launch under a different connection id does not
        // leak the old finish-callback.
        unregisterModal()
        decisionSubmitted = false

        connectionId =
            intent?.getLongExtra(
                ConsentIntents.EXTRA_CONNECTION_ID,
                ConsentIntents.MISSING_CONNECTION_ID,
            ) ?: ConsentIntents.MISSING_CONNECTION_ID

        val entry =
            connectionId
                .takeIf { it != ConsentIntents.MISSING_CONNECTION_ID }
                ?.let { ConsentRegistry.instance.lookup(it) }

        ConsentDiagnostic.log(
            this,
            "trampoline.bindIntent id=$connectionId lookup=${if (entry == null) "null" else "present"}",
        )

        if (entry == null) {
            // The notification fired but the underlying connection has
            // already terminated (e.g. the peer cancelled). Show a brief
            // toast and finish — the surface keeps its incoming-call
            // flags so the user is never left staring at a blank lock
            // screen.
            ConsentDiagnostic.log(this, "trampoline.finish id=$connectionId reason=lookup-null")
            Toast
                .makeText(this, R.string.consent_dismissed, Toast.LENGTH_SHORT)
                .show()
            finish()
            return
        }

        renderEntry(entry)
        wireButtons(entry)
        registerModal()
    }

    override fun onDestroy() {
        // If the user dismissed the activity without an explicit
        // decision (e.g. swipe-back, screen lock), DO NOT auto-reject —
        // the issue's acceptance criteria explicitly call out that
        // dismissal must not be treated as a reject. The notification
        // re-raises through the coordinator's foreground listener so
        // the user can still decide.
        unregisterModal()
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

    /**
     * Submit the user's decision by broadcasting a consent intent that
     * lands in [ConsentBroadcastReceiver] — exactly the same path the
     * heads-up notification's action buttons use. Routing both paths
     * through the broadcast receiver guarantees the inbound FSM cannot
     * tell whether the user clicked the notification action or the
     * modal button (#151 acceptance: identical bookkeeping for both
     * surfaces).
     */
    private fun submit(
        @Suppress("UNUSED_PARAMETER") entry: ConsentRegistry.Entry,
        accepted: Boolean,
    ) {
        if (decisionSubmitted) return
        decisionSubmitted = true
        // Unregister the modal hook BEFORE dispatching the broadcast
        // so a coordinator-driven dismiss (e.g. the user lost the race
        // by backgrounding LibreDrop a millisecond too late) cannot
        // double-finish the activity.
        unregisterModal()
        val action = if (accepted) ConsentIntents.ACTION_ACCEPT else ConsentIntents.ACTION_REJECT
        // Dispatch via setPackage + action only. ConsentBroadcastReceiver
        // is registered dynamically by ReceiverForegroundService with
        // RECEIVER_NOT_EXPORTED — there is no manifest entry, so an
        // explicit setClass(receiver::class) component reference does
        // not resolve at the BroadcastQueue and the broadcast is
        // silently dropped (observed on Vivo Funtouch 16 during the
        // #151 hardware loop). setPackage(packageName) is enough to
        // guarantee delivery stays inside our process; the dynamic
        // receiver's IntentFilter matches both ACCEPT and REJECT
        // actions exclusively, and there is no other receiver in the
        // package that listens for them.
        ConsentDiagnostic.log(this, "trampoline.sendBroadcast id=$connectionId accepted=$accepted")
        sendBroadcast(
            Intent(action).apply {
                setPackage(packageName)
                putExtra(ConsentIntents.EXTRA_CONNECTION_ID, connectionId)
            },
        )
        finish()
    }

    private fun registerModal() {
        if (connectionId == ConsentIntents.MISSING_CONNECTION_ID) return
        ConsentDiagnostic.log(this, "trampoline.registerModal id=$connectionId")
        ConsentModalRegistry.instance.register(connectionId) {
            // The coordinator asked us to disappear without submitting
            // a decision — typically because LibreDrop is going to the
            // background and the heads-up notification is taking over.
            ConsentDiagnostic.log(
                this,
                "trampoline.dismissCallback id=$connectionId decisionSubmitted=$decisionSubmitted",
            )
            if (!decisionSubmitted) {
                runOnUiThread { finish() }
            }
        }
        modalRegistered = true
    }

    private fun unregisterModal() {
        if (!modalRegistered) return
        ConsentDiagnostic.log(this, "trampoline.unregisterModal id=$connectionId")
        if (connectionId != ConsentIntents.MISSING_CONNECTION_ID) {
            ConsentModalRegistry.instance.unregister(connectionId)
        }
        modalRegistered = false
    }

    private companion object {
        /** Cap on the number of file lines rendered before truncation. */
        private const val MAX_ITEM_LINES = 8
    }
}
