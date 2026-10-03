/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.gestureexchange

import java.util.UUID

/** Process-wide owner for the one foreground Gesture NFC role permitted at a time. */
internal object GestureRoleCoordinator {
    enum class Role { SEND, RECEIVE }

    class Lease internal constructor(
        val sessionId: String,
        val generation: Long,
        val role: Role,
    )

    private var generation = 0L
    private var active: Lease? = null

    @Synchronized
    fun claim(role: Role): Lease? {
        if (active != null) return null
        generation += 1
        return Lease(UUID.randomUUID().toString(), generation, role).also { active = it }
    }

    @Synchronized
    fun owns(lease: Lease?): Boolean = lease != null && active == lease

    @Synchronized
    fun hasRole(role: Role): Boolean = active?.role == role

    @Synchronized
    fun release(lease: Lease?) {
        if (lease != null && active == lease) active = null
    }
}
