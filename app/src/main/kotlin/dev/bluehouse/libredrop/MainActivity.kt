/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop

import android.content.ActivityNotFoundException
import android.content.Intent
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
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import dev.bluehouse.libredrop.battery.BatteryOptimizationOemHelper
import dev.bluehouse.libredrop.battery.BatteryOptimizationPreferences
import dev.bluehouse.libredrop.bugreport.BugReportFlowSupport
import dev.bluehouse.libredrop.bugreport.BugReportPreferences
import dev.bluehouse.libredrop.migration.LegacyPackageDetectorAndroid
import dev.bluehouse.libredrop.onboarding.PermissionRequirements
import dev.bluehouse.libredrop.onboarding.PermissionsOnboardingActivity
import dev.bluehouse.libredrop.send.SendActivity
import dev.bluehouse.libredrop.service.downloads.SaveLocationDisplayName
import dev.bluehouse.libredrop.service.downloads.SaveLocationPreferences
import dev.bluehouse.libredrop.service.receiver.AdvertisedDeviceNames
import dev.bluehouse.libredrop.service.receiver.MdnsVisibilityOverrideHolder
import dev.bluehouse.libredrop.service.receiver.ReceiverForegroundService

/**
 * Empty launcher activity for the LibreDrop app.
 *
 * Phase 1's job here is purely scaffold — the real device list, transfer
 * status, and settings screens replace this implementation as the rest
 * of the phase rolls in.
 *
 * The real logic today is:
 *
 *  - Permissions gate: if any of the runtime permissions Phase 1 needs
 *    (#26) is missing on cold start, we send the user to
 *    [PermissionsOnboardingActivity] before they see any app UI.
 *    Onboarding does not block proceeding when only optional permissions
 *    remain denied — see the activity for the policy.
 *  - Always-visible override (#34): a single switch surfaces
 *    [MdnsVisibilityOverrideHolder.setAlwaysVisible] to the user. When
 *    enabled, the receiver publishes mDNS unconditionally, bypassing
 *    the BLE-pulse gate. Useful on devices where BLE scan is
 *    unavailable (no permission, no LE hardware) or whenever the user
 *    wants to stay discoverable.
 *  - Advertised Quick Share name (#141): a small settings block lets
 *    the user persist a custom receiver name, or fall back to Android's
 *    device-name chain when no override is set.
 *  - Battery-optimization banner (#47): a one-time, dismissible banner
 *    that detects the OEM family and routes the user to the right
 *    "exempt this app from background killing" Settings page. Hidden
 *    automatically once the platform reports the app as exempt or once
 *    the user taps Skip.
 *
 * The override flag is process-wide and lives in memory only. Persisting
 * the toggle across process death is a follow-up; the in-memory surface
 * is the minimum #34 needs to ship the override path. The
 * battery-optimization dismiss flag is persisted in
 * [BatteryOptimizationPreferences] (SharedPreferences-backed) so it
 * survives a cold start.
 */
class MainActivity : AppCompatActivity() {
    /**
     * Latched once we've routed to the onboarding screen for this
     * activity instance, so that pressing back from onboarding does
     * not bounce the user straight back into it on every onStart.
     * The next cold start (process death / fresh task) re-evaluates.
     */
    private var onboardingLaunched: Boolean = false

    /**
     * Launcher for SAF's `ACTION_OPEN_DOCUMENT_TREE` (#38). Android
     * registers the result contract during `onCreate` (it walks the
     * activity-result API's bookkeeping at that point), so the launcher
     * is owned at the activity level and triggered by the "Send folder"
     * button click handler.
     *
     * On a successful pick we forward the resolved tree URI to
     * [SendActivity] via [SendActivity.ACTION_SEND_FOLDER]; the activity
     * walks the tree, builds one [dev.bluehouse.libredrop.protocol.connection.FileSource]
     * per descendant file, and runs the existing peer-discovery /
     * outbound-connection flow with `parent_folder` populated.
     */
    private lateinit var openTreeLauncher: ActivityResultLauncher<Uri?>

    /**
     * Launcher for the SAF tree picker that backs the "Save received
     * files to" setting (#42). The result URI is persisted via
     * [SaveLocationPreferences] which also takes the persistable
     * read+write grant so the choice survives reboots. The activity
     * refreshes its current-location label after every successful
     * selection.
     */
    private lateinit var saveLocationLauncher: ActivityResultLauncher<Uri?>

