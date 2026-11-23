/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.loadingindicator.LoadingIndicator
import dev.bluehouse.bada.R
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog
import dev.bluehouse.bada.ui.theme.AppThemes
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
    private var loadingIndicator: LoadingIndicator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppThemes.applyTo(this) // Settings > Theme; must precede super.onCreate().
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
        addLoadingIndicatorIfExpressive()

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

    /**
     * Material 3 Expressive theme: an M3 loading indicator above the status
     * while the check runs. The M3 guidance names "checking for updates" as a
     * loading-indicator case (a short, indeterminate wait). The Bada theme
     * shows the text alone, as before.
     */
    private fun addLoadingIndicatorIfExpressive() {
        if (!AppThemes.isExpressive(this)) return
        val column = statusTitle.parent as? LinearLayout ?: return
        val indicator =
            LoadingIndicator(this).apply {
                contentDescription = getString(R.string.update_status_checking)
                visibility = View.GONE
            }
        val params =
            LinearLayout
                .LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = resources.getDimensionPixelSize(R.dimen.m3e_loading_indicator_gap)
                }
        column.addView(indicator, column.indexOfChild(statusTitle), params)
        loadingIndicator = indicator
    }

    private fun renderState(state: UpdateState) {
        loadingIndicator?.isVisible = state is UpdateState.Idle || state is UpdateState.Checking
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
        // Launch the browser directly and fall back to a toast if nothing
        // can handle the intent. The previous `resolveActivity` guard
        // returned null on Android 11+ (package-visibility filtering hides
        // browsers unless we declare a matching <queries> intent), so the
        // release page never opened even though a browser was installed.
        // try/catch is the visibility-independent pattern used elsewhere
        // (see BugReportFlowSupport).
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            DiagnosticLog.w(TAG, "Could not open release page: $url", e)
            Toast.makeText(this, R.string.update_open_release_failed, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        private const val TAG = "BadaUpdate"
    }
}
