/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Pure-JVM tests for [MulticastLockHolder]. Drives the holder against a
 * fake [MulticastLockGate] so we never need a real [android.net.wifi.WifiManager].
 *
 * Issue #83 hinged on the multicast lock never being held when mDNS
 * needed it. These tests pin the ref-count semantics down to byte-level
 * detail so the production wrapping over [WifiManager.MulticastLock]
 * cannot regress without a corresponding test failure.
 */
class MulticastLockHolderTest {
    @Test
    fun `acquire and release pair through to the gate`() {
        val gate = FakeGate()
        val holder = MulticastLockHolder(gate = gate, tag = "test")

        holder.acquire()
        assertThat(holder.refCountForTest()).isEqualTo(1)
        assertThat(gate.acquireCount).isEqualTo(1)
        assertThat(holder.isHeld()).isTrue()

        holder.release()
        assertThat(holder.refCountForTest()).isEqualTo(0)
        assertThat(gate.releaseCount).isEqualTo(1)
        assertThat(holder.isHeld()).isFalse()
    }

    @Test
    fun `multiple acquires only call the gate once`() {
        val gate = FakeGate()
        val holder = MulticastLockHolder(gate = gate, tag = "test")

        holder.acquire()
        holder.acquire()
        holder.acquire()

        assertThat(gate.acquireCount).isEqualTo(1)
        assertThat(holder.refCountForTest()).isEqualTo(3)
        assertThat(holder.isHeld()).isTrue()
    }

    @Test
    fun `gate is released only on last release`() {
        val gate = FakeGate()
        val holder = MulticastLockHolder(gate = gate, tag = "test")

        holder.acquire()
        holder.acquire()
        holder.release()
        assertThat(gate.releaseCount).isEqualTo(0)
        assertThat(holder.isHeld()).isTrue()

        holder.release()
        assertThat(gate.releaseCount).isEqualTo(1)
        assertThat(holder.isHeld()).isFalse()
        assertThat(holder.refCountForTest()).isEqualTo(0)
    }

    @Test
    fun `releasing more than acquired throws IllegalStateException`() {
        val gate = FakeGate()
        val holder = MulticastLockHolder(gate = gate, tag = "test")

        holder.acquire()
        holder.release()
        // Counter is back at zero; another release is a programmer error.
        assertThrows<IllegalStateException> { holder.release() }
    }

    @Test
    fun `acquire after final release re-engages the gate`() {
        val gate = FakeGate()
        val holder = MulticastLockHolder(gate = gate, tag = "test")

        holder.acquire()
        holder.release()
        holder.acquire()

        assertThat(gate.acquireCount).isEqualTo(2)
        assertThat(holder.isHeld()).isTrue()

        holder.release()
        assertThat(gate.releaseCount).isEqualTo(2)
    }

    /**
     * Test-only [MulticastLockGate]. Mirrors the platform contract that
     * `acquire()` makes `isHeld()` return true and `release()` makes it
     * return false, ignoring spurious double-acquires (which the holder
     * is supposed to suppress through its ref counter).
     */
    private class FakeGate : MulticastLockGate {
        var acquireCount: Int = 0
            private set
        var releaseCount: Int = 0
            private set
        private var held: Boolean = false

        override fun acquire() {
            acquireCount++
            held = true
        }

        override fun release() {
            releaseCount++
            held = false
        }

        override fun isHeld(): Boolean = held
    }
}
