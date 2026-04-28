/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.payload

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [InMemoryResumeStateStore] and the [ResumeRecord] value
 * type. The persistent JSON-backed implementation in
 * `:service-android` shares the [ResumeStateStore] contract; its
 * additional file-roundtrip semantics are covered there.
 */
class ResumeStateStoreTest {
    @Test
    fun `recordCoverage merges adjacent ranges into one canonical range`() {
        val store = InMemoryResumeStateStore()
        store.recordCoverage("AB12", 1L, totalSize = 100L, start = 0, end = 30, updatedAtMillis = 1)
        store.recordCoverage("AB12", 1L, totalSize = 100L, start = 30, end = 60, updatedAtMillis = 2)

        val record = store.loadCoverage("AB12", 1L)!!
        assertThat(record.coverage).hasSize(1)
        assertThat(record.coverage[0]).isEqualTo(ByteRangeSet.Range(0, 60))
        assertThat(record.updatedAtMillis).isEqualTo(2L)
        assertThat(record.totalSize).isEqualTo(100L)
    }

    @Test
    fun `loadCoverage returns null for an unknown key`() {
        val store = InMemoryResumeStateStore()
        assertThat(store.loadCoverage("AB12", 1L)).isNull()
    }

    @Test
    fun `forget removes the record so a subsequent load returns null`() {
        val store = InMemoryResumeStateStore()
        store.recordCoverage("AB12", 1L, totalSize = 10, start = 0, end = 5, updatedAtMillis = 1)
        assertThat(store.loadCoverage("AB12", 1L)).isNotNull()
        store.forget("AB12", 1L)
        assertThat(store.loadCoverage("AB12", 1L)).isNull()
    }

    @Test
    fun `purgeOlderThan drops only records older than the cutoff`() {
        val store = InMemoryResumeStateStore()
        store.recordCoverage("AB12", 1L, totalSize = 10, start = 0, end = 5, updatedAtMillis = 100)
        store.recordCoverage("AB12", 2L, totalSize = 10, start = 0, end = 5, updatedAtMillis = 200)
        store.recordCoverage("CD34", 3L, totalSize = 10, start = 0, end = 5, updatedAtMillis = 50)
        val removed = store.purgeOlderThan(150L)
        assertThat(removed).isEqualTo(2)
        assertThat(store.loadCoverage("AB12", 1L)).isNull()
        assertThat(store.loadCoverage("CD34", 3L)).isNull()
        assertThat(store.loadCoverage("AB12", 2L)).isNotNull()
    }

    @Test
    fun `records are scoped per endpoint id - same payloadId on different endpoints does not collide`() {
        val store = InMemoryResumeStateStore()
        store.recordCoverage("AB12", 1L, totalSize = 100, start = 0, end = 50, updatedAtMillis = 1)
        store.recordCoverage("CD34", 1L, totalSize = 100, start = 0, end = 80, updatedAtMillis = 1)
        assertThat(store.loadCoverage("AB12", 1L)!!.coverage[0].end).isEqualTo(50)
        assertThat(store.loadCoverage("CD34", 1L)!!.coverage[0].end).isEqualTo(80)
    }

    @Test
    fun `ResumeRecord toByteRangeSet reconstructs the canonical form`() {
        val record =
            ResumeRecord(
                endpointId = "X",
                payloadId = 1,
                totalSize = 100,
                coverage =
                    listOf(
                        ByteRangeSet.Range(0, 10),
                        ByteRangeSet.Range(20, 30),
                    ),
                updatedAtMillis = 0,
            )
        val s = record.toByteRangeSet()
        assertThat(s.size).isEqualTo(2)
        assertThat(s.coveredBytes).isEqualTo(20L)
        assertThat(s.contains(0, 10)).isTrue()
        assertThat(s.contains(15, 25)).isFalse()
    }
}
