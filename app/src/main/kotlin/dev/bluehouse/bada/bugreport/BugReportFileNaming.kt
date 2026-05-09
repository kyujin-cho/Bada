/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.bugreport

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal object BugReportFileNaming {
    private val archiveFormatter: DateTimeFormatter =
        DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC)

    fun archiveName(now: Instant): String = "bada-bugreport-${archiveFormatter.format(now)}Z.zip"
}
