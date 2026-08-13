/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.namecard

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.bluehouse.bada.R
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog
import dev.bluehouse.bada.nfc.NameCardNdef
import dev.bluehouse.bada.protocol.namecard.NameCard
import dev.bluehouse.bada.service.radio.RadioHelperClient
import dev.bluehouse.bada.service.radio.ShareRadioController

/**
 * **Name Card transfer screen** — the full-screen page shown when two phones tap
 * to swap contacts. Both roles show YOUR OWN card with **Share** / **Receive
 * Only**; the shared [NameCardConsentMachine] (via [NameCardLinkHolder]) drives
 * every state after that.
 *
 * ## Trust model (who may launch this, with what)
 * Peer data NEVER travels through Intent extras. The activity is exported only
 * for the OS's `NDEF_DISCOVERED` dispatch (the AAR launch after a tap), and that
 * path only ever STARTS A FRESH consent session from the token inside the
 * OS-delivered NDEF — it accepts no role, no peer card, nothing renderable from
 * the caller. The in-process server launch carries a bare role marker and
 * attaches to the live [NameCardLinkHolder] session; if no session is live, the
 * screen finishes. A hostile app on the same device therefore cannot inject a
 * spoofed contact into this screen.
 *
 * Two roles:
 *  - **CLIENT** (the phone whose OS dispatched the tap): runs the BLE consent
 *    client against the NDEF token.
 *  - **SERVER** (the tapped phone): [NameCardExchangeService] started the
 *    session at tap time; this screen attaches to it ([serverV2Intent]).
 *
 * Saving uses [NameCardSaver] (ContactsContract — direct insert if
 * WRITE_CONTACTS is granted, else the system Add-contact screen).
 *
 * **Window:** launched under `Theme.Bada.NameCardTransfer` (translucent, no dim,
 * no window animation) + [overrideOpenTransition] so the card floats OVER
 * whatever screen you were on; the only motion is the view-level choreography:
 * a soft expanding-glow cue ([playTriggerRipple]), a two-phase card entrance
 * ([twoPhaseEntrance]), and a rise-and-shrink exit ([playSendRipple]) — all
 * original ValueAnimator/ObjectAnimator tweens.
 *
 * Status: compile-verified on this box; the BLE exchange, contact save, and
 * visuals are device-verified only.
 */
@Suppress("TooManyFunctions", "LargeClass", "MagicNumber", "ReturnCount")
internal class NameCardTransferActivity : AppCompatActivity() {
    private var exchange: NameCardBleExchange? = null
    private var localCard: NameCard? = null

    /** Guards against a second Share/Receive-Only tap re-running the commit. */
    private var committed = false

    /** The live consent session (from [NameCardLinkHolder]). */
    private var v2Session: NameCardLinkHolder.Session? = null

    /** The peer's card once it arrives, so [NameCardConsentMachine.Effect.SaveCardAndRipple] can save it. */
    private var v2PeerCard: NameCard? = null

    /** True once a terminal state has been rendered, so effects/back-taps don't re-render it. */
    private var v2TerminalShown = false

    /** True while the consent heads-up notification is posted (so it's cancelled exactly once). */
    private var v2NotificationShown = false

    /** Which of your card's fields you share; toggled in the nameCardShareBox menu. Name is always shared. */
    private enum class ShareKey { PHONE, EMAIL }

    /** The currently-checked share fields (default: everything present). Filters what's transmitted. */
    private val selectedShares = linkedSetOf<ShareKey>()

    /**
     * Bridges the live [NameCardLinkHolder.Session] to this screen. The holder already delivers on the
     * main thread; the extra [runOnUiThread] is a cheap safety net.
     */
    private val v2Observer =
        object : NameCardLinkHolder.UiObserver {
            override fun onReady() = runOnUiThread { onV2Ready() }

            override fun onConsentEffect(effect: NameCardConsentMachine.Effect) = runOnUiThread { onV2Effect(effect) }

            override fun onPeerCard(card: NameCard) = runOnUiThread { v2PeerCard = card }
        }

    /** Forces Bluetooth on for the swap + the 5s helper heartbeat (client role); restored on destroy. */
    private val shareRadios by lazy { ShareRadioController(this, "NameCardTransfer") }
    private val btHandler = Handler(Looper.getMainLooper())
    private val ui = Handler(Looper.getMainLooper())