    /**
     * Most recent result of [LegacyPackageDetectorAndroid.findInstalledLegacy].
     * Captured by [refreshLegacyWvmgBanner] so the Uninstall click
     * handler does not have to re-probe (which could race with a
     * partially-completed uninstall and target the wrong variant when
     * both `wvmg` and `wvmg.debug` are present).
     */
    private var legacyPackageInBanner: String? = null
    private lateinit var bugReportFlowSupport: BugReportFlowSupport

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bugReportFlowSupport = BugReportFlowSupport.install(this)
        // Restore the flag across configuration changes so we don't
        // re-route on rotate.
        savedInstanceState?.let {
            onboardingLaunched = it.getBoolean(STATE_ONBOARDING_LAUNCHED, false)
        }

        val switch = findViewById<SwitchCompat>(R.id.main_always_visible_switch)
        // Reflect the current process-wide value so the toggle is
        // accurate after a configuration change or activity recreation.
        switch.isChecked = MdnsVisibilityOverrideHolder.isActive
        switch.setOnCheckedChangeListener { _, checked ->
            MdnsVisibilityOverrideHolder.setAlwaysVisible(checked)
        }

        val bugReportSwitch = findViewById<SwitchCompat>(R.id.main_bug_report_switch)
        val bugReportPreferences = BugReportPreferences.from(this)
        bugReportSwitch.isChecked = bugReportPreferences.isShakeToReportEnabled()
        bugReportSwitch.setOnCheckedChangeListener { _, checked ->
            bugReportPreferences.setShakeToReportEnabled(checked)
        }

        // SAF tree-picker (#38). The launcher must be registered during
        // onCreate to satisfy the activity-result API's lifecycle
        // contract; we hand its result off to SendActivity rather than
        // duplicating discovery / connection wiring here.
        openTreeLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
                if (treeUri != null) {
                    val intent =
                        Intent(this, SendActivity::class.java).apply {
                            action = SendActivity.ACTION_SEND_FOLDER
                            data = treeUri
                            // FLAG_GRANT_READ_URI_PERMISSION propagates the
                            // SAF read grant to SendActivity. Without it,
                            // the receiving activity can read top-level
                            // children but `openInputStream` on individual
                            // file URIs throws SecurityException. The
                            // `OpenDocumentTree` contract already takes a
                            // persistable grant on our behalf, so the read
                            // is safe to pass through.
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    startActivity(intent)
                }
            }

        findViewById<Button>(R.id.main_send_folder_button).setOnClickListener {
            // Passing null means "no initial directory hint"; the system
            // picker opens at its default landing screen (typically the
            // most recently used location). The user is free to navigate
            // to any tree they have access to.
            openTreeLauncher.launch(null)
        }

        // Battery-optimization banner (#47). Buttons are wired here so
        // they survive configuration changes; visibility is recomputed
        // in onResume so a state change made in Settings (e.g. the user
        // exempts the app and comes back) hides the banner immediately.
        findViewById<Button>(R.id.main_battery_banner_open).setOnClickListener {
            onBatteryBannerOpenClicked()
        }
        findViewById<Button>(R.id.main_battery_banner_skip).setOnClickListener {
            onBatteryBannerSkipClicked()
        }

        // #145 legacy-WhenVivoMeetsGoogle migration banner. The button is
        // wired in onCreate so it survives configuration changes;
        // visibility is recomputed in onStart so it disappears the
        // moment the user comes back from the system uninstall flow.
        findViewById<Button>(R.id.main_legacy_wvmg_banner_uninstall).setOnClickListener {
            onLegacyWvmgUninstallClicked()
        }

