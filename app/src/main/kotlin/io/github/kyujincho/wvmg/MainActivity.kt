/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.github.kyujincho.wvmg.onboarding.PermissionRequirements
import io.github.kyujincho.wvmg.onboarding.PermissionsOnboardingActivity

/**
 * Empty launcher activity for the WhenVivoMeetsGoogle app.
 *
 * Phase 1's job here is purely scaffold — the real device list, transfer
 * status, and settings screens replace this implementation as the rest
 * of the phase rolls in.
 *
 * The one bit of real logic today is the permissions gate: if any of
 * the runtime permissions Phase 1 needs (#26) is missing, we send the
 * user to [PermissionsOnboardingActivity] before they see any app UI.
 * Onboarding does not block proceeding when only optional permissions
 * remain denied — see the activity for the policy.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    override fun onStart() {
        super.onStart()
        // Re-check on every onStart so the user can return from the
        // onboarding screen (or from system Settings) without being
        // bounced into a stale state. Once #21 lands, the foreground
        // service will repeat this check at start time and surface a
        // dismissible error instead of relaunching onboarding.
        if (!PermissionRequirements.allGranted(this) &&
            !PermissionRequirements.onlyOptionalMissing(this)
        ) {
            startActivity(Intent(this, PermissionsOnboardingActivity::class.java))
        }
    }
}