    /** Card awaiting save once the WRITE_CONTACTS prompt returns. Persisted across config changes. */
    private var pendingSaveCard: NameCard? = null

    /**
     * Contacts permissions fired on Accept: WRITE_CONTACTS to save directly (auto,
     * no extra screen) + READ_CONTACTS so the saved contact can be opened in the
     * Contacts app afterwards.
     */
    private val contactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val card = pendingSaveCard ?: return@registerForActivityResult
            pendingSaveCard = null
            persistCard(card, granted = result[Manifest.permission.WRITE_CONTACTS] == true)
        }

    private val root by lazy { findViewById<FrameLayout>(R.id.nameCardRoot) }

    /** nameCardCard — the whole card (avatar/name/fields + buttons); the single animated unit. */
    private val card by lazy { findViewById<View>(R.id.nameCardCard) }
    private val glow by lazy { findViewById<View>(R.id.nameCardGlow) }
    private val avatar by lazy { findViewById<TextView>(R.id.nameCardAvatar) }
    private val nameView by lazy { findViewById<TextView>(R.id.nameCardName) }
    private val phoneView by lazy { findViewById<TextView>(R.id.nameCardPhone) }
    private val emailView by lazy { findViewById<TextView>(R.id.nameCardEmail) }

    /** nameCardShareBox — the one rounded pill wrapping phone+email; tap opens the field-share menu. */
    private val shareBox by lazy { findViewById<LinearLayout>(R.id.nameCardShareBox) }

    /** nameCardShareChevron — the ▾ arrow on the pill; shown only on the own-card screen. */
    private val shareChevron by lazy { findViewById<TextView>(R.id.nameCardShareChevron) }
    private val connecting by lazy { findViewById<TextView>(R.id.nameCardConnecting) }
    private val primary by lazy { findViewById<Button>(R.id.nameCardPrimary) }
    private val secondary by lazy { findViewById<Button>(R.id.nameCardSecondary) }

    /** nameCardDone — full-width blue pill at the bottom, top-level sibling of the card; shown only on
     *  a terminal state (declined / no response / failed) and closes the screen. */
    private val done by lazy { findViewById<Button>(R.id.nameCardDone) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overrideOpenTransition() // kill the OEM window-open anim; only our view entrance plays
        // Full-screen NameDrop look: no action bar / title chrome.
        supportActionBar?.hide()
        setContentView(R.layout.activity_name_card_transfer)
        startGlowLoop()

        val recreating = savedInstanceState != null
        when {
            // Server: launched in-process by the service at tap; attach to the live consent session.
            // The extra is a bare role marker — all peer data flows through the holder.
            intent.getStringExtra(EXTRA_ROLE) == ROLE_SERVER_V2 -> setupServerV2()
            // Reader-side wake: AAR-launched by the OS with the token in the NDEF. On a config-change
            // recreation, re-attach to the live session instead of starting a second exchange.
            intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED &&
                recreating &&
                NameCardLinkHolder.current() != null -> reattachToLiveSession()
            intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED -> setupClientV2FromNdef()
            // Anything else (including any launch that tries to hand us peer data) is not a session.
            else -> {
                DiagnosticLog.w(TAG, "unsupported launch (no live session, no NDEF) → finish")
                finish()
            }
        }
        if (!isFinishing) savedInstanceState?.let { restoreInstanceState(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingSaveCard?.let { outState.putByteArray(STATE_PENDING_SAVE, it.serialize()) }
        outState.putStringArrayList(STATE_SELECTED_SHARES, ArrayList(selectedShares.map { it.name }))
        outState.putBoolean(STATE_COMMITTED, committed)
    }

    /** Restore the rotation-fragile bits: the awaiting-save card, share picks, and the commit latch. */
    private fun restoreInstanceState(state: Bundle) {
        state.getByteArray(STATE_PENDING_SAVE)?.let { bytes ->
            NameCard.parse(bytes)?.let { pendingSaveCard = it }
        }
        state.getStringArrayList(STATE_SELECTED_SHARES)?.let { names ->
            selectedShares.clear()
            names.mapNotNullTo(selectedShares) { name ->
                runCatching { ShareKey.valueOf(name) }.getOrNull()
            }
            applyShareDim()
            updateSessionShareCard()
        }
        committed = state.getBoolean(STATE_COMMITTED, false)
        if (committed) {
            primary.isEnabled = false
            secondary.isEnabled = false
            lockShareFields()
        }
    }

    /**
     * singleTop re-delivery (a second tap while this screen is open). The live session is
     * one-per-tap; deliberately ignore the new intent rather than tearing the session down mid-flow.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DiagnosticLog.w(TAG, "onNewIntent: second tap while a session is active → ignored")
    }

    /** Pull the first Name Card rendezvous token out of the OS-delivered NDEF messages, or null. */
    private fun readNameCardTokenFromIntent(): ByteArray? {
        @Suppress("DEPRECATION")
        val raw = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES) ?: return null
        for (parcelable in raw) {
            val msg = parcelable as? NdefMessage ?: continue
            NameCardNdef.parseToken(msg)?.let { return it }
        }
        return null
    }

    /**
     * Suppress the OS/OEM window open animation (e.g. OxygenOS launcher zoom) so the
     * ONLY motion is the view-level [twoPhaseEntrance]. Belt-and-suspenders with the
     * theme's windowAnimationStyle=@null.
     */
    private fun overrideOpenTransition() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }
    }

    /** SERVER: attach to the live session the service started at tap and render the consent screen. */
    private fun setupServerV2() {
        val session = NameCardLinkHolder.current()
        if (session == null) {
            DiagnosticLog.w(TAG, "server screen: no live session → finish")
            finish()
            return
        }
        v2Session = session
        session.peerCard?.let { v2PeerCard = it }
        localCard = session.localCard
        attachAndShowV2()
    }

    /** Config-change recreation (client role): re-attach to the live session; never start a new one. */
    private fun reattachToLiveSession() {
        val session = NameCardLinkHolder.current()
        if (session == null) {
            finish()
            return
        }
        DiagnosticLog.w(TAG, "recreated → re-attaching to the live session")
        v2Session = session
        session.peerCard?.let { v2PeerCard = it }
        localCard = session.localCard
        attachAndShowV2()
    }

    /** CLIENT (AAR wake): start the consent client for the NDEF token, then render the same screen. */
    private fun setupClientV2FromNdef() {
        val token = readNameCardTokenFromIntent()
        if (token == null) {
            DiagnosticLog.w(TAG, "NDEF launch: no Name Card token → finish")
            finish()
            return
        }
        localCard =
            NameCardResolver(
                storedCard = NameCardProfileStore.from(this)::load,
                shareSelection = NameCardProfileStore.from(this)::shareSelection,
            ).resolve()
        val ble = NameCardBleExchange(this)
        exchange = ble
        v2Session = NameCardLinkHolder.startSession(ble, NameCardLinkHolder.Role.CLIENT, localCard)
        attachAndShowV2()
        shareRadios.requestRadiosOn(RadioHelperClient.RADIO_BT)
        startClientWhenBtReadyV2(ble, token, attempt = 0)
    }

    private fun startClientWhenBtReadyV2(
        ble: NameCardBleExchange,
        token: ByteArray,
        attempt: Int,
    ) {
        if (isFinishing) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true && attempt < MAX_BT_WAIT_ATTEMPTS) {
            btHandler.postDelayed({ startClientWhenBtReadyV2(ble, token, attempt + 1) }, BT_GRACE_MS)
            return
        }
        val session = v2Session ?: return
        if (!ble.startClientV2(token, session)) {
            v2ShowTerminal(getString(R.string.name_card_transfer_failed))
        }
    }

    /** Shared screen setup: bind our card, wire the two buttons (disabled until ready), start the 30s timer. */
    private fun attachAndShowV2() {
        v2Session?.uiObserver = v2Observer
        localCard?.let { bindCard(it) }
        setupShareFieldPicker()
        connecting.visibility = View.GONE
        // nameCardPrimary → "Share"; nameCardSecondary → "Receive Only". Disabled until onV2Ready().
        primary.text = getString(R.string.name_card_transfer_share)
        secondary.text = getString(R.string.name_card_transfer_receive_only)
        primary.isEnabled = false
        secondary.isEnabled = false
        primary.setOnClickListener { onV2LocalChoice(share = true) }
        secondary.setOnClickListener { onV2LocalChoice(share = false) }
        playTriggerRipple { revealAndEnter() }
        // Catch up with a link that became ready before this (re)attachment.
        if (v2Session?.linkReady == true) onV2Ready()
        // 30s no-response timer (the machine has none of its own).
        ui.postDelayed({ v2Session?.onTimeout() }, V2_TIMEOUT_MS)
    }

    /** Link is ready to carry a choice → enable the buttons. */
    private fun onV2Ready() {
        if (v2TerminalShown || committed) return
        primary.isEnabled = true
        secondary.isEnabled = true
        DiagnosticLog.w(TAG, "link ready → buttons enabled")
    }

    /**
     * A choice was tapped. The commit latch flips and both buttons disable SYNCHRONOUSLY, before any
     * animation or session work, so a racing second tap can never double-fire. A Share whose
     * field-filter fails aborts WITHOUT committing (fail closed — never fall back to the full card).
     */
    private fun onV2LocalChoice(share: Boolean) {
        if (committed) return
        if (share && localCard != null) {
            val filtered = filteredShareCard()
            if (filtered == null) {
                DiagnosticLog.w(TAG, "share filter failed → aborting the share (nothing sent)")
                Toast.makeText(this, R.string.name_card_share_error, Toast.LENGTH_LONG).show()
                return
            }
            v2Session?.shareCard = filtered
        }
        committed = true
        primary.isEnabled = false
        secondary.isEnabled = false
        lockShareFields() // freeze which fields are shared once you've chosen
        pressAnim(if (share) primary else secondary)
        val session = v2Session ?: return
        if (share) session.onLocalShare() else session.onLocalReceiveOnly()
    }

    /** Render a machine effect on the existing views. BLE effects (SendChoice/TransmitCard) are the holder's. */
    private fun onV2Effect(effect: NameCardConsentMachine.Effect) {
        // Class name only — an effect's fields must never reach the shareable diagnostic log.
        DiagnosticLog.w(TAG, "consent effect: ${effect.javaClass.simpleName}")
        when (effect) {
            // Buttons already disabled after the tap; waiting is conveyed by that + the heads-up.
            NameCardConsentMachine.Effect.ShowWaiting -> Unit
            NameCardConsentMachine.Effect.ShowHeadsUpWaiting ->
                v2HeadsUp(getString(R.string.name_card_consent_headsup_waiting))
            NameCardConsentMachine.Effect.SaveCardAndRipple -> v2SaveReceived()
            NameCardConsentMachine.Effect.UpdateHeadsUpDeclined -> {
                v2HeadsUp(getString(R.string.name_card_consent_headsup_declined))
                v2ShowTerminal(getString(R.string.name_card_transfer_declined))
            }
            NameCardConsentMachine.Effect.FadeToDeclined ->
                v2ShowTerminal(getString(R.string.name_card_transfer_declined))
            NameCardConsentMachine.Effect.ShowNoResponse ->
                v2ShowTerminal(getString(R.string.name_card_transfer_no_response))
            NameCardConsentMachine.Effect.CloseLink -> v2CancelHeadsUp()
            else -> Unit // SendChoice / TransmitCard handled by the holder → BLE
        }
    }

    /** Peer shared: their card arrived — play the send ripple, save it, open the contact. */
    private fun v2SaveReceived() {
        v2CancelHeadsUp()
        val peer = v2PeerCard
        if (peer == null) {
            DiagnosticLog.w(TAG, "SaveCardAndRipple but no peer card yet")
            return
        }
        if (v2TerminalShown) return
        v2TerminalShown = true
        playSendRipple()
        ui.postDelayed({ if (!isFinishing) saveAndFinish(peer) }, Anim.SENT_RIPPLE_MS + Anim.AUTO_OPEN_DELAY_MS)
    }

    /**
     * Terminal state: fade the whole card out (300ms tween), show [message] centered
     * (nameCardConnecting), and raise the full-width Done (nameCardDone). Both the message and Done
     * are top-level siblings of the card, so they render even if the card was never revealed.
     */
    private fun v2ShowTerminal(message: String) {
        if (v2TerminalShown) return
        v2TerminalShown = true
        v2CancelHeadsUp()
        if (card.visibility == View.VISIBLE) {
            card
                .animate()
                .alpha(0f)
                .setDuration(DECLINE_FADE_MS)
                .withEndAction { card.visibility = View.INVISIBLE }
                .start()
        } else {
            card.visibility = View.INVISIBLE
        }
        connecting.text = message
        connecting.visibility = View.VISIBLE
        done.visibility = View.VISIBLE
        done.setOnClickListener { finish() }
    }

    /** Post / update the consent heads-up ("Waiting for the other person…" → "They declined…"). */
    private fun v2HeadsUp(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            DiagnosticLog.w(TAG, "POST_NOTIFICATIONS not granted → skip heads-up (in-app state still shows)")
            return
        }
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CONSENT_CHANNEL_ID,
                    getString(R.string.name_card_consent_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val notification =
            NotificationCompat
                .Builder(this, CONSENT_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.nfc_namecard_hce_service_description))
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(false)
                .build()
        mgr.notify(CONSENT_NOTIFICATION_ID, notification)
        v2NotificationShown = true
    }

    private fun v2CancelHeadsUp() {
        if (!v2NotificationShown) return
        getSystemService(NotificationManager::class.java)?.cancel(CONSENT_NOTIFICATION_ID)
        v2NotificationShown = false
    }

    // ---- share-field picker ----

    /**
     * Set up the `nameCardShareBox` pill (own card): seed [selectedShares] to every present
     * field (share everything by default), reveal the ▾ chevron, and make the WHOLE pill open the
     * checkbox menu. Hidden if the card has neither phone nor email (nothing to pick).
     */
    private fun setupShareFieldPicker() {
        val card = localCard
        val hasPhone = card?.phoneNumber?.isNotBlank() == true
        val hasEmail = card?.email?.isNotBlank() == true
        if (!hasPhone && !hasEmail) {
            shareBox.visibility = View.GONE
            return
        }
        selectedShares.clear()
        if (hasPhone) selectedShares.add(ShareKey.PHONE)
        if (hasEmail) selectedShares.add(ShareKey.EMAIL)
        shareChevron.visibility = View.VISIBLE
        applyShareDim()
        updateSessionShareCard()
        shareBox.setOnClickListener { showShareFieldMenu(shareBox) }
    }

    /** Build + show the checkbox pop-up ABOVE the pill: one row per present field, blue checks. */
    private fun showShareFieldMenu(anchor: View) {
        val card = localCard ?: return
        val d = resources.displayMetrics.density
        val menu =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background =
                    GradientDrawable().apply {
                        cornerRadius = 16 * d
                        setColor(Color.WHITE)
                    }
                elevation = 24 * d
                val vp = (8 * d).toInt()
                setPadding(0, vp, 0, vp)
            }
        val rows = mutableListOf<Pair<ShareKey, String>>()
        card.phoneNumber?.takeIf { it.isNotBlank() }?.let { rows.add(ShareKey.PHONE to it) }
        card.email?.takeIf { it.isNotBlank() }?.let { rows.add(ShareKey.EMAIL to it) }
        for ((i, entry) in rows.withIndex()) {
            val (key, label) = entry
            val cb =
                CheckBox(this).apply {
                    isChecked = selectedShares.contains(key)
                    isClickable = false // the row handles the toggle
                    buttonTintList = ColorStateList.valueOf(SHARE_CHECK_COLOR)
                }
            val text =
                TextView(this).apply {
                    this.text = label
                    textSize = 16f
                    setTextColor(SHARE_MENU_TEXT_COLOR)
                    layoutParams =
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            leftMargin = (10 * d).toInt()
                        }
                }
            val row =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((14 * d).toInt(), (12 * d).toInt(), (18 * d).toInt(), (12 * d).toInt())
                    addView(cb)
                    addView(text)
                    setOnClickListener {
                        toggleShare(key)
                        cb.isChecked = selectedShares.contains(key)
                    }
                }
            menu.addView(row)
            if (i < rows.size - 1) {
                menu.addView(
                    View(this).apply {
                        layoutParams =
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, (0.5f * d).toInt()))
                        setBackgroundColor(0x1F000000)
                    },
                )
            }
        }
        val menuW = (240 * d).toInt()
        val pop = PopupWindow(menu, menuW, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pop.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pop.elevation = 24 * d
        menu.measure(
            View.MeasureSpec.makeMeasureSpec(menuW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val menuH = menu.measuredHeight
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val x = loc[0] + anchor.width / 2 - menuW / 2
        val y = loc[1] - menuH - (8 * d).toInt()
        pop.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    }

    /** Toggle a field in/out of [selectedShares]; refuse to leave a card with zero fields when it has no name. */
    private fun toggleShare(key: ShareKey) {
        if (selectedShares.contains(key)) {
            if (selectedShares.size == 1 && localCard?.displayName.isNullOrBlank()) {
                return // a NameCard needs at least one of name/phone/email
            }
            selectedShares.remove(key)
        } else {
            selectedShares.add(key)
        }
        applyShareDim()
        updateSessionShareCard()
    }

    /** Dim the phone/email lines that aren't currently selected to share. */
    private fun applyShareDim() {
        phoneView.alpha = if (ShareKey.PHONE in selectedShares) 1f else SHARE_DIM_ALPHA
        emailView.alpha = if (ShareKey.EMAIL in selectedShares) 1f else SHARE_DIM_ALPHA
    }

    /** Push the filtered card onto the live session so `TransmitCard` sends only the checked fields. */
    private fun updateSessionShareCard() {
        v2Session?.shareCard = filteredShareCard()
    }

    /**
     * The stored card reduced to the checked fields, or `null` when the filter cannot produce a
     * valid card. FAIL CLOSED: a `null` here means nothing is transmitted — the full card is never
     * used as a fallback (the user deselected those fields for a reason).
     */
    private fun filteredShareCard(): NameCard? {
        val base = localCard ?: return null
        return runCatching {
            NameCard(
                version = base.version,
                displayName = base.displayName,
                phoneNumber = if (ShareKey.PHONE in selectedShares) base.phoneNumber else null,
                email = if (ShareKey.EMAIL in selectedShares) base.email else null,
                extraFields = base.extraFields,
            )
        }.getOrElse {
            DiagnosticLog.w(TAG, "share filter produced an invalid card → sharing NOTHING (fail closed)")
            null
        }
    }

    /** After a choice is committed, freeze the selection: the pill no longer opens the menu. */
    private fun lockShareFields() {
        shareBox.setOnClickListener(null)
        shareBox.isClickable = false
    }

    // ---- entrance / exit / ripple (original ValueAnimator/ObjectAnimator tweens) ----

    /** Make the card visible and play the two-phase entrance once it has been laid out. */
    private fun revealAndEnter() {
        card.visibility = View.VISIBLE
        card.post { twoPhaseEntrance(card) }
    }

    /**
     * TWO-PHASE entrance: the small card DESCENDS (decelerate) from above to a stop
     * point, then EXPANDS in place from the start scale to full size (accelerate,
     * factor [Anim.EXPAND_EASE]) around a near-top pivot ([Anim.EXPAND_PIVOT_Y_FRAC]).
     * No overlap between the two phases. A tween, NOT a physics bounce.
     */
    private fun twoPhaseEntrance(v: View) {
        val screenH = v.rootView.height
        val h = v.height.toFloat()
        val restCenterY = h / 2f
        val fromY = Anim.START_CENTER_Y_FRAC * screenH - restCenterY // start above the top
        val stopY = Anim.STOP_FRAC * screenH - restCenterY // phase-1 stop (0.5 → 0 = centered rest)
        val s0 = Anim.SCALE_FROM
        v.pivotX = v.width * 0.5f
        v.pivotY = h * Anim.EXPAND_PIVOT_Y_FRAC
        v.scaleX = s0
        v.scaleY = s0
        v.translationY = fromY
        v.alpha = 1f
        // PHASE 1 — descend and slow to a stop (no scaling yet).
        ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, fromY, stopY).apply {
            duration = Anim.DESCENT_MS
            interpolator = DecelerateInterpolator()
            start()
        }
        // PHASE 2 — after the descent, expand in place from s0 to full size.
        val ease = AccelerateInterpolator(Anim.EXPAND_EASE.coerceAtLeast(0.1f))
        ObjectAnimator.ofFloat(v, View.SCALE_X, s0, 1f).apply {
            startDelay = Anim.DESCENT_MS
            duration = Anim.EXPAND_MS
            interpolator = ease
            start()
        }
        ObjectAnimator.ofFloat(v, View.SCALE_Y, s0, 1f).apply {
            startDelay = Anim.DESCENT_MS
            duration = Anim.EXPAND_MS
            interpolator = ease
            start()
        }
    }

    /**
     * PRE-ENTRANCE cue — a soft circular glow that expands and fades from the center of the screen
     * (an original scale+alpha tween on a plain oval, no shader). Always calls [onDone] so the flow
     * never stalls.
     */
    private fun playTriggerRipple(onDone: () -> Unit) {
        val d = resources.displayMetrics.density
        val size = (TRIGGER_RIPPLE_BASE_DP * d).toInt()
        val ring =
            View(this).apply {
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(TRIGGER_RIPPLE_COLOR)
                    }
                layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
                alpha = 0f
                scaleX = TRIGGER_RIPPLE_SCALE_FROM
                scaleY = TRIGGER_RIPPLE_SCALE_FROM
            }
        root.addView(ring)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Anim.TRIGGER_GLOW_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                val s = TRIGGER_RIPPLE_SCALE_FROM + (TRIGGER_RIPPLE_SCALE_TO - TRIGGER_RIPPLE_SCALE_FROM) * t
                ring.scaleX = s
                ring.scaleY = s
                ring.alpha = (1f - t) * TRIGGER_RIPPLE_MAX_ALPHA
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        root.removeView(ring)
                        onDone()
                    }
                },
            )
            start()
        }
    }

    /**
     * SEND cue — the card rises, shrinks, and fades out (an original translation+scale+alpha tween
     * on the card itself, no snapshot or shader). A no-op if the card was never revealed; the
     * caller's completion runs on its own timer either way.
     */
    private fun playSendRipple() {
        if (card.visibility != View.VISIBLE || card.height <= 0) return
        card
            .animate()
            .translationYBy(-card.height * SEND_RISE_FRAC)
            .scaleX(SEND_SHRINK_SCALE)
            .scaleY(SEND_SHRINK_SCALE)
            .alpha(0f)
            .setDuration(Anim.SENT_RIPPLE_MS)
            .setInterpolator(AccelerateInterpolator(1.2f))
            .withEndAction { card.visibility = View.INVISIBLE }
            .start()
    }

    /** Button tap feedback: a quick pop-EXPAND (scale up past 1 and back). */
    private fun pressAnim(v: View) {
        val sx = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, Anim.BUTTON_PRESS_SCALE, 1f)
        val sy = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, Anim.BUTTON_PRESS_SCALE, 1f)
        ObjectAnimator.ofPropertyValuesHolder(v, sx, sy).apply {
            duration = Anim.BUTTON_PRESS_MS
            start()
        }
    }

    private fun saveAndFinish(card: NameCard) {
        if (NameCardSaver.hasWritePermission(this)) {
            persistCard(card, granted = true)
        } else {
            pendingSaveCard = card
            contactsPermission.launch(
                arrayOf(Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS),
            )
        }
    }

    /**
     * Save [card] and finish. With permission: a direct ContactsContract insert off
     * the UI thread, then OPEN the saved contact in the Contacts app. Without: the
     * system Add-contact screen. [persistCard] owns the finish in every branch.
     */
    private fun persistCard(
        card: NameCard,
        granted: Boolean,
    ) {
        if (!granted) {
            runCatching { startActivity(NameCardSaver.systemInsertIntent(card)) }
            finish()
            return
        }
        val appCtx = applicationContext
        Thread {
            val contactUri = NameCardSaver.saveDirect(appCtx, card)
            runOnUiThread {
                contactUri?.let { runCatching { startActivity(Intent(Intent.ACTION_VIEW, it)) } }
                finish()
            }
        }.start()
    }

    /** Bind [card]'s fields into the panel (card left INVISIBLE until [revealAndEnter]). */
    private fun bindCard(card: NameCard) {
        avatar.text = (card.displayName ?: card.phoneNumber ?: "?").trim().take(1).uppercase()
        nameView.text = card.displayName ?: getString(R.string.name_card_transfer_no_name)
        bindOptional(phoneView, card.phoneNumber)
        bindOptional(emailView, card.email)
    }

    private fun bindOptional(
        view: TextView,
        value: String?,
    ) {
        if (value.isNullOrBlank()) {
            view.visibility = View.GONE
        } else {
            view.visibility = View.VISIBLE
            view.text = value
        }
    }

    /** Top "light beam" glow: a looping fade in/out via a tween (no physics spec). */
    private fun startGlowLoop() {
        ObjectAnimator.ofFloat(glow, View.ALPHA, GLOW_MIN, GLOW_MAX).apply {
            duration = GLOW_MS
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    override fun onDestroy() {
        btHandler.removeCallbacksAndMessages(null)
        ui.removeCallbacksAndMessages(null)
        v2CancelHeadsUp()
        // Detach from the live session so we don't leak the activity; the client-side exchange is
        // ours to stop (below), the server-side one is owned by NameCardExchangeService. On a
        // config-change recreate (isFinishing false) keep the session + radios for the new instance.
        v2Session?.uiObserver = null
        v2Session = null
        if (isFinishing) {
            exchange?.stop()
        }
        exchange = null
        shareRadios.restoreRadios(finishSession = isFinishing)
        super.onDestroy()
    }

    /** Baked animation constants. Times in ms. */
    private object Anim {
        // Two-phase entrance / reverse-exit.
        const val START_CENTER_Y_FRAC = -0.3f // card center starts this frac of screen height (above top)
        const val SCALE_FROM = 0.09f // start scale (proportional miniature)
        const val STOP_FRAC = 0.5f // phase-1 stop: center at 0.5 → centered full-screen rest
        const val DESCENT_MS = 500L
        const val EXPAND_MS = 700L
        const val EXPAND_EASE = 1.3f // AccelerateInterpolator factor
        const val EXPAND_PIVOT_Y_FRAC = 0.08f // expansion origin near the TOP of the card

        // Ripple cues.
        const val TRIGGER_GLOW_MS = 1100L
        const val SENT_RIPPLE_MS = 1200L
        const val AUTO_OPEN_DELAY_MS = 200L

        // Button feedback.
        const val BUTTON_PRESS_MS = 200L
        const val BUTTON_PRESS_SCALE = 1.1f
    }

    companion object {
        private const val TAG = "NameCardTransfer"
        private const val EXTRA_ROLE = "role"
        private const val ROLE_SERVER_V2 = "server_v2"

        private const val STATE_PENDING_SAVE = "state_pending_save_card"
        private const val STATE_SELECTED_SHARES = "state_selected_shares"
        private const val STATE_COMMITTED = "state_committed"

        private const val GLOW_MS = 1100L
        private const val GLOW_MIN = 0.35f
        private const val GLOW_MAX = 1.0f
        private const val BT_GRACE_MS = 1_500L
        private const val MAX_BT_WAIT_ATTEMPTS = 2

        private const val V2_TIMEOUT_MS = 30_000L
        private const val DECLINE_FADE_MS = 300L
        private const val CONSENT_CHANNEL_ID = "namecard_consent"
        private const val CONSENT_NOTIFICATION_ID = 4311

        // Share-field picker (nameCardShareBox menu).
        private const val SHARE_CHECK_COLOR = 0xFF0A84FF.toInt() // blue check tint
        private const val SHARE_MENU_TEXT_COLOR = 0xFF1C1C1E.toInt() // near-black menu label
        private const val SHARE_DIM_ALPHA = 0.35f // unchecked phone/email line dim

        // Trigger-glow cue geometry.
        private const val TRIGGER_RIPPLE_BASE_DP = 160
        private const val TRIGGER_RIPPLE_SCALE_FROM = 0.3f
        private const val TRIGGER_RIPPLE_SCALE_TO = 3.5f
        private const val TRIGGER_RIPPLE_MAX_ALPHA = 0.45f
        private const val TRIGGER_RIPPLE_COLOR = 0x66FFFFFF

        // Send-cue geometry.
        private const val SEND_RISE_FRAC = 0.6f
        private const val SEND_SHRINK_SCALE = 0.7f

        /**
         * Server side: open the symmetric-consent screen at tap. No peer data — the screen attaches
         * to the live [NameCardLinkHolder] session the [NameCardExchangeService] started and receives
         * the peer card + consent effects through it, never through extras.
         */
        fun serverV2Intent(context: Context): Intent =
            Intent(context, NameCardTransferActivity::class.java)
                .putExtra(EXTRA_ROLE, ROLE_SERVER_V2)
    }
}
