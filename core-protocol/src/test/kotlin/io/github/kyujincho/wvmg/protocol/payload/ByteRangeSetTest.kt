/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.payload

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for [ByteRangeSet] — the small interval-set helper that backs
 * [PayloadAssembler]'s out-of-order FILE chunk tracking (#44).
 *
 * Coverage is structured around the three kinds of insert outcome
 * ([ByteRangeSet.AddResult.Added], [ByteRangeSet.AddResult.AlreadyCovered],
 * [ByteRangeSet.AddResult.PartialOverlap]) plus the canonicalization
 * invariant (sorted, non-overlapping, non-adjacent).
 */
class ByteRangeSetTest {
    @Test
    fun `empty set is incomplete for non-zero total and complete for zero`() {
        val s = ByteRangeSet()
        assertThat(s.size).isEqualTo(0)
        assertThat(s.coveredBytes).isEqualTo(0L)
        assertThat(s.isComplete(0)).isTrue()
        assertThat(s.isComplete(10)).isFalse()
    }

    @Test
    fun `adding a fresh range reports Added with the full size`() {
        val s = ByteRangeSet()
        val r = s.add(10, 20)
        assertThat(r).isEqualTo(ByteRangeSet.AddResult.Added(addedBytes = 10))
        assertThat(s.coveredBytes).isEqualTo(10L)
        assertThat(s.size).isEqualTo(1)
    }

    @Test
    fun `adding the same range a second time reports AlreadyCovered`() {
        val s = ByteRangeSet()
        s.add(0, 100)
        val r = s.add(0, 100)
        assertThat(r).isEqualTo(ByteRangeSet.AddResult.AlreadyCovered)
        assertThat(s.coveredBytes).isEqualTo(100L)
        assertThat(s.size).isEqualTo(1)
    }

    @Test
    fun `adding a range fully contained in an existing one reports AlreadyCovered`() {
        val s = ByteRangeSet()
        s.add(0, 100)
        val r = s.add(20, 50)
        assertThat(r).isEqualTo(ByteRangeSet.AddResult.AlreadyCovered)
        assertThat(s.coveredBytes).isEqualTo(100L)
    }

    @Test
    fun `adding a range that partially overlaps reports PartialOverlap with delta`() {
        val s = ByteRangeSet()
        s.add(0, 100)
        // [50, 150) overlaps [0, 100) on [50, 100) (50 bytes) and
        // extends [100, 150) (50 fresh bytes).
        val r = s.add(50, 150)
        assertThat(r).isEqualTo(ByteRangeSet.AddResult.PartialOverlap(addedBytes = 50))
        assertThat(s.coveredBytes).isEqualTo(150L)
        assertThat(s.size).isEqualTo(1)
    }

    @Test
    fun `adjacent ranges merge into one canonical range`() {
        val s = ByteRangeSet()
        s.add(0, 50)
        s.add(50, 100) // exactly adjacent at 50 -> merges
        assertThat(s.size).isEqualTo(1)
        assertThat(s.coveredBytes).isEqualTo(100L)
        assertThat(s.isComplete(100)).isTrue()
    }

    @Test
    fun `non-adjacent ranges stay separate`() {
        val s = ByteRangeSet()
        s.add(0, 10)
        s.add(20, 30)
        s.add(40, 50)
        assertThat(s.size).isEqualTo(3)
        assertThat(s.coveredBytes).isEqualTo(30L)
        assertThat(s.isComplete(50)).isFalse()
    }

    @Test
    fun `inserting a range that bridges two existing ranges merges all three`() {
        val s = ByteRangeSet()
        s.add(0, 10)
        s.add(20, 30)
        // [10, 20) is the gap; bridging it merges everything into one.
        val r = s.add(10, 20)
        assertThat(r).isEqualTo(ByteRangeSet.AddResult.Added(addedBytes = 10))
        assertThat(s.size).isEqualTo(1)
        assertThat(s.coveredBytes).isEqualTo(30L)
    }

    @Test
    fun `inserting a range that subsumes multiple existing ranges merges them all`() {
        val s = ByteRangeSet()
        s.add(10, 20)
        s.add(30, 40)
        s.add(50, 60)
        // [0, 100) covers all three plus extra fresh bytes.
        val r = s.add(0, 100)
        // Existing covered bytes: 30. Inserted size: 100. Genuinely new: 70.
        assertThat(r).isInstanceOf(ByteRangeSet.AddResult.PartialOverlap::class.java)
        assertThat((r as ByteRangeSet.AddResult.PartialOverlap).addedBytes).isEqualTo(70L)
        assertThat(s.size).isEqualTo(1)
        assertThat(s.coveredBytes).isEqualTo(100L)
    }

    @Test
    fun `insertion order does not change canonical layout for shuffled chunks`() {
        // Two assemblers, same chunk set, different insertion order:
        // both should reach the identical canonical form.
        val s1 = ByteRangeSet()
        val s2 = ByteRangeSet()
        listOf(0L to 10L, 10L to 20L, 20L to 30L, 30L to 40L)
            .forEach { (a, b) -> s1.add(a, b) }
        listOf(20L to 30L, 0L to 10L, 30L to 40L, 10L to 20L)
            .forEach { (a, b) -> s2.add(a, b) }
        assertThat(s1.ranges).isEqualTo(s2.ranges)
        assertThat(s1.isComplete(40)).isTrue()
    }

    @Test
    fun `contains returns true for any sub-range of a stored range`() {
        val s = ByteRangeSet()
        s.add(100, 200)
        assertThat(s.contains(100, 200)).isTrue()
        assertThat(s.contains(110, 190)).isTrue()
        assertThat(s.contains(100, 100)).isTrue()
        assertThat(s.contains(150, 150)).isTrue()
        assertThat(s.contains(99, 200)).isFalse()
        assertThat(s.contains(100, 201)).isFalse()
        assertThat(s.contains(0, 50)).isFalse()
    }

    @Test
    fun `clear empties the set`() {
        val s = ByteRangeSet()
        s.add(0, 10)
        s.add(20, 30)
        s.clear()
        assertThat(s.size).isEqualTo(0)
        assertThat(s.coveredBytes).isEqualTo(0L)
    }

    @Test
    fun `negative or inverted ranges are rejected`() {
        val s = ByteRangeSet()
        assertThrows<IllegalArgumentException> { s.add(-1, 0) }
        assertThrows<IllegalArgumentException> { s.add(10, 5) }
    }

    @Test
    fun `Range itself rejects negative and inverted bounds`() {
        assertThrows<IllegalArgumentException> { ByteRangeSet.Range(-1, 0) }
        assertThrows<IllegalArgumentException> { ByteRangeSet.Range(10, 5) }
    }

    @Test
    fun `empty range insert is a no-op and reports AlreadyCovered`() {
        val s = ByteRangeSet()
        val r = s.add(5, 5)
        assertThat(r).isEqualTo(ByteRangeSet.AddResult.AlreadyCovered)
        assertThat(s.size).isEqualTo(0)
    }

    @Test
    fun `isComplete strictly checks for single-range full coverage`() {
        val s = ByteRangeSet()
        s.add(0, 50)
        s.add(60, 100)
        assertThat(s.isComplete(100)).isFalse()
        s.add(50, 60)
        assertThat(s.isComplete(100)).isTrue()
    }
}
