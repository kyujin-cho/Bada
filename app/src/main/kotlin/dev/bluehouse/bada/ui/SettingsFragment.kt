/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui

import android.animation.ArgbEvaluator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.res.use
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import dev.bluehouse.bada.BadaApplication
import dev.bluehouse.bada.MainActivity
import dev.bluehouse.bada.R
import dev.bluehouse.bada.UserFacingFeatures
import dev.bluehouse.bada.battery.BatteryOptimizationOemHelper
import dev.bluehouse.bada.bugreport.BugReportPreferences
import dev.bluehouse.bada.consent.FullScreenIntentPermission
import dev.bluehouse.bada.namecard.NameCardProfileStore
import dev.bluehouse.bada.namecard.NameCardSetupActivity
import dev.bluehouse.bada.service.downloads.SaveLocationDisplayName
import dev.bluehouse.bada.service.downloads.SaveLocationPreferences
import dev.bluehouse.bada.service.receiver.AdvertisedDeviceNames
import dev.bluehouse.bada.service.receiver.ReceiverForegroundService
import dev.bluehouse.bada.service.receiver.ReceiverMasterSwitch
import dev.bluehouse.bada.transfer.KeepScreenOnPreferences
import dev.bluehouse.bada.transfer.TransferExpertViewPreferences
import dev.bluehouse.bada.ui.theme.AppTheme
import dev.bluehouse.bada.ui.theme.AppThemePreferences
import dev.bluehouse.bada.ui.theme.AppThemes
import dev.bluehouse.bada.ui.theme.ExpressiveLists
import dev.bluehouse.bada.ui.theme.ExpressiveMotion
import dev.bluehouse.bada.ui.theme.SpringMorphBackground
import dev.bluehouse.bada.update.UpdatePreferences

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

        // Regions whose widgets differ per theme (ViewStubs on theme tokens):
        // inflate them before any lookup below.
        ExpressiveLists.inflateStub(view, R.id.main_advertised_name_input_stub)
        ExpressiveLists.inflateStub(view, R.id.settings_switch_group_stub)
        ExpressiveLists.inflateStub(view, R.id.settings_theme_extras_stub)
        view.findViewById<ViewGroup>(R.id.settings_switch_group_list)?.let { list ->
            // Expressive segmented list only (the Bada variant has no such id).
            ExpressiveLists.applySegmentedPositions(list)
            ExpressiveLists.toggleSwitchOnItemClick(list)
        }

        wireMasterSwitch(view)
        wireThemePicker(view)
        wireWallpaperColors(view)
        restoreThemePickerOffset(view)

        // "Name Card" row → My Name Card setup page (tap-to-share contacts).
        // Hidden until the flow is usable end to end (UserFacingFeatures).
        val nameCardRow = view.findViewById<View>(R.id.settings_name_card_row)
        nameCardRow.isVisible = UserFacingFeatures.NAME_CARD_ENABLED
        nameCardRow.setOnClickListener {
            startActivity(Intent(requireContext(), NameCardSetupActivity::class.java))
        }

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
            startReceiverIfEnabled()
        }
        view.findViewById<Button>(R.id.main_advertised_name_reset).setOnClickListener {
            AdvertisedDeviceNames.clearCustomName(requireContext())
            refreshAdvertisedNameSection()
            startReceiverIfEnabled()
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

        val expertViewSwitch = view.findViewById<SwitchCompat>(R.id.main_transfer_expert_switch)
        val expertViewPreferences = TransferExpertViewPreferences.from(requireContext())
        expertViewSwitch.isChecked = expertViewPreferences.isExpertViewEnabled()
        expertViewSwitch.setOnCheckedChangeListener { _, checked ->
            expertViewPreferences.setExpertViewEnabled(checked)
        }

        val autoUpdateSwitch = view.findViewById<SwitchCompat>(R.id.settings_auto_update_switch)
        val updatePreferences = UpdatePreferences.from(requireContext())
        autoUpdateSwitch.isChecked = updatePreferences.autoCheckEnabled()
        autoUpdateSwitch.setOnCheckedChangeListener { _, checked ->
            updatePreferences.setAutoCheckEnabled(checked)
            // Re-apply immediately: enabling enqueues the periodic worker,
            // disabling cancels the persisted schedule (not just a soft
            // short-circuit inside the worker).
            BadaApplication.applyAutoUpdateCheckPolicy(requireContext())
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
        refreshTransferSwitches()
        refreshNameCardDot()
    }

    /**
     * Settings > Theme. Builds one option per [AppTheme] and checks the saved
     * one. Picking another option saves it and restarts MainActivity on this
     * tab (MainActivity.restartForThemeChange): AppThemes applies a theme only
     * in onCreate, and the two themes inflate different widget classes, so
     * the old view state must not be restored into the new layout.
     */
    private fun wireThemePicker(view: View) {
        val group = view.findViewById<RadioGroup>(R.id.settings_theme_group)
        val prefs = AppThemePreferences.from(requireContext())
        val current = prefs.theme()
        val inflater = LayoutInflater.from(group.context)
        val secondary = AppThemes.color(requireContext(), android.R.attr.textColorSecondary)
        // Bada: a RadioButton per theme (label + description as one styled
        // text). Expressive: an expressive list item with radio, label and
        // supporting text (badaThemeOptionLayout token).
        val optionLayout = AppThemes.resourceId(requireContext(), R.attr.badaThemeOptionLayout)

        fun pick(theme: AppTheme) {
            if (theme == prefs.theme()) return
            prefs.setTheme(theme)
            val host = requireActivity()
            if (host is MainActivity) host.restartForThemeChange(themePickerOffset(view)) else host.recreate()
        }
        AppTheme.entries.forEach { theme ->
            val option = inflater.inflate(optionLayout, group, false)
            val radio = option.findViewById<RadioButton>(R.id.theme_option_radio)
            radio.id = View.generateViewId()
            val label = option.findViewById<TextView>(R.id.theme_option_label)
            if (label != null) {
                label.setText(theme.labelRes)
                option.findViewById<TextView>(R.id.theme_option_description)?.setText(theme.descriptionRes)
                option.findViewById<MaterialCardView>(R.id.theme_option_item)?.let { item ->
                    item.isChecked = theme == current
                    item.setOnClickListener { pick(theme) }
                }
            } else {
                radio.text = themeOptionLabel(theme, secondary)
            }
            radio.isChecked = theme == current
            radio.setOnCheckedChangeListener { _, checked -> if (checked) pick(theme) }
            group.addView(option)
        }
        ExpressiveLists.applySegmentedPositions(group)
    }

    /**
     * Settings > Theme > Wallpaper colors, Expressive theme only (the Bada
     * variant of the theme extras region has no such list). Shown where the
     * device offers wallpaper colors. The colors apply in onCreate
     * (AppThemes.applyTo), so a change restarts MainActivity like a theme
     * change does.
     */
    private fun wireWallpaperColors(view: View) {
        val list = view.findViewById<ViewGroup>(R.id.settings_theme_extras_list) ?: return
        if (!AppThemes.wallpaperColorsAvailable()) return
        list.isVisible = true
        ExpressiveLists.applySegmentedPositions(list)
        ExpressiveLists.toggleSwitchOnItemClick(list)
        val prefs = AppThemePreferences.from(requireContext())
        val switch = list.findViewById<SwitchCompat>(R.id.settings_wallpaper_colors_switch)
        switch.isChecked = prefs.wallpaperColors()
        switch.setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.wallpaperColors()) return@setOnCheckedChangeListener
            prefs.setWallpaperColors(checked)
            val host = requireActivity()
            if (host is MainActivity) host.restartForThemeChange(themePickerOffset(view)) else host.recreate()
        }
    }

    /**
     * Where the theme options sit on screen: their top in the scroll content
     * minus the current scroll. The restarted Settings tab scrolls so the
     * options land on the same spot (restoreThemePickerOffset), even though
     * the two themes lay out the cards above them at different heights.
     */
    private fun themePickerOffset(root: View): Int? {
        val scroll = root as? ScrollView
        val group = root.findViewById<View>(R.id.settings_theme_group)
        return if (scroll != null && group != null) group.topInScrollContent(scroll) - scroll.scrollY else null
    }

    private fun restoreThemePickerOffset(root: View) {
        val offset = (activity as? MainActivity)?.consumeThemePickerOffset()
        val scroll = root as? ScrollView
        val group = root.findViewById<View>(R.id.settings_theme_group)
        if (offset == null || scroll == null || group == null) return
        // Before the first frame, so the restarted screen fades in already in place.
        root.doOnPreDraw { scroll.scrollTo(0, group.topInScrollContent(scroll) - offset) }
    }

    private fun View.topInScrollContent(scroll: ScrollView): Int {
        var y = 0
        var current: View? = this
        while (current != null && current !== scroll) {
            y += current.top
            current = current.parent as? View
        }
        return y
    }

    private fun themeOptionLabel(
        theme: AppTheme,
        secondaryColor: Int,
    ): CharSequence {
        val label = getString(theme.labelRes)
        val description = getString(theme.descriptionRes)
        return SpannableStringBuilder(label).apply {
            append('\n')
            val start = length
            append(description)
            setSpan(ForegroundColorSpan(secondaryColor), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(THEME_DESCRIPTION_SCALE), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * Master on/off pill (#239). The row toggles; the switch mirrors the
     * persisted state. Off stops the receiver service right away, on
     * starts it again, and the receive tab repaints from the same flag.
     */
    private fun wireMasterSwitch(view: View) {
        val row = view.findViewById<View>(R.id.settings_master_row)
        val label = view.findViewById<TextView>(R.id.settings_master_label)
        val switch = view.findViewById<SwitchCompat>(R.id.settings_master_switch)
        val master = ReceiverMasterSwitch.from(requireContext())
        val defaultTextColor =
            requireContext().obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).use {
                it.getColor(0, 0)
            }
        // Bada theme: the ON label is the brand accent on a faint brand plate.
        // Expressive theme: the ON plate is the primary container, so the
        // label takes its paired on-color for contrast.
        val accent =
            if (AppThemes.isExpressive(requireContext())) {
                AppThemes.color(requireContext(), com.google.android.material.R.attr.colorOnPrimaryContainer)
            } else {
                AppThemes.color(requireContext(), R.attr.badaColorAccent)
            }
        val onBackground = AppThemes.resourceId(requireContext(), R.attr.badaMasterOnBackground)
        val offBackground = AppThemes.resourceId(requireContext(), R.attr.badaMasterOffBackground)
        // Expressive theme: one container that springs between the rounded
        // rectangle (off) and the pill (on) instead of swapping drawables, with
        // the label color following the fill. The Bada theme swaps drawables.
        val morph =
            if (AppThemes.isExpressive(
                    requireContext(),
                )
            ) {
                masterRowMorph(row, label, defaultTextColor, accent)
            } else {
                null
            }

        fun render(
            on: Boolean,
            animate: Boolean,
        ) {
            switch.isChecked = on
            label.setText(if (on) R.string.settings_master_on else R.string.settings_master_off)
            when {
                morph == null -> {
                    label.setTextColor(if (on) accent else defaultTextColor)
                    row.setBackgroundResource(if (on) onBackground else offBackground)
                }
                animate -> morph.animateTo(on)
                else -> morph.snapTo(on)
            }
        }
        render(master.isEnabled(), animate = false)
        row.setOnClickListener {
            val next = !master.isEnabled()
            master.setEnabled(next)
            render(next, animate = true)
            // A short squash on the whole row, released on the fast spatial
            // spring, so the tap itself feels elastic.
            if (morph != null) ExpressiveMotion.popScale(row, MASTER_ROW_PRESS_SCALE)
            if (next) {
                ReceiverForegroundService.start(requireContext())
            } else {
                ReceiverForegroundService.stop(requireContext())
            }
        }
    }

    private fun masterRowMorph(
        row: View,
        label: TextView,
        offTextColor: Int,
        onTextColor: Int,
    ): SpringMorphBackground {
        val context = requireContext()
        val evaluator = ArgbEvaluator()
        return SpringMorphBackground(
            view = row,
            offCornerPx = resources.getDimension(R.dimen.m3e_corner_large),
            offColor = AppThemes.color(context, com.google.android.material.R.attr.colorSurfaceContainerHighest),
            onColor = AppThemes.color(context, com.google.android.material.R.attr.colorPrimaryContainer),
            rippleColor = AppThemes.color(context, androidx.appcompat.R.attr.colorControlHighlight),
            onColorChanged = { fraction ->
                label.setTextColor(evaluator.evaluate(fraction, offTextColor, onTextColor) as Int)
            },
        )
    }

    /** Identity refreshes restart the receiver only while the master switch is on. */
    private fun startReceiverIfEnabled() {
        if (ReceiverMasterSwitch.isEnabled(requireContext())) ReceiverForegroundService.start(requireContext())
    }

    /**
     * Show the small red dot on the "Name Card" row when the user hasn't set up a
     * card yet (mirrors the update badge). Re-checked on every onStart so it clears
     * immediately after the user saves a card in NameCardSetupActivity and returns.
     * The dot reflects the in-app card only — a device fallback (SIM / "Me" contact)
     * still counts as "not set up" so the user is nudged to fill it in.
     */

    private fun refreshNameCardDot() {
        if (!UserFacingFeatures.NAME_CARD_ENABLED) return
        val dot = view?.findViewById<View>(R.id.settings_name_card_dot) ?: return
        val configured = NameCardProfileStore.from(requireContext()).isConfigured()
        dot.visibility = if (configured) View.GONE else View.VISIBLE
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
     * Re-sync transfer display switches in case another Settings
     * surface or restored app state mutates preferences while this
     * fragment is alive.
     */
    private fun refreshTransferSwitches() {
        val v = view ?: return
        v
            .findViewById<SwitchCompat>(R.id.main_keep_screen_on_switch)
            ?.refreshChecked(
                KeepScreenOnPreferences
                    .from(requireContext())
                    .isKeepScreenOnDuringTransfersEnabled(),
            )
        v
            .findViewById<SwitchCompat>(R.id.main_transfer_expert_switch)
            ?.refreshChecked(TransferExpertViewPreferences.from(requireContext()).isExpertViewEnabled())
        v
            .findViewById<SwitchCompat>(R.id.settings_auto_update_switch)
            ?.refreshChecked(UpdatePreferences.from(requireContext()).autoCheckEnabled())
    }

    private fun SwitchCompat.refreshChecked(enabled: Boolean) {
        if (isChecked != enabled) {
            isChecked = enabled
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

        /** Expressive master row: scale it is squashed to on tap before springing back. */
        const val MASTER_ROW_PRESS_SCALE = 0.97f

        /** Theme option description size relative to the option label. */
        const val THEME_DESCRIPTION_SCALE = 0.85f
    }
}
