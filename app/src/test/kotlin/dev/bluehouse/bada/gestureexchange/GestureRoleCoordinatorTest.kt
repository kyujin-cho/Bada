/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.gestureexchange

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureRoleCoordinatorTest {
    @Test
    fun `opposite and duplicate roles cannot overlap and stale release cannot clear owner`() {
        val send = GestureRoleCoordinator.claim(GestureRoleCoordinator.Role.SEND)
        assertNotNull(send)
        assertNull(GestureRoleCoordinator.claim(GestureRoleCoordinator.Role.RECEIVE))
        assertNull(GestureRoleCoordinator.claim(GestureRoleCoordinator.Role.SEND))
        GestureRoleCoordinator.release(send)

        val receive = GestureRoleCoordinator.claim(GestureRoleCoordinator.Role.RECEIVE)
        assertTrue(GestureRoleCoordinator.owns(receive))
        GestureRoleCoordinator.release(send)
        assertTrue(GestureRoleCoordinator.owns(receive))
        GestureRoleCoordinator.release(receive)
        assertFalse(GestureRoleCoordinator.hasRole(GestureRoleCoordinator.Role.RECEIVE))
    }
}
