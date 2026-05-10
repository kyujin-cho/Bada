/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.resume

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.bada.protocol.payload.ByteRangeSet
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * JVM tests for [PersistentResumeStateStore].
 *
 * Two concerns drive the coverage here:
 *  - **Functional parity** with [dev.bluehouse.bada.protocol.payload.InMemoryResumeStateStore]:
 *    record/load/forget/purge semantics match.
 *  - **Disk roundtrip**: state persisted by one instance is visible
 *    to a second instance pointed at the same file. This is the
 *    acceptance criterion for issue #43's "kill the receiver mid
 *    transfer, reopen, sender resumes" scenario — the receiver's
 *    coverage record must survive a process restart.
 */
class PersistentResumeStateStoreTest {
    @Test
    fun `recordCoverage persists across reopen of the same file`(
        @TempDir tmp: Path,
    ) {
        val file = File(tmp.toFile(), "resume.json")
        run {
            val store = PersistentResumeStateStore(file)
            store.recordCoverage("AB12", 1L, totalSize = 100, start = 0, end = 50, updatedAtMillis = 100)
            store.recordCoverage("AB12", 1L, totalSize = 100, start = 60, end = 80, updatedAtMillis = 200)
        }
        // Second instance over the same file — simulates a process
        // restart.
        val reopened = PersistentResumeStateStore(file)
        val record = reopened.loadCoverage("AB12", 1L)!!
        assertThat(record.totalSize).isEqualTo(100L)
        assertThat(record.coverage).containsExactly(
            ByteRangeSet.Range(0, 50),
            ByteRangeSet.Range(60, 80),
        )
        assertThat(record.updatedAtMillis).isEqualTo(200L)
    }

    @Test
    fun `forget removes the record from the snapshot and from disk`(
        @TempDir tmp: Path,
    ) {
        val file = File(tmp.toFile(), "resume.json")
        val store = PersistentResumeStateStore(file)
        store.recordCoverage("AB12", 1L, totalSize = 10, start = 0, end = 5, updatedAtMillis = 1)
        store.forget("AB12", 1L)
        val reopened = PersistentResumeStateStore(file)
        assertThat(reopened.loadCoverage("AB12", 1L)).isNull()
    }

    @Test
    fun `purgeOlderThan persists the GC sweep to disk`(
        @TempDir tmp: Path,
    ) {
        val file = File(tmp.toFile(), "resume.json")
        val store = PersistentResumeStateStore(file)
        store.recordCoverage("E1", 1L, totalSize = 10, start = 0, end = 5, updatedAtMillis = 100)
        store.recordCoverage("E2", 2L, totalSize = 10, start = 0, end = 5, updatedAtMillis = 200)
        store.purgeOlderThan(150L)
        val reopened = PersistentResumeStateStore(file)
        assertThat(reopened.loadCoverage("E1", 1L)).isNull()
        assertThat(reopened.loadCoverage("E2", 2L)).isNotNull()
    }

    @Test
    fun `nonexistent storage file yields an empty store and is created on first write`(
        @TempDir tmp: Path,
    ) {
        val file = File(tmp.toFile(), "fresh.json")
        assertThat(file.exists()).isFalse()
        val store = PersistentResumeStateStore(file)
        assertThat(store.recordCount).isEqualTo(0)
        store.recordCoverage("E", 1L, totalSize = 10, start = 0, end = 5, updatedAtMillis = 1)
        assertThat(file.exists()).isTrue()
    }

    @Test
    fun `garbage on disk is treated as an empty store`(
        @TempDir tmp: Path,
    ) {
        val file = File(tmp.toFile(), "corrupt.txt")
        // Lines that don't match the v1 format are silently dropped.
        file.writeText("this is not the format\n", Charsets.UTF_8)
        val store = PersistentResumeStateStore(file)
        assertThat(store.recordCount).isEqualTo(0)
        // The next write should be able to overwrite the file without
        // crashing.
        store.recordCoverage("E", 1L, totalSize = 10, start = 0, end = 5, updatedAtMillis = 1)
        val reopened = PersistentResumeStateStore(file)
        assertThat(reopened.recordCount).isEqualTo(1)
    }

    @Test
    fun `lines with malformed range pairs are skipped on load`(
        @TempDir tmp: Path,
    ) {
        // Hand-crafted file with one valid record line whose range
        // segment contains a malformed entry. The malformed segment
        // is dropped; the surrounding valid segments survive.
        val file = File(tmp.toFile(), "mixed.txt")
        file.writeText(
            "v1|E|1|10|100|0,5;7;8,3\n",
            Charsets.UTF_8,
        )
        val store = PersistentResumeStateStore(file)
        val record = store.loadCoverage("E", 1L)!!
        // Only the valid [0, 5) range survives; "7" (no comma) and
        // "8,3" (inverted bounds) are dropped.
        assertThat(record.coverage).containsExactly(ByteRangeSet.Range(0, 5))
    }

    @Test
    fun `lines tagged with an unknown format version are dropped`(
        @TempDir tmp: Path,
    ) {
        val file = File(tmp.toFile(), "future.txt")
        file.writeText(
            "v999|E|1|10|100|0,5\n" +
                "v1|F|2|10|100|0,5\n",
            Charsets.UTF_8,
        )
        val store = PersistentResumeStateStore(file)
        assertThat(store.loadCoverage("E", 1L)).isNull()
        assertThat(store.loadCoverage("F", 2L)).isNotNull()
    }

    @Test
    fun `endpoint ids containing reserved separator characters round-trip safely`(
        @TempDir tmp: Path,
    ) {
        // The wire format is `|`-separated, so an endpoint id that
        // happens to contain `|`, `;`, `,`, or backslash MUST be
        // escaped; otherwise a simple line split would mis-parse the
        // record. This test guards the round trip.
        val file = File(tmp.toFile(), "weird.txt")
        val tricky = """e|nd;po,nt\id"""
        run {
            val store = PersistentResumeStateStore(file)
            store.recordCoverage(tricky, 1L, totalSize = 10, start = 0, end = 5, updatedAtMillis = 1)
        }
        val reopened = PersistentResumeStateStore(file)
        val record = reopened.loadCoverage(tricky, 1L)!!
        assertThat(record.endpointId).isEqualTo(tricky)
        assertThat(record.coverage).containsExactly(ByteRangeSet.Range(0, 5))
    }

    @Test
    fun `ranges are scoped per endpoint id and per payload id`(
        @TempDir tmp: Path,
    ) {
        val file = File(tmp.toFile(), "scoped.json")
        val store = PersistentResumeStateStore(file)
        store.recordCoverage("E1", 1L, totalSize = 10, start = 0, end = 5, updatedAtMillis = 1)
        store.recordCoverage("E1", 2L, totalSize = 10, start = 0, end = 7, updatedAtMillis = 1)
        store.recordCoverage("E2", 1L, totalSize = 10, start = 0, end = 9, updatedAtMillis = 1)

        val reopened = PersistentResumeStateStore(file)
        assertThat(reopened.loadCoverage("E1", 1L)!!.coverage[0].end).isEqualTo(5)
        assertThat(reopened.loadCoverage("E1", 2L)!!.coverage[0].end).isEqualTo(7)
        assertThat(reopened.loadCoverage("E2", 1L)!!.coverage[0].end).isEqualTo(9)
    }
}
