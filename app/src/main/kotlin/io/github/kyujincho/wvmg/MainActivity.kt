/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import io.github.kyujincho.wvmg.onboarding.PermissionRequirements
import io.github.kyujincho.wvmg.onboarding.PermissionsOnboardingActivity
import io.github.kyujincho.wvmg.send.SendActivity
import io.github.kyujincho.wvmg.service.receiver.MdnsVisibilityOverrideHolder
import io.github.kyujincho.wvmg.service.receiver.ReceiverForegroundService

/**
 * Empty launcher activity for the WhenVivoMeetsGoogle app.
 *
 * Phase 1's job here is purely scaffold — the real device list, transfer
 * status, and settings screens replace this implementation as the rest
 * of the phase rolls in.
 *
 * The one bit of real logic today is:
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
 *
 * The override flag is process-wide and lives in memory only. Persisting
 * the toggle across process death is a follow-up; the in-memory surface
 * is the minimum #34 needs to ship the override path.
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
     * walks the tree, builds one [io.github.kyujincho.wvmg.protocol.connection.FileSource]
     * per descendant file, and runs the existing peer-discovery /
     * outbound-connection flow with `parent_folder` populated.
     */
    private lateinit var openTreeLauncher: ActivityResultLauncher<Uri?>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
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

    private companion object {
        const val STATE_ONBOARDING_LAUNCHED = "wvmg.main.onboardingLaunched"
    }
}
