/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import dev.bluehouse.bada.battery.BatteryOptimizationOemHelper
import dev.bluehouse.bada.battery.BatteryOptimizationPreferences
import dev.bluehouse.bada.bugreport.BugReportFlowSupport
import dev.bluehouse.bada.consent.FullScreenIntentPermission
import dev.bluehouse.bada.consent.FullScreenIntentPreferences
import dev.bluehouse.bada.onboarding.PermissionRequirements
import dev.bluehouse.bada.onboarding.PermissionsOnboardingActivity
import dev.bluehouse.bada.service.receiver.ReceiverForegroundService
import dev.bluehouse.bada.ui.CreditActivity
import dev.bluehouse.bada.ui.SendReceiveFragment
import dev.bluehouse.bada.ui.SettingsFragment

/**
 * Top-level launcher activity. Splits the UI into:
 *
 *   * A top app bar ([MaterialToolbar]) that carries the app title
 *     and the kebab overflow menu (a single "Credit" entry today,
 *     opening [CreditActivity]).
 *   * Two bottom-navigation tabs:
 *     - [SendReceiveFragment] — file/folder send entry points, the
 *       always-visible toggle, and the conditional battery (#47) and
 *       legacy-WhenVivoMeetsGoogle (#145) onboarding banners.
 *     - [SettingsFragment] — save-location override (#42) and
 *       advertised Quick Share device name (#141).
 *
 * The activity owns three pieces of cross-tab state:
 *
 *   * The permissions gate: if any of the runtime permissions Phase 1
 *     needs (#26) is missing on cold start, route the user to
 *     [PermissionsOnboardingActivity] before any tab content is shown.
 *     Onboarding does not block proceeding when only optional
 *     permissions remain denied — see the activity for the policy.
 *   * The receiver foreground-service kick (#33 / #34): start the
 *     service on every onStart so the BLE pulse scanner, mDNS-publish
 *     gate, and TCP listener are running while the launcher is
 *     visible. Idempotent on the service side.
 *   * Tab restoration: the currently-selected tab id is persisted in
 *     [savedInstanceState] so a configuration change does not snap the
 *     user back to the default.
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
     * Latched once the battery-optimization dialog has been shown in
     * this MainActivity instance. Saved into instance state so a
     * configuration change (rotate / theme switch) does not re-raise
     * the dialog after the user has already seen it once. A cold
     * start (no `savedInstanceState`) clears the latch and the
     * dialog re-evaluates against [BatteryOptimizationPreferences];
     * once the user taps Skip or Open Settings the prefs are marked
     * dismissed and the dialog never raises again.
     */
    private var batteryDialogShownThisSession: Boolean = false

    /**
     * Session latch for the full-screen-intent first-launch prompt,
     * mirroring [batteryDialogShownThisSession]. Survives configuration
     * changes via `savedInstanceState`; a fresh cold start clears it and
     * the dialog re-evaluates against [FullScreenIntentPreferences].
     */
    private var fsiDialogShownThisSession: Boolean = false

    /**
     * Shake-to-report bug flow (#166). Lives at the activity level
     * because the support class registers a process-lifetime
     * SensorEventListener, hooks the activity's
     * [androidx.lifecycle.Lifecycle], and owns the SAF
     * `CreateDocument` launcher used to save the diagnostic ZIP.
     * The preference toggle that gates the listener is wired up by
     * [dev.bluehouse.bada.ui.SettingsFragment].
     */
    private lateinit var bugReportFlowSupport: BugReportFlowSupport

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Install the shake-to-report bug-flow support (#166). Has to
        // happen before any fragment transaction so the flow's
        // CreateDocument result launcher is registered while the
        // activity is in CREATED — registering after would throw.
        bugReportFlowSupport = BugReportFlowSupport.install(this)

        savedInstanceState?.let {
            onboardingLaunched = it.getBoolean(STATE_ONBOARDING_LAUNCHED, false)
            batteryDialogShownThisSession = it.getBoolean(STATE_BATTERY_DIALOG_SHOWN, false)
            fsiDialogShownThisSession = it.getBoolean(STATE_FSI_DIALOG_SHOWN, false)
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.main_toolbar)
        // Override the toolbar overflow icon with the vivo / OriginOS-style
        // 2-dot kebab. Setting it programmatically (rather than via the
        // `app:overflowIcon` XML attribute) avoids a styleable lookup
        // mismatch we hit on the appcompat-resources build that ships with
        // AGP 8.7.3 / appcompat 1.7.0; the runtime setter has been stable
        // since the ToolbarWidgetWrapper landed in support-v7 and works on
        // every minSdk we target.
        toolbar.overflowIcon = ContextCompat.getDrawable(this, R.drawable.ic_overflow_kebab_24)
        setSupportActionBar(toolbar)

        val bottomNav = findViewById<BottomNavigationView>(R.id.main_bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            val fragment =
                when (item.itemId) {
                    R.id.nav_send_receive -> SendReceiveFragment()
                    R.id.nav_settings -> SettingsFragment()
                    else -> return@setOnItemSelectedListener false
                }
            // Swap the toolbar title per tab so the page identity is
            // explicit. Home keeps the "bada" brand (the app's
            // root surface); Settings switches to its tab title so the
            // toolbar reads as page context, matching how the
            // dedicated send/consent activities title themselves
            // rather than carrying the brand.
            toolbar.title =
                when (item.itemId) {
                    R.id.nav_settings -> getString(R.string.nav_settings_title)
                    else -> getString(R.string.app_name)
                }
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.main_fragment_container, fragment)
                .commit()
            true
        }

        // Only seed the initial fragment on the very first creation; on
        // configuration change the FragmentManager has already restored
        // the previous fragment and the BottomNavigationView restores
        // its own selected-item state from saved instance state.
        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.nav_send_receive
        }

        // The internal item views are only attached after the
        // BottomNavigationView finishes inflating its menu, which
        // happens during the first measure pass — `post` defers the
        // hookup to the next run-loop tick so children definitely
        // exist by the time we reach for them.
        bottomNav.post { installItemPressAnimations(bottomNav) }
    }

    /**
     * Replace BottomNavigationView's stock ripple/highlight press
     * effect with the vivo-style release-bounce animation: the icon
     * stays at full size while the user is holding the tab down,
     * and on finger-release the icon shrinks to 85% then springs
     * back to 100% as a single chained animation. Label is never
     * touched — the size and color of the text stay constant. A
     * drag-out (`ACTION_CANCEL`) intentionally does NOT trigger
     * the bounce, matching click-feedback semantics.
     *
     * The implementation reaches into the BottomNavigationView's
     * private menu container to grab each item's icon `ImageView`
     * (id `navigation_bar_item_icon_view`, defined by the Material
     * library and stable since 1.4.0). An OnTouchListener on the
     * item view kicks the bounce on `ACTION_UP` and returns `false`
     * so the View's own onTouchEvent still routes the click to the
     * BottomNavigationView's selection handler.
     */
    private fun installItemPressAnimations(bottomNav: BottomNavigationView) {
        val menuView = bottomNav.getChildAt(0) as? ViewGroup ?: return
        for (i in 0 until menuView.childCount) {
            val itemView = menuView.getChildAt(i)
            val icon =
                itemView.findViewById<ImageView>(
                    com.google.android.material.R.id.navigation_bar_item_icon_view,
                ) ?: continue
            itemView.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    playIconBounce(icon)
                }
                false
            }
        }
    }

    /**
     * Run a quick scale-down then scale-up animation on the bottom-nav
     * icon. Cancels any in-flight animation and resets the scale to
     * 1.0 first so a rapid double-tap always plays the bounce from
     * the rest state, never from a half-shrunk frame.
     */
    private fun playIconBounce(icon: View) {
        icon.animate().cancel()
        icon.scaleX = RESTING_ICON_SCALE
        icon.scaleY = RESTING_ICON_SCALE
        icon
            .animate()
            .scaleX(PRESSED_ICON_SCALE)
            .scaleY(PRESSED_ICON_SCALE)
            .setDuration(ICON_PRESS_DURATION_MS)
            .withEndAction {
                icon
                    .animate()
                    .scaleX(RESTING_ICON_SCALE)
                    .scaleY(RESTING_ICON_SCALE)
                    .setDuration(ICON_PRESS_DURATION_MS)
                    .start()
            }.start()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_overflow_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.menu_credit -> {
                startActivity(Intent(this, CreditActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ONBOARDING_LAUNCHED, onboardingLaunched)
        outState.putBoolean(STATE_BATTERY_DIALOG_SHOWN, batteryDialogShownThisSession)
        outState.putBoolean(STATE_FSI_DIALOG_SHOWN, fsiDialogShownThisSession)
    }

    override fun onStart() {
        super.onStart()
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

        // Bring up the receiver foreground service so the BLE pulse
        // scanner (#33), mDNS-publish gate (#34), and TCP listener are
        // running while the user has the launcher visible. Without this
        // call the production code had no other entry point that ever
        // started the service, so the receiver pipeline was effectively
        // dead-code on real devices — found while running the
        // BLE-trigger interop runbook against a Vivo X300 Ultra. The
        // service handles repeated startService calls idempotently.
        //
        // We only reach this point when the mandatory permissions are
        // granted (the early-return above bounces the user to onboarding
        // first). If the user denied an optional permission like
        // POST_NOTIFICATIONS, BleQuickShareScanner.start() and the
        // mDNS publish path each re-check their own permissions
        // internally and gracefully no-op rather than crash.
        ReceiverForegroundService.start(this)

        // First-launch prompts (#47 battery, full-screen-intent). Show at
        // most one per onStart so the user never faces stacked dialogs:
        // the battery exemption takes priority, and the full-screen-intent
        // prompt only raises when the battery one did not. Each is gated
        // by its own session latch + persisted dismissal flag, so the
        // skipped one still gets its turn on a later cold start.
        if (!maybeShowBatteryOptimizationDialog()) {
            maybeShowFullScreenIntentDialog()
        }
    }

    /**
     * Raise the battery-optimization dialog at most once per
     * MainActivity instance, gated by the same conditions that
     * previously controlled the banner: device not already exempt,
     * user has not chosen Skip / Open Settings before, and the
     * OEM helper has at least one Settings activity to route to.
     *
     * Skip → mark the prefs dismissed and surface a Toast pointing
     *        the user at the Settings tab so they know how to
     *        re-trigger the prompt. The dialog will never raise
     *        again on this device.
     * Open Settings → walk the OEM-aware intent list and open the
     *                 first one that resolves; mark the prefs
     *                 dismissed regardless (treat sending the user
     *                 to the system page as the user having
     *                 acknowledged the prompt).
     * System back / outside-tap → cancellable, no preference
     *                             change. The session latch still
     *                             prevents re-show in the same
     *                             instance, but a fresh cold start
     *                             will re-prompt.
     */
    @Suppress("ReturnCount")
    private fun maybeShowBatteryOptimizationDialog(): Boolean {
        if (batteryDialogShownThisSession) return false
        if (BatteryOptimizationPreferences.from(this).hasBeenDismissed()) return false
        if (BatteryOptimizationOemHelper.isAlreadyExempt(this)) return false
        if (BatteryOptimizationOemHelper.intentsForCurrentDevice(this).isEmpty()) return false

        batteryDialogShownThisSession = true

        AlertDialog
            .Builder(this)
            .setTitle(R.string.main_battery_banner_title)
            .setMessage(R.string.main_battery_banner_body)
            .setPositiveButton(R.string.main_battery_banner_open) { _, _ ->
                openBatterySettings(this)
                BatteryOptimizationPreferences.from(this).markDismissed()
            }.setNegativeButton(R.string.main_battery_banner_skip) { _, _ ->
                BatteryOptimizationPreferences.from(this).markDismissed()
                Toast
                    .makeText(this, R.string.dialog_battery_skip_toast, Toast.LENGTH_LONG)
                    .show()
            }.setCancelable(true)
            .show()
        return true
    }

    /**
     * Raise the full-screen-intent first-launch prompt at most once per
     * MainActivity instance. Only relevant on Android 14+, where
     * `USE_FULL_SCREEN_INTENT` became a special access that is
     * auto-denied for non-calendar/alarm apps — without it the
     * incoming-transfer consent prompt cannot pop full-screen while Bada
     * is backgrounded and degrades to a heads-up notification.
     *
     * Gated identically to the battery prompt: session latch + persisted
     * dismissal. Only shows when the access is actually requestable
     * (API 34+ and not yet granted).
     *
     * Open Settings → route to the system page and mark dismissed.
     * Skip → mark dismissed and Toast that it lives in the Settings tab.
     *
     * @return true when the dialog was shown this call.
     */
    @Suppress("ReturnCount")
    private fun maybeShowFullScreenIntentDialog(): Boolean {
        if (fsiDialogShownThisSession) return false
        if (FullScreenIntentPreferences.from(this).hasBeenDismissed()) return false
        if (!FullScreenIntentPermission.isRequestable(this)) return false

        fsiDialogShownThisSession = true

        AlertDialog
            .Builder(this)
            .setTitle(R.string.settings_fsi_title)
            .setMessage(R.string.fsi_dialog_body)
            .setPositiveButton(R.string.settings_fsi_open) { _, _ ->
                FullScreenIntentPermission.openSettings(this)
                FullScreenIntentPreferences.from(this).markDismissed()
            }.setNegativeButton(R.string.main_battery_banner_skip) { _, _ ->
                FullScreenIntentPreferences.from(this).markDismissed()
                Toast
                    .makeText(this, R.string.fsi_dialog_skip_toast, Toast.LENGTH_LONG)
                    .show()
            }.setCancelable(true)
            .show()
        return true
    }

    internal companion object {
        private const val TAG = "BadaMain"
        private const val STATE_ONBOARDING_LAUNCHED = "bada.main.onboardingLaunched"
        private const val STATE_BATTERY_DIALOG_SHOWN = "bada.main.batteryDialogShown"
        private const val STATE_FSI_DIALOG_SHOWN = "bada.main.fsiDialogShown"

        // Bottom-nav icon scale on press (matches vivo native).
        private const val PRESSED_ICON_SCALE = 0.85f
        private const val RESTING_ICON_SCALE = 1.0f
        private const val ICON_PRESS_DURATION_MS = 100L

        /**
         * Open the system battery-optimization settings list so the
         * user can locate this app and toggle its exemption manually.
         *
         * We deliberately do NOT use
         * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — that action
         * surfaces a one-tap "Allow this app to ignore battery
         * optimizations?" dialog, but on some OEM ROMs (notably vivo
         * OriginOS) tapping Allow on that dialog does not actually
         * flip [PowerManager.isIgnoringBatteryOptimizations]. The user
         * sees a popup, taps grant, and nothing changes — which is
         * exactly the broken-button feel we want to avoid. The full
         * settings list page is always interactive and the toggle
         * inside it does flip the platform flag, so the status label
         * in the Settings tab updates correctly when the user returns.
         *
         * Vendor activities (vivo BgStartUpManager etc.) are kept as
         * fall-through entries so devices on which the standard
         * Settings list page unexpectedly fails to resolve still
         * have somewhere to land.
         */
        internal fun openBatterySettings(context: Context) {
            val primaryIntent =
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val vendorIntents = BatteryOptimizationOemHelper.intentsForCurrentDevice(context)
            val candidates = listOf(primaryIntent) + vendorIntents
            for (intent in candidates) {
                try {
                    context.startActivity(intent)
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
            Toast
                .makeText(context, R.string.main_battery_banner_unavailable, Toast.LENGTH_LONG)
                .show()
        }
    }
}
