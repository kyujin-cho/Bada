/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.bugreport

import androidx.appcompat.app.AppCompatActivity

/**
 * Sensitive-screen matcher for screenshot redaction.
 *
 * The policy is intentionally conservative: every screen that can expose the
 * 4-digit consent PIN or QR handshake material is treated as sensitive and
 * therefore receives a placeholder bitmap in bug reports.
 */
internal object BugReportSensitiveScreens {
    private val sensitiveClassNames: Set<String> =
        setOf(
            "dev.bluehouse.bada.consent.ConsentTrampolineActivity",
            "dev.bluehouse.bada.send.ShowQrActivity",
            "dev.bluehouse.bada.send.SendActivity",
        )

    fun shouldRedact(activity: AppCompatActivity): Boolean = shouldRedact(activity.javaClass.name)

    fun shouldRedact(className: String): Boolean = className in sensitiveClassNames
}
