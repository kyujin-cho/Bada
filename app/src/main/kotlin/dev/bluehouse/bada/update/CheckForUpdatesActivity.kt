/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.update

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import dev.bluehouse.bada.R
import kotlinx.coroutines.launch

/**
 * "Check for updates" screen reached from MainActivity's overflow menu.
 *
 * Owns nothing of its own: the in-flight check, the cached snapshot and
 * the version comparison live in [UpdateRepository], and this activity
 * just renders [UpdateRepository.state] onto three views (title,
 * subtitle, button) and forwards the button tap.
 *
 * The first visit triggers a fresh [UpdateRepository.refresh] so the
 * user sees a current answer even if the background check started by
 * MainActivity is still in flight.
 */
internal class CheckForUpdatesActivity : AppCompatActivity() {
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var primaryButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_check_for_updates)

        val toolbar = findViewById<MaterialToolbar>(R.id.update_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        statusTitle = findViewById(R.id.update_status_title)
        statusSubtitle = findViewById(R.id.update_status_subtitle)
        primaryButton = findViewById(R.id.update_primary_button)

        primaryButton.setOnClickListener { onPrimaryClicked() }

        UpdateRepository.seedFromCache(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpdateRepository.state.collect(::renderState)
            }
        }

        // Kick a fresh check on every entry so the user never sees a
        // stale "up to date" answer if a release landed since the last
        // background check.
        lifecycleScope.launch { UpdateRepository.refresh(this@CheckForUpdatesActivity) }
    }

    private fun renderState(state: UpdateState) {
        when (state) {
            is UpdateState.Idle,
            is UpdateState.Checking,
            -> {
                statusTitle.setText(R.string.update_status_checking)
                statusSubtitle.visibility = View.GONE
                primaryButton.setText(R.string.update_button_check_again)
                primaryButton.isEnabled = false
            }
            is UpdateState.UpToDate -> {
                statusTitle.setText(R.string.update_status_up_to_date)
                statusSubtitle.text =
                    getString(R.string.update_subtitle_current_version, state.installedVersion)
                statusSubtitle.visibility = View.VISIBLE
                primaryButton.setText(R.string.update_button_check_again)
                primaryButton.isEnabled = true
            }
            is UpdateState.UpdateAvailable -> {
                statusTitle.setText(R.string.update_status_available)
                statusSubtitle.text =
                    getString(R.string.update_subtitle_latest_version, state.latestVersion)
                statusSubtitle.visibility = View.VISIBLE
                primaryButton.setText(R.string.update_button_update)
                primaryButton.isEnabled = true
            }
            is UpdateState.Error -> {
                statusTitle.setText(R.string.update_status_error)
                statusSubtitle.text = state.message
                statusSubtitle.visibility = View.VISIBLE
                primaryButton.setText(R.string.update_button_check_again)
                primaryButton.isEnabled = true
            }
        }
    }

    private fun onPrimaryClicked() {
        when (val state = UpdateRepository.state.value) {
            is UpdateState.UpdateAvailable -> openReleasePage(state.releaseUrl)
            else -> lifecycleScope.launch { UpdateRepository.refresh(this@CheckForUpdatesActivity) }
        }
    }

    private fun openReleasePage(url: String) {
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        // resolveActivity guards the rare case where no browser is
        // installed; without it the explicit intent throws ActivityNotFound.
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
    }
}
