/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.onboarding

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import dev.bluehouse.libredrop.R
import dev.bluehouse.libredrop.bugreport.BugReportFlowSupport
import dev.bluehouse.libredrop.databinding.ActivityPermissionsOnboardingBinding
import dev.bluehouse.libredrop.databinding.ItemPermissionRowBinding

/**
 * Single-screen onboarding for the runtime permissions Phase 1 needs.
 *
 * Responsibilities:
 *   1. Show every required runtime permission with rationale text so the
 *      user understands **why** Quick Share over LAN needs it.
 *   2. Drive the system permission prompt for any not-yet-granted ones.
 *   3. Surface post-grant state inline (granted / denied / optional-denied)
 *      so the user can see at a glance what still needs attention.
 *   4. Let the user proceed once mandatory permissions are granted —
 *      `POST_NOTIFICATIONS` denials are non-blocking (degraded mode).
 *
 * Pre-33 devices have no runtime permissions to request; the activity
 * detects that path and shows a "you're all set" state instead of a
 * permission grid.
 */
class PermissionsOnboardingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPermissionsOnboardingBinding
    private lateinit var bugReportFlowSupport: BugReportFlowSupport
    private val rowsByPermission: MutableMap<String, ItemPermissionRowBinding> = mutableMapOf()

    /**
     * True once the user has tapped "Grant permissions" at least once
     * in this activity instance. Used to distinguish a true first-run
     * request (where `shouldShowRequestPermissionRationale` returning
     * false means "first ever ask") from a permanent denial (where the
     * same flag means "Don't ask again").
     */
    private var hasRequestedOnce: Boolean = false

    /**
     * True once the user has scrolled the content area to its end —
     * the activity unlocks the pinned bottom buttons only after this
     * gate flips. Persisted across configuration changes so a rotate
     * does not re-lock buttons the user has already earned. Latches
     * once true; we never disable the buttons again on a re-scroll
     * up, which would feel punitive.
     *
     * For short pages (no scroll needed), [setupScrollGate] flips this
     * to true on the first layout pass via a `post`-deferred check, so
     * the gate is never a UX trap on devices that have nothing to
     * render in the permission grid.
     */
    private var scrolledToEnd: Boolean = false

    /**
     * Multi-permission request launcher. We request the full set in one
     * dialog so the user does not get a chain of system prompts.
     */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            // Re-sync every row from the system. We cannot trust the
            // results map alone because permissions that were already
            // granted at launch time are absent from it.
            refreshAllRows()
            // Surface the Settings shortcut for any permission that came
            // back denied without the rationale flag — that's the
            // platform's "Don't ask again" signal.
            val permanentlyDenied =
                results.any { (permission, granted) ->
                    !granted && !shouldShowRequestPermissionRationale(permission)
                }
            if (permanentlyDenied) {
                binding.onboardingSettingsButton.visibility = View.VISIBLE
            }
            applyButtonStates()

            // Auto-advance once the mandatory permission set is
            // satisfied. Mirrors the MainActivity onStart gate (all
            // granted OR only optional left) so the user does not
            // have to tap a separate "Continue" button when the grant
            // they just confirmed already covered everything that
            // blocks the launcher. Optional-only denials (e.g.
            // POST_NOTIFICATIONS) are non-blocking by design — the
            // launcher runs in degraded notification mode rather than
            // re-prompting forever.
            if (PermissionRequirements.allGranted(this) ||
                PermissionRequirements.onlyOptionalMissing(this)
            ) {
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionsOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bugReportFlowSupport = BugReportFlowSupport.install(this)

        // Restore launch state across configuration changes so we don't
        // mistakenly treat a rotation as a fresh first run.
        savedInstanceState?.let {
            hasRequestedOnce = it.getBoolean(STATE_HAS_REQUESTED_ONCE, false)
            if (it.getBoolean(STATE_SETTINGS_BUTTON_VISIBLE, false)) {
                // Visibility is restored after the views are inflated.
                binding.onboardingSettingsButton.visibility = View.VISIBLE
            }
            scrolledToEnd = it.getBoolean(STATE_SCROLLED_TO_END, false)
        }

        renderPermissionRows()
        binding.onboardingGrantButton.setOnClickListener { onGrantClicked() }
        binding.onboardingContinueButton.setOnClickListener { finish() }
        binding.onboardingSettingsButton.setOnClickListener { openAppSettings() }
        // The same-Wi-Fi-network card (#85) is dismissable so the user
        // can collapse it after reading. Dismissal is purely visual —
        // we re-show the card on every onCreate so the requirement
        // resurfaces if the user revisits onboarding from settings.
        binding.onboardingNetworkCardDismiss.setOnClickListener {
            binding.onboardingNetworkCard.visibility = View.GONE
        }

        setupScrollGate()
        applyButtonStates()
    }

    /**
     * Wire the scroll-to-end gate that controls when the pinned
     * bottom buttons unlock. Two paths to flip [scrolledToEnd]:
     *
     *   1. The user scrolls down and reaches the bottom of the
     *      NestedScrollView's child. We detect this by asking the
     *      scroll view whether it can still scroll downward; once
     *      it cannot, the gate flips.
     *   2. The content fits without scrolling at all (e.g. pre-33
     *      device with an empty permission grid, or a short
     *      configuration on a tall screen). The deferred `post`
     *      runs after the first layout pass, by which point the
     *      scroll view's `canScrollVertically(1)` reflects real
     *      measurements; if no scrolling is possible we flip the
     *      gate immediately so the user is not stuck with disabled
     *      buttons on a screen with nothing to scroll.
     */
    private fun setupScrollGate() {
        val scroll = binding.onboardingScroll
        scroll.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { v, _, _, _, _ ->
                if (!scrolledToEnd && !v.canScrollVertically(1)) {
                    scrolledToEnd = true
                    applyButtonStates()
                }
            },
        )
        scroll.post {
            if (!scrolledToEnd && !scroll.canScrollVertically(1)) {
                scrolledToEnd = true
                applyButtonStates()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_HAS_REQUESTED_ONCE, hasRequestedOnce)
        outState.putBoolean(
            STATE_SETTINGS_BUTTON_VISIBLE,
            binding.onboardingSettingsButton.visibility == View.VISIBLE,
        )
        outState.putBoolean(STATE_SCROLLED_TO_END, scrolledToEnd)
    }

    override fun onResume() {
        super.onResume()
        // The user may have toggled permissions in system Settings while
        // we were paused, so re-sync state every time we come back.
        refreshAllRows()
        applyButtonStates()
    }

    private fun renderPermissionRows() {
        val requirements = PermissionRequirements.requirementsFor()
        val container: LinearLayout = binding.onboardingPermissionList
        container.removeAllViews()
        rowsByPermission.clear()

        if (requirements.isEmpty()) {
            // Pre-33 devices: nothing to request. Show a friendly empty
            // state and hide the grant button so the only action is
            // "continue".
            binding.onboardingEmptyState.visibility = View.VISIBLE
            binding.onboardingGrantButton.visibility = View.GONE
            return
        }

        binding.onboardingEmptyState.visibility = View.GONE
        binding.onboardingGrantButton.visibility = View.VISIBLE

        val inflater = LayoutInflater.from(this)
        for (requirement in requirements) {
            val row =
                ItemPermissionRowBinding.inflate(inflater, container, false).also {
                    it.permissionRowTitle.setText(requirement.titleRes)
                    it.permissionRowRationale.setText(requirement.rationaleRes)
                }
            container.addView(row.root)
            rowsByPermission[requirement.permission] = row
            renderRowState(requirement, row)
        }
    }

    private fun refreshAllRows() {
        for (requirement in PermissionRequirements.requirementsFor()) {
            val row = rowsByPermission[requirement.permission] ?: continue
            renderRowState(requirement, row)
        }
    }

    private fun renderRowState(
        requirement: PermissionRequirements.Requirement,
        row: ItemPermissionRowBinding,
    ) {
        val granted =
            ContextCompat.checkSelfPermission(this, requirement.permission) ==
                PackageManager.PERMISSION_GRANTED
        val statusView: TextView = row.permissionRowStatus
        if (granted) {
            statusView.setText(requirement.grantedRes)
            statusView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            statusView.setText(requirement.deniedRes)
            statusView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
        }
    }

    private fun onGrantClicked() {
        val missing = PermissionRequirements.missingPermissions(this)
        if (missing.isEmpty()) {
            applyButtonStates()
            return
        }

        // If we've already asked at least once and the platform still
        // says rationale is not appropriate for any missing permission,
        // the user has hit "Don't ask again". The only remaining path
        // is the system Settings screen, so reveal that shortcut now —
        // we still issue the launch in case the platform decides to
        // re-prompt.
        if (hasRequestedOnce) {
            val permanentlyDenied =
                missing.any { permission -> !shouldShowRequestPermissionRationale(permission) }
            if (permanentlyDenied) {
                binding.onboardingSettingsButton.visibility = View.VISIBLE
            }
        }
        hasRequestedOnce = true
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun openAppSettings() {
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        startActivity(intent)
    }

    /**
     * Apply the scroll-to-end gate to the pinned button row. Every
     * button stays disabled until the user has scrolled the rationale
     * content to the end (or the activity has determined no scrolling
     * was needed). This is the visible affordance: read first, then
     * act.
     *
     * Visibility decisions (e.g. the Settings shortcut appearing
     * after a permanent denial) live elsewhere; this function only
     * touches `isEnabled`. The Continue label is set once in
     * [onCreate] via the layout's `android:text` attribute and never
     * changes — the success path (mandatory grants satisfied)
     * auto-finishes the activity from [permissionLauncher], so this
     * button is only ever the "skip without granting" action.
     */
    private fun applyButtonStates() {
        val gateOpen = scrolledToEnd
        binding.onboardingGrantButton.isEnabled = gateOpen
        binding.onboardingSettingsButton.isEnabled = gateOpen
        binding.onboardingContinueButton.isEnabled = gateOpen
    }

    private companion object {
        const val STATE_HAS_REQUESTED_ONCE = "libredrop.onboarding.hasRequestedOnce"
        const val STATE_SETTINGS_BUTTON_VISIBLE = "libredrop.onboarding.settingsButtonVisible"
        const val STATE_SCROLLED_TO_END = "libredrop.onboarding.scrolledToEnd"
    }
}
