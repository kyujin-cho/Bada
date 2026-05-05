/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.bugreport

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.bluehouse.libredrop.R
import dev.bluehouse.libredrop.discovery.diagnostics.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

internal class BugReportFlowSupport private constructor(
    private val activity: AppCompatActivity,
) {
    private val preferences: BugReportPreferences = BugReportPreferences.from(activity)
    private val collector: BugReportCollector = BugReportCollector(activity.applicationContext)
    private val sensorManager: SensorManager? =
        activity.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var pendingReport: PreparedBugReport? = null
    private var saveInFlight: Boolean = false
    private var lastPromptAtMillis: Long = 0L
    private var shakeCandidateCount: Int = 0
    private var lastShakeCandidateAt: Long = 0L

    private val saveLauncher: ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            handleSaveResult(uri)
        }

    private val shakeObserver =
        object : DefaultLifecycleObserver, SensorEventListener {
            override fun onStart(owner: LifecycleOwner) {
                val sensor = accelerometer ?: return
                sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
            }

            override fun onStop(owner: LifecycleOwner) {
                sensorManager?.unregisterListener(this)
                shakeCandidateCount = 0
            }

            override fun onSensorChanged(event: SensorEvent) {
                if (preferences.isShakeToReportEnabled() && !saveInFlight) {
                    val gForce =
                        sqrt(
                            (event.values[0] * event.values[0]) +
                                (event.values[1] * event.values[1]) +
                                (event.values[2] * event.values[2]),
                        ) / SensorManager.GRAVITY_EARTH
                    if (gForce >= SHAKE_G_FORCE_THRESHOLD) {
                        val now = SystemClock.elapsedRealtime()
                        val promptReady =
                            registerShakeCandidate(now) &&
                                now - lastPromptAtMillis >= SHAKE_DEBOUNCE_MILLIS
                        if (promptReady) {
                            lastPromptAtMillis = now
                            showConfirmationDialog()
                        }
                    }
                }
            }

            override fun onAccuracyChanged(
                sensor: Sensor?,
                accuracy: Int,
            ) = Unit
        }

    init {
        activity.lifecycle.addObserver(shakeObserver)
    }

    private fun showConfirmationDialog() {
        val checkbox =
            CheckBox(activity).apply {
                text = activity.getString(R.string.bug_report_include_bssid_label)
                isChecked = false
            }
        val message =
            TextView(activity).apply {
                text = activity.getString(R.string.bug_report_confirm_body)
            }
        val container =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val padding =
                    (CONFIRM_DIALOG_HORIZONTAL_PADDING_DP * resources.displayMetrics.density).toInt()
                setPadding(padding, padding / 2, padding, 0)
                addView(
                    message,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(
                    checkbox,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }

        AlertDialog
            .Builder(activity)
            .setTitle(R.string.bug_report_confirm_title)
            .setView(container)
            .setNegativeButton(R.string.bug_report_confirm_no, null)
            .setPositiveButton(R.string.bug_report_confirm_yes) { dialog, _ ->
                dialog.dismiss()
                startCollection(checkbox.isChecked)
            }.show()
    }

    private fun startCollection(includeWifiBssid: Boolean) {
        if (saveInFlight) return
        saveInFlight = true
        val progressDialog =
            AlertDialog
                .Builder(activity)
                .setMessage(R.string.bug_report_collecting)
                .setCancelable(false)
                .create()
        progressDialog.show()
        activity.lifecycleScope.launch {
            val result =
                runCatching {
                    collector.collect(
                        activity = activity,
                        includeWifiBssid = includeWifiBssid,
                    )
                }
            progressDialog.dismiss()
            result
                .onSuccess { report ->
                    pendingReport = report
                    saveLauncher.launch(report.suggestedName)
                }.onFailure { t ->
                    saveInFlight = false
                    Toast
                        .makeText(
                            activity,
                            activity.getString(R.string.bug_report_collect_failed, t.message ?: "unknown error"),
                            Toast.LENGTH_LONG,
                        ).show()
                }
        }
    }

    private fun handleSaveResult(uri: Uri?) {
        val report = pendingReport ?: return
        if (uri == null) {
            report.tempZip.delete()
            pendingReport = null
            saveInFlight = false
            Toast.makeText(activity, R.string.bug_report_save_cancelled, Toast.LENGTH_SHORT).show()
            return
        }

        activity.lifecycleScope.launch {
            val result =
                runCatching {
                    withContext(Dispatchers.IO) {
                        collector.writeToUri(report, uri)
                    }
                }
            report.tempZip.delete()
            pendingReport = null
            saveInFlight = false
            result
                .onSuccess {
                    showPostSaveDialog()
                }.onFailure { t ->
                    Toast
                        .makeText(
                            activity,
                            activity.getString(R.string.bug_report_save_failed, t.message ?: "unknown error"),
                            Toast.LENGTH_LONG,
                        ).show()
                }
        }
    }

    private fun showPostSaveDialog() {
        AlertDialog
            .Builder(activity)
            .setTitle(R.string.bug_report_saved_title)
            .setMessage(R.string.bug_report_saved_body)
            .setNegativeButton(R.string.bug_report_saved_dismiss, null)
            .setPositiveButton(R.string.bug_report_saved_open_issue) { _, _ ->
                openIssuePage()
            }.show()
    }

    private fun openIssuePage() {
        val issueUri =
            Uri
                .parse("https://github.com/kyujin-cho/LibreDrop/issues/new")
                .buildUpon()
                .appendQueryParameter("title", "Bug report (auto-generated)")
                .appendQueryParameter(
                    "body",
                    "Please describe what happened and attach the saved libredrop bug-report zip from your device.",
                ).build()
        val viewIntent = Intent(Intent.ACTION_VIEW, issueUri)
        try {
            activity.startActivity(
                Intent.createChooser(
                    viewIntent,
                    activity.getString(R.string.bug_report_issue_chooser),
                ),
            )
        } catch (e: ActivityNotFoundException) {
            DiagnosticLog.w(TAG, "Could not open GitHub issue page", e)
            Toast.makeText(activity, R.string.bug_report_issue_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun registerShakeCandidate(now: Long): Boolean {
        if (now - lastShakeCandidateAt > SHAKE_CLUSTER_WINDOW_MILLIS) {
            shakeCandidateCount = 0
        }
        lastShakeCandidateAt = now
        shakeCandidateCount += 1
        val ready = shakeCandidateCount >= REQUIRED_SHAKE_HITS
        if (ready) {
            shakeCandidateCount = 0
        }
        return ready
    }

    internal companion object {
        private const val TAG: String = "LibreDropBugReport"
        private const val CONFIRM_DIALOG_HORIZONTAL_PADDING_DP: Int = 24
        private const val SHAKE_G_FORCE_THRESHOLD: Double = 2.35
        private const val REQUIRED_SHAKE_HITS: Int = 2
        private const val SHAKE_CLUSTER_WINDOW_MILLIS: Long = 650L
        private const val SHAKE_DEBOUNCE_MILLIS: Long = 5_000L

        fun install(activity: AppCompatActivity): BugReportFlowSupport = BugReportFlowSupport(activity)
    }
}
