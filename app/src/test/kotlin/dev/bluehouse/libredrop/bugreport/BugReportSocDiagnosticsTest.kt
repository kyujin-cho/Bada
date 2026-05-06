/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.bugreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BugReportSocDiagnosticsTest {
    @Test
    fun resolveForSdkInt_preApi31_returnsFallbackWithoutInvokingApi31Reader() {
        var invoked = false

        val diagnostics =
            BugReportSocDiagnostics.resolveForSdkInt(sdkInt = 29) {
                invoked = true
                SocDiagnostics(manufacturer = "unexpected", model = "unexpected")
            }

        assertFalse(invoked)
        assertEquals(BugReportSocDiagnostics.UNAVAILABLE_PRE_API31, diagnostics.manufacturer)
        assertEquals(BugReportSocDiagnostics.UNAVAILABLE_PRE_API31, diagnostics.model)
    }

    @Test
    fun resolveForSdkInt_api31_invokesApi31Reader() {
        var invoked = false

        val diagnostics =
            BugReportSocDiagnostics.resolveForSdkInt(sdkInt = 31) {
                invoked = true
                SocDiagnostics(manufacturer = "Qualcomm", model = "SM8650")
            }

        assertTrue(invoked)
        assertEquals("Qualcomm", diagnostics.manufacturer)
        assertEquals("SM8650", diagnostics.model)
    }
}
