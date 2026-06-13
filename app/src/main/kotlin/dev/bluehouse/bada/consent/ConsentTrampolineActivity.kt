/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.consent

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.Formatter
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.CircularProgressIndicator
import dev.bluehouse.bada.R
import dev.bluehouse.bada.bugreport.BugReportFlowSupport
import dev.bluehouse.bada.protocol.connection.InboundConnection
import dev.bluehouse.bada.protocol.connection.InboundConnectionState
import dev.bluehouse.bada.protocol.connection.ReceivedItem
import dev.bluehouse.bada.protocol.connection.TransferItem
import dev.bluehouse.bada.protocol.connection.TransferProgress
import dev.bluehouse.bada.protocol.medium.Medium
import dev.bluehouse.bada.service.receiver.consent.ConsentBroadcastReceiver
import dev.bluehouse.bada.service.receiver.consent.ConsentDiagnostic
import dev.bluehouse.bada.service.receiver.consent.ConsentIntents
import dev.bluehouse.bada.service.receiver.consent.ConsentModalRegistry
import dev.bluehouse.bada.service.receiver.consent.ConsentNotificationContent
import dev.bluehouse.bada.service.receiver.consent.ConsentRegistry
import dev.bluehouse.bada.transfer.KeepScreenOnPreferences
import dev.bluehouse.bada.transfer.TransferExpertDetailsFormatter
import dev.bluehouse.bada.transfer.TransferExpertViewPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Layer 2 of the consent UI (#22): a screen-on, lock-screen-bypassing
 * activity that shows the full transfer details (sender device name,
 * file list with sizes, 4-digit PIN) plus Accept / Reject buttons.
 *
 * Also serves as the foreground in-app modal surface added in #151:
 * when Bada is foregrounded at the moment a `WaitingForUserConsent`
 * arrives, the [dev.bluehouse.bada.service.receiver.consent.ConsentCoordinator]
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
 *    [dev.bluehouse.bada.service.receiver.consent.ConsentCoordinator]
 *    when Bada is already foregrounded (issue #151 modal path).
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
 * [dev.bluehouse.bada.service.receiver.consent.ConsentCoordinator]
 * uses the registry to call [finish] when the user backgrounds
 * Bada while the modal is up — the modal closes, the coordinator
 * raises the heads-up notification, and the user can resume from the
 * shade.
 */
@Suppress("TooManyFunctions")
class ConsentTrampolineActivity : AppCompatActivity() {
    private var connectionId: Long = ConsentIntents.MISSING_CONNECTION_ID
    private var decisionSubmitted: Boolean = false
    private var modalRegistered: Boolean = false
    private lateinit var bugReportFlowSupport: BugReportFlowSupport

    /**
     * The [InboundConnection] the activity observes after the user
     * taps Accept. Captured from the [ConsentRegistry.Entry] before
     * the broadcast is dispatched so the registry's later
     * unregister-on-decision call cannot strand us without a state
     * source. `null` until Accept fires.
     */
    private var observedConnection: InboundConnection? = null

    /** Coroutine collecting the observed connection's StateFlow. */
    private var stateJob: Job? = null

    /**
     * Total announced byte count for the in-flight transfer, captured
     * from the `WaitingForUserConsent` entry so the receiving panel can
     * format the running counter as "12 MB / 100 MB" before the first
     * `Receiving` event lands (which also carries the same total).
     */
    private var totalAnnouncedBytes: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        // setShowWhenLocked / setTurnScreenOn must be called BEFORE
        // setContentView on API 27+ so the platform brings the activity
        // up over the keyguard immediately. Pre-API-27 devices fall
        // back to FLAG_DISMISS_KEYGUARD / FLAG_TURN_SCREEN_ON window
        // flags via the shim below.
        applyIncomingCallFlags()
        super.onCreate(savedInstanceState)
        // Soft alpha + scale-up entrance so the popup feels like it's
        // emerging from its centre rather than snapping into place.
        // Paired with `popup_fade_out` in [finish] for a symmetric
        // dismiss.
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.popup_fade_in, 0)
        setContentView(R.layout.activity_consent_trampoline)
        bugReportFlowSupport = BugReportFlowSupport.install(this)

        ConsentDiagnostic.log(this, "trampoline.onCreate intent.id=${incomingId(intent)}")
        bindIntent(intent)
    }

    /**
     * Override the activity-level dismissal animation so every path
     * out of the popup — accept / reject button, close button on the
     * completed panel, system back, or tap-outside on the dialog
     * window — fades + slightly scales the dialog as it leaves
     * instead of disappearing in a single frame. The platform's
     * default Dialog-theme exit is just an alpha fade with a short
     * duration; the custom [popup_fade_out] keeps the same direction
     * but adds the matching scale + interpolator the entrance uses.
     *
     * `overridePendingTransition` is deprecated on API 34+ in favour
     * of `overrideActivityTransition`, but the old API still works
     * (back-compat shim is still wired in the framework) and is
     * trivially correct for our minSdk = 24 floor.
     */
    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(0, R.anim.popup_fade_out)
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
        setTransferKeepScreenOn(active = false)
        // If the user dismissed the activity without an explicit
        // decision (e.g. swipe-back, screen lock), DO NOT auto-reject —
        // the issue's acceptance criteria explicitly call out that
        // dismissal must not be treated as a reject. The notification
        // re-raises through the coordinator's foreground listener so
        // the user can still decide.
        unregisterModal()
        // The post-Accept state observer is `lifecycleScope`-scoped
        // and would auto-cancel here too; the explicit cancel keeps
        // the teardown sequence symmetric with [startObservingConnectionState].
        stateJob?.cancel()
        stateJob = null
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
        val pinView = findViewById<TextView>(R.id.consent_pin)
        val list = findViewById<LinearLayout>(R.id.consent_files_list)

        val device = entry.sourceDeviceName?.takeIf { it.isNotBlank() }
        titleView.text =
            if (device != null) {
                // Pick a type-specific title template based on the
                // announced mime types: all `image/*` → "…share
                // images", all `video/*` → "…share videos", anything
                // else (mixed types, text payloads, archives, ...) →
                // generic "…share files". Empty announcements fall
                // through to the generic template too — better than
                // promising images we cannot deliver.
                val templateRes =
                    when (classifyPayload(entry.items)) {
                        PayloadKind.IMAGES -> R.string.consent_title_with_name_images
                        PayloadKind.VIDEOS -> R.string.consent_title_with_name_videos
                        PayloadKind.FILES -> R.string.consent_title_with_name_files
                    }
                getString(templateRes, device)
            } else {
                getString(R.string.consent_unknown_sender)
            }

        // The legacy `consent_subtitle` (e.g. "3 files (12.4 MB)") is
        // hidden in the layout — the type-specific title carries the
        // payload kind already, and the per-file rows below carry the
        // size, so the count line is redundant. Folder-share metadata
        // is no longer surfaced as a subtitle either; if a future
        // design needs it back, the view ID remains stable for
        // re-binding.

        pinView.text = entry.pin

        // Render the snapshot captured by the coordinator at the moment
        // the consent state was reached. Reading state.value would race
        // with the FSM advancing past WaitingForUserConsent (e.g. the
        // peer cancelled while the user was reaching for the screen).
        renderItemList(list, entry.items)
    }

    /**
     * Classify a consent announcement into one of three buckets used
     * for picking the type-specific title template. The rule is
     * intentionally strict ("ALL items match" rather than "majority"
     * or "first item") so the title never overpromises a single
     * media type when the announcement mixes kinds. Mixed and
     * non-file (text / URL) announcements collapse to [PayloadKind.FILES].
     */
    private fun classifyPayload(items: List<TransferItem>): PayloadKind {
        val files = items.filterIsInstance<TransferItem.File>()
        if (files.isEmpty() || files.size != items.size) {
            // Empty announcement, or mixed file + text payload — fall
            // back to the generic "files" framing.
            return PayloadKind.FILES
        }
        return when {
            files.all { it.mimeType.startsWith("image/") } -> PayloadKind.IMAGES
            files.all { it.mimeType.startsWith("video/") } -> PayloadKind.VIDEOS
            else -> PayloadKind.FILES
        }
    }

    private enum class PayloadKind { IMAGES, VIDEOS, FILES }

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
            // Match the centred axis of the file rows above.
            extra.gravity = Gravity.CENTER_HORIZONTAL
            extra.layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
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
        // Each row renders the name on the first line and the size
        // on the second line (the format string carries a `\n`); both
        // lines centre on the same axis as the title / PIN label /
        // files heading above. Match-parent width is required so the
        // gravity actually has horizontal space to centre within —
        // wrap_content (the default for `addView` with no LayoutParams)
        // would shrink the row to its longest line and the gravity
        // would have nothing to align against.
        view.gravity = Gravity.CENTER_HORIZONTAL
        view.layoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
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
        entry: ConsentRegistry.Entry,
        accepted: Boolean,
    ) {
        if (decisionSubmitted) return
        decisionSubmitted = true
        // Unregister the modal hook BEFORE dispatching the broadcast
        // so a coordinator-driven dismiss (e.g. the user lost the race
        // by backgrounding Bada a millisecond too late) cannot
        // double-finish the activity.
        unregisterModal()

        if (accepted) {
            // Capture the connection + total size BEFORE dispatching
            // the broadcast — the broadcast handler unregisters the
            // ConsentRegistry entry, which would strip our access to
            // entry.connection on the very next line.
            observedConnection = entry.connection
            totalAnnouncedBytes = entry.totalSize
        }

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

        if (accepted) {
            // Stay foreground and observe the in-flight transfer so the
            // user gets a circular-progress + completion-preview UI
            // instead of a snap-finish that drops them straight back to
            // wherever they were.
            switchToReceivingPanel()
            startObservingConnectionState()
        } else {
            finish()
        }
    }

    /**
     * Hide the consent prompt and reveal the circular-progress panel.
     * Wraps the visibility flip in a [ChangeBounds] transition so the
     * dialog window resizes smoothly between the consent and the
     * (smaller) receiving panel rather than snapping in a single
     * frame.
     */
    private fun switchToReceivingPanel() {
        beginPanelTransition()
        findViewById<View>(R.id.consent_panel).visibility = View.GONE
        findViewById<View>(R.id.consent_receiving_panel).visibility = View.VISIBLE

        // Seed the running counter with the announced totals so the
        // user sees "0 B / 100 MB" immediately rather than empty
        // space until the first Receiving event lands.
        renderProgress(
            progress =
                TransferProgress.of(
                    bytesTransferred = 0,
                    totalSize = totalAnnouncedBytes,
                    bytesPerSecond = 0,
                ),
            activeMedium = observedConnection?.activeMedium?.value ?: Medium.WIFI_LAN,
            wifiFrequencyMhz = observedConnection?.activeWifiFrequencyMhz?.value,
        )
    }

    /**
     * Subscribe to the captured InboundConnection's StateFlow under
     * [lifecycleScope] so collection cancels automatically when the
     * activity is destroyed. The collector switches the visible panel
     * on every terminal transition.
     */
    private fun startObservingConnectionState() {
        val connection = observedConnection ?: return
        stateJob?.cancel()
        stateJob =
            lifecycleScope.launch {
                connection.state
                    .combine(connection.activeMedium) { state, medium -> state to medium }
                    .combine(connection.activeWifiFrequencyMhz) { (state, medium), frequencyMhz ->
                        ReceiveRenderSnapshot(
                            state = state,
                            activeMedium = medium,
                            wifiFrequencyMhz = frequencyMhz,
                        )
                    }.collect { snapshot ->
                        when (val state = snapshot.state) {
                            is InboundConnectionState.Receiving -> {
                                setTransferKeepScreenOn(active = true)
                                renderProgress(
                                    progress = state.progress,
                                    activeMedium = snapshot.activeMedium,
                                    wifiFrequencyMhz = snapshot.wifiFrequencyMhz,
                                )
                            }
                            is InboundConnectionState.Completed -> {
                                setTransferKeepScreenOn(active = false)
                                showCompletedPanel(state.items)
                            }
                            is InboundConnectionState.Cancelled -> {
                                setTransferKeepScreenOn(active = false)
                                showFailedPanel(getString(R.string.consent_state_cancelled), reason = null)
                            }
                            is InboundConnectionState.Failed -> {
                                setTransferKeepScreenOn(active = false)
                                showFailedPanel(getString(R.string.consent_state_failed), reason = state.reason)
                            }
                            is InboundConnectionState.Rejected -> {
                                setTransferKeepScreenOn(active = false)
                                showFailedPanel(getString(R.string.consent_state_failed), reason = null)
                            }
                            else -> Unit
                        }
                    }
            }
    }

    private data class ReceiveRenderSnapshot(
        val state: InboundConnectionState,
        val activeMedium: Medium,
        val wifiFrequencyMhz: Int?,
    )

    private fun setTransferKeepScreenOn(active: Boolean) {
        val keepScreenOn =
            active &&
                KeepScreenOnPreferences
                    .from(this)
                    .isKeepScreenOnDuringTransfersEnabled()
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun renderProgress(
        progress: TransferProgress,
        activeMedium: Medium,
        wifiFrequencyMhz: Int?,
    ) {
        val progressBar = findViewById<CircularProgressIndicator>(R.id.consent_receiving_progress) ?: return
        val percentText = findViewById<TextView>(R.id.consent_receiving_progress_pct)
        val pct =
            if (progress.totalSize > 0) {
                ((progress.bytesTransferred.toDouble() / progress.totalSize.toDouble()) * PERCENT_SCALE)
                    .toInt()
                    .coerceIn(0, PERCENT_SCALE)
            } else {
                0
            }
        if (progress.totalSize > 0) {
            // Once we know the total, switch from the spinning
            // indeterminate state to a deterministic ratio so the user
            // can see the bar fill up frame by frame.
            if (progressBar.isIndeterminate) progressBar.isIndeterminate = false
            progressBar.setProgressCompat(pct, true)
        }
        percentText?.text = getString(R.string.transfer_progress_percent, pct)
        findViewById<TextView>(R.id.consent_receiving_progress_text)?.text =
            getString(
                R.string.consent_state_progress,
                Formatter.formatShortFileSize(this, progress.bytesTransferred),
                Formatter.formatShortFileSize(this, progress.totalSize),
            )
        renderExpertDetails(progress, activeMedium, wifiFrequencyMhz)
    }

    private fun renderExpertDetails(
        progress: TransferProgress,
        activeMedium: Medium,
        wifiFrequencyMhz: Int?,
    ) {
        val details = findViewById<TextView>(R.id.consent_expert_details) ?: return
        if (!TransferExpertViewPreferences.from(this).isExpertViewEnabled()) {
            details.visibility = View.GONE
            return
        }
        details.text =
            TransferExpertDetailsFormatter.format(
                context = this,
                progress = progress,
                activeMedium = activeMedium,
                wifiFrequencyMhz = wifiFrequencyMhz,
            )
        details.visibility = View.VISIBLE
    }

    /**
     * Resolve any image preview FIRST, then transition to the
     * completion panel with all elements at their final visibility.
     *
     * Earlier the panel switch fired immediately and the
     * MediaStore lookup ran async — that produced a one-frame
     * "title + Close button only" flash that snapped to "title +
     * polaroid preview + Close + View image" once the lookup
     * resolved. The flash read as a glitchy intermediate popup.
     *
     * Now: kick off the (bounded) lookup, await it, and only then
     * commit the panel switch. The receiving panel stays at 100%
     * during the wait so the user just sees a brief "wrapping up"
     * moment instead of a layout pop. A
     * [PREVIEW_LOOKUP_TIMEOUT_MS] cap guarantees the wait never
     * stalls the completion UI if MediaStore is slow or hung —
     * the panel switches without a preview after the timeout.
     *
     * Non-image transfers skip the lookup entirely (no filenames
     * to query) and switch panels immediately, matching the old
     * behavior for that path.
     */
    private fun showCompletedPanel(items: List<ReceivedItem>) {
        findViewById<TextView>(R.id.consent_expert_details)?.visibility = View.GONE
        val fileItems = items.filterIsInstance<ReceivedItem.File>()
        val targetNames = fileItems.map { it.header.fileName }.toSet()
        lifecycleScope.launch {
            val previewUri =
                if (targetNames.isEmpty()) {
                    null
                } else {
                    withTimeoutOrNull(PREVIEW_LOOKUP_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) { findReceivedImageUri(targetNames) }
                    }
                }

            beginPanelTransition()
            findViewById<View>(R.id.consent_receiving_panel).visibility = View.GONE
            findViewById<View>(R.id.consent_failed_panel).visibility = View.GONE
            findViewById<View>(R.id.consent_completed_panel).visibility = View.VISIBLE

            val summary =
                if (fileItems.size == 1) {
                    getString(R.string.consent_state_completed_one)
                } else {
                    getString(R.string.consent_state_completed_many, fileItems.size)
                }
            findViewById<TextView>(R.id.consent_completed_summary)?.text = summary

            val closeButton = findViewById<View>(R.id.consent_completed_close_button)
            configureCompletedActionButton(closeButton, getString(R.string.consent_state_close))
            closeButton.setOnClickListener { finish() }

            if (previewUri != null) {
                bindCompletedPreview(previewUri)
            }
        }
    }

    @Suppress("ReturnCount")
    private fun bindCompletedPreview(uri: Uri) {
        val previewView = findViewById<ImageView>(R.id.consent_completed_preview) ?: return
        val previewCard = findViewById<FrameLayout>(R.id.consent_completed_preview_card) ?: return
        val viewButton = findViewById<View>(R.id.consent_completed_view_button) ?: return
        try {
            previewView.setImageURI(uri)
            previewCard.visibility = View.VISIBLE
        } catch (e: SecurityException) {
            Log.w(TAG, "Failed to load preview Uri: ${e.message}")
            return
        }
        // Mirror the sender's success card: paint a heavily-blurred
        // copy of the same image across the dialog backdrop so both
        // completion surfaces read as the same visual family.
        applyBlurredCardBackground(uri)
        configureCompletedActionButton(viewButton, getString(R.string.consent_state_view_image))
        viewButton.visibility = View.VISIBLE
        // Reveal the spacer between Close and View image so the row
        // splits cleanly when both buttons are visible (the spacer
        // stays GONE while only Close is in play, letting Close span
        // the full width of the panel).
        findViewById<View>(R.id.consent_completed_button_gap)?.visibility = View.VISIBLE
        viewButton.setOnClickListener {
            try {
                val viewIntent =
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "image/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                startActivity(viewIntent)
                // Dismiss the consent dialog as soon as we hand off to
                // the gallery viewer. Otherwise the trampoline stays in
                // the back stack and the user has to dismiss it again
                // after they're done viewing — which feels like a stuck
                // popup since the receive flow is already complete. With
                // this finish, returning from the gallery drops them
                // straight to whatever was below the trampoline (home /
                // launcher), matching the user's expectation that "back
                // from gallery means back to my normal screen".
                finish()
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No activity to view image: ${e.message}")
                Toast
                    .makeText(this, R.string.consent_state_view_image_unavailable, Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    private fun configureCompletedActionButton(
        view: View,
        label: CharSequence,
    ) {
        view.contentDescription = label
        ViewCompat.setAccessibilityDelegate(
            view,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                    info.text = label
                    info.isClickable = true
                }
            },
        )
    }

    /**
     * Sender-style blurred backdrop for the completion dialog: paints
     * a sampled copy of the received image across the full dialog
     * surface and dampens it under a translucent white sheet so the
     * foreground panel stays readable. The bitmap is decoded with
     * `setTargetSampleSize` (API 28+) / `inSampleSize` (older) so the
     * ImageView's intrinsic size never balloons the wrap_content
     * FrameLayout to image dimensions.
     *
     * The blur effect itself uses [RenderEffect] (API 31+); pre-API-31
     * devices fall back to a sharp sampled bitmap behind the same
     * overlay, which still reads as a soft accent.
     */
    private fun applyBlurredCardBackground(uri: Uri) {
        val blurView = findViewById<ImageView>(R.id.consent_card_blur) ?: return
        val overlayView = findViewById<View>(R.id.consent_card_overlay) ?: return
        lifecycleScope.launch {
            val bitmap =
                withContext(Dispatchers.IO) {
                    decodeSampledBitmap(uri, BLUR_BG_TARGET_PX)
                } ?: return@launch
            blurView.setImageBitmap(bitmap)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                blurView.setRenderEffect(buildPrettyBlurEffect())
            }
            // Cross-fade the backdrop in instead of snapping it visible,
            // so the blurred image eases onto the white dialog rather
            // than popping in once the async decode lands. The blur layer
            // and its dampening overlay fade together as one backdrop.
            fadeInBlurBackdrop(blurView, overlayView)
        }
    }

    /**
     * Reveal the completion blur backdrop with a short alpha fade. Both
     * the blurred image and the translucent overlay start fully
     * transparent and ease to opaque together over [BLUR_FADE_IN_MS].
     */
    private fun fadeInBlurBackdrop(
        blurView: View,
        overlayView: View,
    ) {
        for (view in listOf(blurView, overlayView)) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
            view
                .animate()
                .alpha(1f)
                .setDuration(BLUR_FADE_IN_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /**
     * Compose the dialog-backdrop blur effect: saturation-boosted
     * color filter chained behind a Gaussian blur. Mirrors
     * [SendActivity]'s pretty-blur pipeline so the sender's success
     * card and the receiver's completion dialog read as the same
     * visual family — saturation lift → blur smear gives the iOS
     * `UIVisualEffectView`-style vibrancy feel rather than the
     * washed-out gray a plain Gaussian blur tends to produce.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun buildPrettyBlurEffect(): RenderEffect {
        val saturationFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(BLUR_SATURATION_BOOST) })
        val saturationEffect = RenderEffect.createColorFilterEffect(saturationFilter)
        val blurEffect =
            RenderEffect.createBlurEffect(
                BLUR_RADIUS_PX,
                BLUR_RADIUS_PX,
                Shader.TileMode.MIRROR,
            )
        return RenderEffect.createChainEffect(blurEffect, saturationEffect)
    }

    /**
     * Decode a content URI to a sampled bitmap whose larger edge is
     * at most [targetPx]. Mirrors the pattern used by SendActivity so
     * both completion surfaces share the same memory profile and the
     * `wrap_content` parents do not size to the source bitmap's
     * pixel dimensions.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun decodeSampledBitmap(
        uri: Uri,
        targetPx: Int,
    ): Bitmap? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val ratio =
                        maxOf(
                            info.size.width / targetPx,
                            info.size.height / targetPx,
                            1,
                        )
                    decoder.setTargetSampleSize(ratio)
                }
            } else {
                val bounds =
                    android.graphics.BitmapFactory
                        .Options()
                        .apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, bounds)
                }
                val sample =
                    maxOf(
                        bounds.outWidth / targetPx,
                        bounds.outHeight / targetPx,
                        1,
                    )
                val opts =
                    android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, opts)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "decodeSampledBitmap failed for $uri", e)
            null
        }

    /**
     * Search MediaStore.Images for a recently-added entry whose
     * display name matches one of [targetNames]. Returns the first
     * match (most-recently-added) or `null` if none of the announced
     * names land in the image collection — common for non-image
     * payloads or when the platform has not yet indexed the new file.
     */
    @Suppress("NestedBlockDepth")
    private fun findReceivedImageUri(targetNames: Set<String>): Uri? {
        val collection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
        val projection =
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
            )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        return try {
            contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                var inspected = 0
                while (cursor.moveToNext() && inspected < PREVIEW_LOOKUP_LIMIT) {
                    inspected++
                    val name = cursor.getString(nameCol)
                    if (name in targetNames) {
                        return@use ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                    }
                }
                null
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "MediaStore preview query denied: ${e.message}")
            null
        }
    }

    private fun showFailedPanel(
        title: String,
        reason: String?,
    ) {
        beginPanelTransition()
        findViewById<TextView>(R.id.consent_expert_details)?.visibility = View.GONE
        findViewById<View>(R.id.consent_receiving_panel).visibility = View.GONE
        findViewById<View>(R.id.consent_completed_panel).visibility = View.GONE
        findViewById<View>(R.id.consent_failed_panel).visibility = View.VISIBLE

        findViewById<TextView>(R.id.consent_failed_title).text = title
        val reasonView = findViewById<TextView>(R.id.consent_failed_reason)
        if (reason.isNullOrBlank()) {
            reasonView.visibility = View.GONE
        } else {
            reasonView.visibility = View.VISIBLE
            reasonView.text = reason
        }
        findViewById<Button>(R.id.consent_failed_close_button).setOnClickListener { finish() }
    }

    private fun beginPanelTransition() {
        val root = findViewById<View>(R.id.consent_root) as? android.view.ViewGroup ?: return
        TransitionManager.beginDelayedTransition(
            root,
            ChangeBounds().apply { duration = PANEL_TRANSITION_MS },
        )
    }

    private fun registerModal() {
        if (connectionId == ConsentIntents.MISSING_CONNECTION_ID) return
        ConsentDiagnostic.log(this, "trampoline.registerModal id=$connectionId")
        ConsentModalRegistry.instance.register(connectionId) {
            // The coordinator asked us to disappear without submitting
            // a decision — typically because Bada is going to the
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

        /** Diagnostic tag for the activity-side preview / view-image paths. */
        private const val TAG = "BadaConsent"
        private const val PERCENT_SCALE = 100

        /**
         * How many cursor rows to walk in MediaStore when looking for
         * a received image to preview. The newest 30 images cover the
         * common case of "opened the app immediately after a transfer";
         * deeper history scans rarely match because Quick Share saves
         * with the peer's announced filename and the announced name is
         * almost never an old gallery duplicate.
         */
        private const val PREVIEW_LOOKUP_LIMIT = 30

        /**
         * Maximum time the completed-state transition will wait for
         * the MediaStore preview lookup before committing the panel
         * switch without a preview. Held tight so a slow MediaStore
         * cannot stall the success UI; in practice the typical
         * "find this filename in the newest 30 images" query runs
         * well under this bound, so the user almost always lands on
         * the fully-rendered completed panel in one transition.
         *
         * Picked at 600 ms based on the app's panel-transition
         * cadence ([PANEL_TRANSITION_MS]) — anything longer than
         * roughly 2× the transition duration starts reading as a
         * pause instead of a transition prefetch.
         */
        private const val PREVIEW_LOOKUP_TIMEOUT_MS: Long = 600L

        /**
         * Duration of the [ChangeBounds] transition between consent
         * panels. Matches the in-card transition used elsewhere in the
         * app for stylistic consistency.
         */
        private const val PANEL_TRANSITION_MS: Long = 280L

        /**
         * Target pixel size for the blurred backdrop on the completed
         * panel. Sampling to this size before handing the bitmap to
         * the ImageView prevents the wrap_content FrameLayout from
         * sizing to the source image's pixel dimensions — same
         * rationale as SendActivity.BLUR_BG_TARGET_PX. 720 px keeps
         * pace with SendActivity so the two completion surfaces have
         * matching gradient smoothness.
         */
        private const val BLUR_BG_TARGET_PX: Int = 720

        /**
         * Pixel radius for the dialog backdrop's RenderEffect blur.
         * 80 px puts the blur in the iOS-material "soft dreamy" zone
         * — large enough that the source bitmap reads as a gradient
         * of color rather than a recognizable photo, but not so large
         * that the result collapses into a single muddy hue.
         */
        private const val BLUR_RADIUS_PX: Float = 80f
        private const val BLUR_SATURATION_BOOST: Float = 1.4f

        /**
         * Fade-in duration for the blurred completion backdrop. The
         * bitmap decode finishes a beat after the panel is already on
         * screen, so cross-fading it in (rather than snapping it from
         * GONE to VISIBLE) keeps the image from popping onto the white
         * dialog surface.
         */
        private const val BLUR_FADE_IN_MS: Long = 320L
    }
}
