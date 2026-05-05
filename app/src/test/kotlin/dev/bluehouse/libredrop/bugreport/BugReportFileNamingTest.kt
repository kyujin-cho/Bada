/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.bugreport

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class BugReportFileNamingTest {
    @Test
    fun archiveName_usesUtcTimestampFormat() {
        assertEquals(
            "libredrop-bugreport-20260505-103500Z.zip",
            BugReportFileNaming.archiveName(Instant.parse("2026-05-05T10:35:00Z")),
        )
    }
}
