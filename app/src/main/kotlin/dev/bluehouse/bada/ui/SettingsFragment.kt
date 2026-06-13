/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import dev.bluehouse.bada.MainActivity
import dev.bluehouse.bada.R
import dev.bluehouse.bada.battery.BatteryOptimizationOemHelper
import dev.bluehouse.bada.bugreport.BugReportPreferences
import dev.bluehouse.bada.consent.FullScreenIntentPermission
import dev.bluehouse.bada.service.downloads.SaveLocationDisplayName
import dev.bluehouse.bada.service.downloads.SaveLocationPreferences
import dev.bluehouse.bada.service.receiver.AdvertisedDeviceNames
import dev.bluehouse.bada.service.receiver.ReceiverForegroundService
import dev.bluehouse.bada.transfer.KeepScreenOnPreferences

/**
 * Settings tab content for the bottom-navigation shell in
 * [dev.bluehouse.bada.MainActivity].
 *
 * Houses the persistent receiver-side preferences:
 *   * #42 save-location override — pick a SAF tree URI to redirect
 *     incoming files away from the system Downloads folder, or clear
 *     the override to fall back to Downloads.
 *   * #141 advertised Quick Share name — custom override for the
 *     receiver name nearby Quick Share peers see; clearing the override
 *     falls back to Android's device-name chain (system device name,
 *     Bluetooth name, model, then app label).
 *   * #47 background-activity entry point — re-trigger the OEM-aware
 *     battery-optimization Settings page after the first-launch dialog
 *     has been skipped. Status summary is refreshed on every onStart
 *     so a system-Settings round trip reflects immediately.
 *
 * Each control mutates a single SharedPreferences-backed value (or
 * platform power-manager state for the battery row) and refreshes
 * its summary line on every onStart so an external state change
 * (e.g. the user revoked the SAF grant in system Settings while we
 * were paused) reflects immediately.
 */
