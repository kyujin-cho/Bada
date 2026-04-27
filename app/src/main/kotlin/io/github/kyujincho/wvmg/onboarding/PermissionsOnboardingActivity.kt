/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.onboarding

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
import io.github.kyujincho.wvmg.R
import io.github.kyujincho.wvmg.databinding.ActivityPermissionsOnboardingBinding
import io.github.kyujincho.wvmg.databinding.ItemPermissionRowBinding

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
            updateContinueButton()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionsOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Restore launch state across configuration changes so we don't
        // mistakenly treat a rotation as a fresh first run.
        savedInstanceState?.let {
            hasRequestedOnce = it.getBoolean(STATE_HAS_REQUESTED_ONCE, false)
            if (it.getBoolean(STATE_SETTINGS_BUTTON_VISIBLE, false)) {
                // Visibility is restored after the views are inflated.
                binding.onboardingSettingsButton.visibility = View.VISIBLE
            }
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
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_HAS_REQUESTED_ONCE, hasRequestedOnce)
        outState.putBoolean(
            STATE_SETTINGS_BUTTON_VISIBLE,
            binding.onboardingSettingsButton.visibility == View.VISIBLE,
        )
    }

    override fun onResume() {
        super.onResume()
        // The user may have toggled permissions in system Settings while
        // we were paused, so re-sync state every time we come back.
        refreshAllRows()
        updateContinueButton()
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
            updateContinueButton()
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

    private fun updateContinueButton() {
        // The continue button is always enabled — the user can leave
        // onboarding even with denials, and the service-start gate
        // (issue #21) re-checks at runtime. Optional-only denials show
        // a softer label so the user knows they can proceed but with
        // degraded notifications.
        binding.onboardingContinueButton.isEnabled = true
        binding.onboardingContinueButton.setText(
            when {
                PermissionRequirements.allGranted(this) -> R.string.onboarding_continue
                PermissionRequirements.onlyOptionalMissing(this) -> R.string.onboarding_continue_degraded
                else -> R.string.onboarding_continue_partial
            },
        )
    }

    private companion object {
        const val STATE_HAS_REQUESTED_ONCE = "wvmg.onboarding.hasRequestedOnce"
        const val STATE_SETTINGS_BUTTON_VISIBLE = "wvmg.onboarding.settingsButtonVisible"
    }
}
