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
     * Multi-permission request launcher. We request the full set in one
     * dialog so the user does not get a chain of system prompts.
     */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            // The result map is the source of truth for the rows we just
            // asked about. For any permission that was already granted
            // before launch (and so was not re-requested) we still need
            // to refresh the row from the system, hence refreshAllRows().
            results.forEach { (permission, _) -> refreshRow(permission) }
            refreshAllRows()
            updateContinueButton()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionsOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        renderPermissionRows()
        binding.onboardingGrantButton.setOnClickListener { onGrantClicked() }
        binding.onboardingContinueButton.setOnClickListener { finish() }
        binding.onboardingSettingsButton.setOnClickListener { openAppSettings() }
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

    private fun refreshRow(permission: String) {
        val requirement =
            PermissionRequirements
                .requirementsFor()
                .firstOrNull { it.permission == permission } ?: return
        val row = rowsByPermission[permission] ?: return
        renderRowState(requirement, row)
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

        // If the system has stopped showing the rationale dialog (user
        // tapped "Don't ask again"), `shouldShowRequestPermissionRationale`
        // returns false even though the permission isn't granted. In that
        // case the only path forward is the system Settings screen, so we
        // surface that button to the user.
        val needsSettingsFallback =
            missing.any { permission ->
                !shouldShowRequestPermissionRationale(permission) &&
                    !isFirstTimeRequest(permission)
            }
        if (needsSettingsFallback) {
            binding.onboardingSettingsButton.visibility = View.VISIBLE
        }
        permissionLauncher.launch(missing.toTypedArray())
    }

    /**
     * Heuristic for "have we ever asked for this permission before?".
     * The Android platform doesn't expose this directly, so we check
     * `shouldShowRequestPermissionRationale` against a sentinel:
     *   * granted   -> not first time
     *   * denied with rationale flag false on the very first launch is
     *     indistinguishable from "permanent deny"; we accept that minor
     *     UX wrinkle — at worst we show the Settings shortcut one
     *     prompt earlier than strictly necessary.
     */
    private fun isFirstTimeRequest(permission: String): Boolean {
        val granted =
            ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) return false
        // If we've never launched the request, the launcher hasn't
        // recorded any state. The cheapest signal we have is that the
        // Settings button is still hidden (its visibility flips on the
        // first denial-with-permanent path).
        return binding.onboardingSettingsButton.visibility != View.VISIBLE
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
}
