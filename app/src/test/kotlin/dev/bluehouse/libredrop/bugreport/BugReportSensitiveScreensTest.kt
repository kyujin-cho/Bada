/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.bugreport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BugReportSensitiveScreensTest {
    @Test
    fun shouldRedact_matchesSensitiveActivities() {
        assertTrue(BugReportSensitiveScreens.shouldRedact("dev.bluehouse.libredrop.send.SendActivity"))
        assertTrue(BugReportSensitiveScreens.shouldRedact("dev.bluehouse.libredrop.send.ShowQrActivity"))
        assertTrue(
            BugReportSensitiveScreens.shouldRedact(
                "dev.bluehouse.libredrop.consent.ConsentTrampolineActivity",
            ),
        )
    }

    @Test
    fun shouldRedact_allowsNonSensitiveActivities() {
        assertFalse(BugReportSensitiveScreens.shouldRedact("dev.bluehouse.libredrop.MainActivity"))
        assertFalse(
            BugReportSensitiveScreens.shouldRedact(
                "dev.bluehouse.libredrop.onboarding.PermissionsOnboardingActivity",
            ),
        )
    }
}