        // Save-location settings (#42). The launcher must be registered
        // during onCreate per the activity-result API contract; the
        // pick / clear buttons just trigger it / clear the preference
        // and refresh the label.
        saveLocationLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
                if (treeUri == null) return@registerForActivityResult
                try {
                    SaveLocationPreferences.from(this).setSaveTreeUri(treeUri)
                    refreshSaveLocationLabel()
                } catch (e: SecurityException) {
                    // The platform refused to take the persistable grant
                    // (typically because the URI didn't come from
                    // ACTION_OPEN_DOCUMENT_TREE — defensive guard, the
                    // contract should always return a tree URI). Surface
                    // a soft error so the user picks a different folder.
                    Log.w(TAG, "Save-location pick failed: ${e.message}", e)
                    Toast.makeText(this, R.string.main_save_location_pick_failed, Toast.LENGTH_LONG).show()
                }
            }
        findViewById<Button>(R.id.main_save_location_pick).setOnClickListener {
            saveLocationLauncher.launch(null)
        }
        findViewById<Button>(R.id.main_save_location_clear).setOnClickListener {
            SaveLocationPreferences.from(this).clear()
            refreshSaveLocationLabel()
        }

        findViewById<Button>(R.id.main_advertised_name_save).setOnClickListener {
            val input = findViewById<EditText>(R.id.main_advertised_name_input)
            val stored = AdvertisedDeviceNames.setCustomName(this, input.text?.toString())
            input.setText(stored.orEmpty())
            refreshAdvertisedNameSection()
            ReceiverForegroundService.start(this)
        }
        findViewById<Button>(R.id.main_advertised_name_reset).setOnClickListener {
            AdvertisedDeviceNames.clearCustomName(this)
            refreshAdvertisedNameSection()
            ReceiverForegroundService.start(this)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ONBOARDING_LAUNCHED, onboardingLaunched)
    }

    override fun onStart() {
        super.onStart()
        // Re-sync the switch to the holder on every onStart — the
        // override is process-wide and could have been changed by
        // another activity (or, in the future, a quick-settings tile)
        // while we were paused.
        findViewById<SwitchCompat>(R.id.main_always_visible_switch)?.let {
            if (it.isChecked != MdnsVisibilityOverrideHolder.isActive) {
                it.isChecked = MdnsVisibilityOverrideHolder.isActive
            }
        }
        findViewById<SwitchCompat>(R.id.main_bug_report_switch)?.let {
            val enabled = BugReportPreferences.from(this).isShakeToReportEnabled()
            if (it.isChecked != enabled) {
                it.isChecked = enabled
            }
        }

        // Route to onboarding at most once per activity instance. Once
        // #21 lands, the foreground service will re-check at start time
        // and surface a dismissible error instead of relaunching
        // onboarding from here.
        if (!onboardingLaunched &&
            !PermissionRequirements.allGranted(this) &&
            !PermissionRequirements.onlyOptionalMissing(this)
        ) {
            onboardingLaunched = true
            startActivity(Intent(this, PermissionsOnboardingActivity::class.java))
            return
        }

        // Re-evaluate the battery-optimization banner each time the
        // launcher comes back to the foreground. The user may have
        // toggled the system exemption while we were paused; the banner
        // hides automatically the moment the platform reports us as
        // exempt, even if the user never tapped Skip.
        refreshBatteryBanner()

        // Re-evaluate the legacy-WVMG banner on every resume so it
        // disappears immediately after the user returns from the system
        // uninstall confirmation. Cheap PackageManager probe — two
        // entries in the manifest <queries> block, no allocation when
        // both lookups miss.
        refreshLegacyWvmgBanner()

        // Re-read the save-location preference on every onStart so the
        // label reflects an external change (e.g. the user revoked the
        // grant in system Settings while we were paused). Falls back to
        // the "Downloads (default)" label when the URI is unset or its
        // grant has been lost.
        refreshSaveLocationLabel()
        refreshAdvertisedNameSection()

        // Bring up the receiver foreground service so the BLE pulse
        // scanner (#33), mDNS-publish gate (#34), and TCP listener are
        // running while the user has the launcher visible. Without this
        // call the production code had no other entry point that ever
        // started the service, so the receiver pipeline was effectively
        // dead-code on real devices — found while running the BLE-trigger
        // interop runbook against a Vivo X300 Ultra. The service handles
        // repeated startService calls idempotently.
        //
        // We only reach this point when the mandatory permissions are
        // granted (the early-return above bounces the user to onboarding
        // first). If the user denied an optional permission like
        // POST_NOTIFICATIONS, BleQuickShareScanner.start() and the JmDNS
        // publish path each re-check their own permissions internally
        // and gracefully no-op rather than crash.
        ReceiverForegroundService.start(this)
    }

    /**
     * Decides whether the battery-optimization banner (#47) should be
     * visible right now. The banner appears when:
     *   * the user has not previously dismissed it, AND
     *   * the platform reports the app is not yet exempt from battery
     *     optimization, AND
     *   * the OEM helper found at least one Settings activity it can
     *     route the user to (the generic system dialog is always in
     *     this list on API 23+, so practically this is always non-empty
     *     on supported devices — guard kept for the offline-package-
     *     manager edge case).
     *
     * Hides itself in every other case so the launcher returns to a
     * clean scaffold once the prompt has been handled.
     */
    private fun refreshBatteryBanner() {
        val banner = findViewById<View>(R.id.main_battery_banner)
        val prefs = BatteryOptimizationPreferences.from(this)
        val shouldShow =
            !prefs.hasBeenDismissed() &&
                !BatteryOptimizationOemHelper.isAlreadyExempt(this) &&
                BatteryOptimizationOemHelper.intentsForCurrentDevice(this).isNotEmpty()
        banner.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    /**
     * Tries the OEM-aware list of candidate intents in order. The first
     * one that launches successfully also marks the prompt dismissed so
     * the banner stays hidden on the next visit, regardless of whether
     * the user actually tapped the exemption toggle in Settings.
     */
    private fun onBatteryBannerOpenClicked() {
        val candidates = BatteryOptimizationOemHelper.intentsForCurrentDevice(this)
        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.main_battery_banner_unavailable, Toast.LENGTH_LONG).show()
            BatteryOptimizationPreferences.from(this).markDismissed()
            refreshBatteryBanner()
            return
        }
        for (intent in candidates) {
            try {
                startActivity(intent)
                BatteryOptimizationPreferences.from(this).markDismissed()
                refreshBatteryBanner()
                return
            } catch (e: ActivityNotFoundException) {
                // Vendor activities can disappear between ROM versions
                // even when resolveActivity reported them present, so
                // we keep walking the list.
                Log.w(TAG, "Battery-exemption intent not launchable: ${intent.component}", e)
            } catch (e: SecurityException) {
                // Some MIUI / EMUI builds protect their internal
                // activities behind permissions only their first-party
                // launcher holds.
                Log.w(TAG, "Battery-exemption intent denied: ${intent.component}", e)
            }
        }
        // Every candidate failed at runtime — surface a soft warning
        // instead of crashing, and treat the prompt as handled so the
        // user is not stuck staring at the same banner forever.
        Toast.makeText(this, R.string.main_battery_banner_unavailable, Toast.LENGTH_LONG).show()
        BatteryOptimizationPreferences.from(this).markDismissed()
        refreshBatteryBanner()
    }

    /**
     * Update the "Current: …" line under the save-location title to
     * match the persisted preference. Reads via
     * [SaveLocationPreferences] (which already drops the URI when its
     * grant has been revoked) so a stale URI never shows up here as
     * a misleading label.
     */
    private fun refreshSaveLocationLabel() {
        val label = findViewById<TextView>(R.id.main_save_location_current)
        val savedUri = SaveLocationPreferences.from(this).getSaveTreeUri()
        val displayText =
            if (savedUri != null) {
                val name = SaveLocationDisplayName.resolve(this, savedUri)
                getString(R.string.main_save_location_current, name)
            } else {
                getString(R.string.main_save_location_default)
            }
        label.text = displayText
    }

    private fun refreshAdvertisedNameSection() {
        val input = findViewById<EditText>(R.id.main_advertised_name_input)
        val custom = AdvertisedDeviceNames.getCustomName(this).orEmpty()
        if (input.text?.toString() != custom) {
            input.setText(custom)
        }

        val current = findViewById<TextView>(R.id.main_advertised_name_current)
        current.text =
            getString(
                R.string.main_advertised_name_current,
                AdvertisedDeviceNames.resolve(this),
            )
    }

    /**
     * Show or hide the #145 migration banner based on whether a
     * coresident legacy WhenVivoMeetsGoogle install is still present.
     * Stores the detected legacy package id in [legacyPackageInBanner]
     * so the Uninstall button click handler can route directly without
     * re-running detection (avoids a TOCTOU race where the user
     * uninstalls one variant while both were detected).
     */
    private fun refreshLegacyWvmgBanner() {
        val legacyPackage = LegacyPackageDetectorAndroid.findInstalledLegacy(this)
        legacyPackageInBanner = legacyPackage
        val banner = findViewById<View>(R.id.main_legacy_wvmg_banner)
        banner.visibility = if (legacyPackage != null) View.VISIBLE else View.GONE
    }

    /**
     * Open Android's standard uninstall confirmation dialog for the
     * legacy WhenVivoMeetsGoogle package using `Intent.ACTION_DELETE`.
     * That action does not need any extra permission and does not
     * require us to be a device owner — the platform shows its own
     * confirmation UI before tearing the package down.
     *
     * On the rare ROM that has stripped `ACTION_DELETE` (we have not
     * seen one in the wild on the OEMs we care about), surface a Toast
     * and let the user uninstall manually rather than crashing the
     * activity.
     */
    private fun onLegacyWvmgUninstallClicked() {
        val target = legacyPackageInBanner ?: return
        val intent =
            Intent(Intent.ACTION_DELETE).apply {
                data = Uri.fromParts("package", target, null)
            }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "ACTION_DELETE not resolvable for $target: ${e.message}", e)
            val message = getString(R.string.main_legacy_wvmg_banner_unavailable, target)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun onBatteryBannerSkipClicked() {
        BatteryOptimizationPreferences.from(this).markDismissed()
        refreshBatteryBanner()
    }

    private companion object {
        const val STATE_ONBOARDING_LAUNCHED = "libredrop.main.onboardingLaunched"
        const val TAG = "LibreDropMain"
    }
}
