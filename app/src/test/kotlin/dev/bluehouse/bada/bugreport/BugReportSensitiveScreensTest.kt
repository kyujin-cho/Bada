/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.bugreport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BugReportSensitiveScreensTest {
    @Test
    fun shouldRedact_matchesSensitiveActivities() {
        assertTrue(BugReportSensitiveScreens.shouldRedact("dev.bluehouse.bada.send.SendActivity"))
        assertTrue(BugReportSensitiveScreens.shouldRedact("dev.bluehouse.bada.send.ShowQrActivity"))
        assertTrue(
            BugReportSensitiveScreens.shouldRedact(
                "dev.bluehouse.bada.consent.ConsentTrampolineActivity",
            ),
        )
    }

    @Test
    fun shouldRedact_allowsNonSensitiveActivities() {
        assertFalse(BugReportSensitiveScreens.shouldRedact("dev.bluehouse.bada.MainActivity"))
        assertFalse(
            BugReportSensitiveScreens.shouldRedact(
                "dev.bluehouse.bada.onboarding.PermissionsOnboardingActivity",
            ),
        )
    }
}
