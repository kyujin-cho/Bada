/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.onboarding

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionRequirementsTest {
    @Test
    fun `api 30 requests only legacy nearby discovery location`() {
        val requirements = PermissionRequirements.requirementsFor(sdkInt = Build.VERSION_CODES.R)

        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            requirements.map(PermissionRequirements.Requirement::permission),
        )
        assertTrue(requirements.single().optional)
    }

    @Test
    fun `api 31 switches to bluetooth runtime permissions`() {
        val requirements = PermissionRequirements.requirementsFor(sdkInt = Build.VERSION_CODES.S)

        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            ),
            requirements.map(PermissionRequirements.Requirement::permission),
        )
        assertTrue(requirements.all(PermissionRequirements.Requirement::optional))
    }

    @Test
    fun `api 33 adds nearby wifi and notifications on top of bluetooth`() {
        val requirements =
            PermissionRequirements.requirementsFor(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
            )

        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
            requirements.map(PermissionRequirements.Requirement::permission),
        )
        val nearbyWifi =
            requirements.first { it.permission == Manifest.permission.NEARBY_WIFI_DEVICES }
        assertFalse(nearbyWifi.optional)
        assertTrue(
            requirements
                .filterNot { it.permission == Manifest.permission.NEARBY_WIFI_DEVICES }
                .all(PermissionRequirements.Requirement::optional),
        )
    }
}