internal class SettingsFragment : Fragment(R.layout.fragment_settings) {
    /**
     * Launcher for the SAF tree picker that backs the "Save received
     * files to" setting (#42). The result URI is persisted via
     * [SaveLocationPreferences] which also takes the persistable
     * read+write grant so the choice survives reboots. The fragment
     * refreshes its current-location label after every successful
     * selection.
     */
    private lateinit var saveLocationLauncher: ActivityResultLauncher<Uri?>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        saveLocationLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
                if (treeUri == null) return@registerForActivityResult
                try {
                    SaveLocationPreferences.from(requireContext()).setSaveTreeUri(treeUri)
                    refreshSaveLocationLabel()
                } catch (e: SecurityException) {
                    // The platform refused to take the persistable grant
                    // (typically because the URI didn't come from
                    // ACTION_OPEN_DOCUMENT_TREE — defensive guard, the
                    // contract should always return a tree URI). Surface
                    // a soft error so the user picks a different folder.
                    Log.w(TAG, "Save-location pick failed: ${e.message}", e)
                    Toast
                        .makeText(
                            requireContext(),
                            R.string.main_save_location_pick_failed,
                            Toast.LENGTH_LONG,
                        ).show()
                }
            }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Button>(R.id.main_save_location_pick).setOnClickListener {
            saveLocationLauncher.launch(null)
        }
        view.findViewById<Button>(R.id.main_save_location_clear).setOnClickListener {
            SaveLocationPreferences.from(requireContext()).clear()
            refreshSaveLocationLabel()
        }

        view.findViewById<Button>(R.id.main_advertised_name_save).setOnClickListener {
            val input = view.findViewById<EditText>(R.id.main_advertised_name_input)
            val stored = AdvertisedDeviceNames.setCustomName(requireContext(), input.text?.toString())
            input.setText(stored.orEmpty())
            refreshAdvertisedNameSection()
            ReceiverForegroundService.start(requireContext())
        }
        view.findViewById<Button>(R.id.main_advertised_name_reset).setOnClickListener {
            AdvertisedDeviceNames.clearCustomName(requireContext())
            refreshAdvertisedNameSection()
            ReceiverForegroundService.start(requireContext())
        }

        view.findViewById<Button>(R.id.settings_battery_open).setOnClickListener {
            MainActivity.openBatterySettings(requireContext())
        }

        view.findViewById<Button>(R.id.settings_fsi_open).setOnClickListener {
            FullScreenIntentPermission.openSettings(requireContext())
        }

        val bugReportSwitch = view.findViewById<SwitchCompat>(R.id.main_bug_report_switch)
        val bugReportPreferences = BugReportPreferences.from(requireContext())
        bugReportSwitch.isChecked = bugReportPreferences.isShakeToReportEnabled()
        bugReportSwitch.setOnCheckedChangeListener { _, checked ->
            bugReportPreferences.setShakeToReportEnabled(checked)
        }

        val keepScreenOnSwitch = view.findViewById<SwitchCompat>(R.id.main_keep_screen_on_switch)
        val keepScreenOnPreferences = KeepScreenOnPreferences.from(requireContext())
        keepScreenOnSwitch.isChecked = keepScreenOnPreferences.isKeepScreenOnDuringTransfersEnabled()
        keepScreenOnSwitch.setOnCheckedChangeListener { _, checked ->
            keepScreenOnPreferences.setKeepScreenOnDuringTransfersEnabled(checked)
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-read the save-location preference on every onStart so the
        // label reflects an external change (e.g. the user revoked the
        // grant in system Settings while we were paused). Falls back to
        // the "Downloads (default)" label when the URI is unset or its
        // grant has been lost.
        refreshSaveLocationLabel()
        refreshAdvertisedNameSection()
        refreshBatteryStatus()
        refreshFullScreenIntentSection()
        refreshBugReportSwitch()
        refreshKeepScreenOnSwitch()
    }

    override fun onResume() {
        super.onResume()
        // Full-screen-intent access is toggled on a system Settings page
        // that, like the battery overlay, can dismiss without fully
        // stopping this activity on some OEM ROMs. Re-check on resume so
        // the status line flips immediately after the user grants it.
        refreshFullScreenIntentSection()
        // Re-check the battery-optimization exemption on every resume.
        // The platform's `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
        // dialog is a translucent overlay on some OEM ROMs (vivo
        // OriginOS, in particular) — when it dismisses, the host
        // fragment lifecycle does not always fall back through
        // `onStart` because the underlying activity was never fully
        // stopped. Re-running [refreshBatteryStatus] from `onResume`
        // catches that case so the "Currently: …" label flips
        // immediately after the user grants the exemption, instead
        // of waiting for the next manual tab switch.
        refreshBatteryStatus()
    }

    /**
     * Re-sync the shake-to-report switch to the preference holder on
     * every onStart so an external write (currently none, but #166
     * adds room for the bug-report flow itself to flip the toggle
     * after a save) reflects without requiring a fragment recreate.
     */
    private fun refreshBugReportSwitch() {
        val v = view ?: return
        val switch = v.findViewById<SwitchCompat>(R.id.main_bug_report_switch) ?: return
        val enabled = BugReportPreferences.from(requireContext()).isShakeToReportEnabled()
        if (switch.isChecked != enabled) {
            switch.isChecked = enabled
        }
    }

    /**
     * Re-sync the transfer display switch in case another Settings
     * surface or restored app state mutates the preference while this
     * fragment is alive.
     */
    private fun refreshKeepScreenOnSwitch() {
        val v = view ?: return
        val switch = v.findViewById<SwitchCompat>(R.id.main_keep_screen_on_switch) ?: return
        val enabled =
            KeepScreenOnPreferences
                .from(requireContext())
                .isKeepScreenOnDuringTransfersEnabled()
        if (switch.isChecked != enabled) {
            switch.isChecked = enabled
        }
    }

    /**
     * Update the "Currently: …" line under the Background activity
     * title to match the platform-reported exemption state. Reading
     * via [BatteryOptimizationOemHelper.isAlreadyExempt] (which wraps
     * `PowerManager.isIgnoringBatteryOptimizations`) means a system
     * Settings round trip flips the label without an app restart.
     */
    private fun refreshBatteryStatus() {
        val label = view?.findViewById<TextView>(R.id.settings_battery_status) ?: return
        val exempt = BatteryOptimizationOemHelper.isAlreadyExempt(requireContext())
        label.text =
            if (exempt) {
                getString(R.string.settings_battery_status_exempt)
            } else {
                getString(R.string.settings_battery_status_not_exempt)
            }
    }

    /**
     * Show the full-screen-alerts card only on Android 14+ (where
     * `USE_FULL_SCREEN_INTENT` is a grantable special access) and reflect
     * the current grant state in its status line. On older platforms the
     * permission is install-time, so the whole card stays hidden.
     */
    @Suppress("ReturnCount")
    private fun refreshFullScreenIntentSection() {
        val v = view ?: return
        val card = v.findViewById<View>(R.id.settings_fsi_card) ?: return
        if (!FullScreenIntentPermission.isApplicable()) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        val status = v.findViewById<TextView>(R.id.settings_fsi_status) ?: return
        status.text =
            if (FullScreenIntentPermission.isGranted(requireContext())) {
                getString(R.string.settings_fsi_status_granted)
            } else {
                getString(R.string.settings_fsi_status_not_granted)
            }
    }

    /**
     * Update the "Current: …" line under the save-location title to
     * match the persisted preference. Reads via
     * [SaveLocationPreferences] (which already drops the URI when its
     * grant has been revoked) so a stale URI never shows up here as
     * a misleading label.
     */
    private fun refreshSaveLocationLabel() {
        val label = view?.findViewById<TextView>(R.id.main_save_location_current) ?: return
        val ctx = requireContext()
        val savedUri = SaveLocationPreferences.from(ctx).getSaveTreeUri()
        val displayText =
            if (savedUri != null) {
                val name = SaveLocationDisplayName.resolve(ctx, savedUri)
                getString(R.string.main_save_location_current, name)
            } else {
                getString(R.string.main_save_location_default)
            }
        label.text = displayText
    }

    private fun refreshAdvertisedNameSection() {
        val v = view ?: return
        val ctx = requireContext()
        val input = v.findViewById<EditText>(R.id.main_advertised_name_input)
        val custom = AdvertisedDeviceNames.getCustomName(ctx).orEmpty()
        if (input.text?.toString() != custom) {
            input.setText(custom)
        }

        val current = v.findViewById<TextView>(R.id.main_advertised_name_current)
        current.text =
            getString(
                R.string.main_advertised_name_current,
                AdvertisedDeviceNames.resolve(ctx),
            )
    }

    private companion object {
        const val TAG = "BadaMain"
    }
}
