/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.downloads

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/**
 * Pure-JVM tests for [DownloadsWriter]'s reservation, collision, and
 * lifecycle behavior. Runs against a [FakeDownloadsEnvironment] so the
 * Android-specific [MediaStoreDownloadsEnvironment] /
 * [LegacyDownloadsEnvironment] do not need to be exercised here — those
 * are I/O wrappers covered later by instrumentation tests in #28.
 */
class DownloadsWriterTest {
    // ------------------------------------------------------------------
    // Happy path: sanitize, reserve, write, commit.
    // ------------------------------------------------------------------

    @Test
    fun `beginWrite sanitizes the filename before reserving`() {
        val env = FakeDownloadsEnvironment()
        val writer = DownloadsWriter(env)

        // Path-traversal attempt: the slash gets replaced with `_` by
        // FilenameSanitizer before we ever call insertPending.
        val handle = writer.beginWrite("../../etc/passwd", mimeType = "text/plain")

        assertThat(handle.displayName).doesNotContain("/")
        assertThat(handle.displayName).doesNotContain("\\")
        // Leading dots stripped, separators replaced.
        assertThat(handle.displayName).isEqualTo("_.._etc_passwd")
        assertThat(env.slots).containsKey("_.._etc_passwd")
        assertThat(
            env.slots.values
                .single()
                .mimeType,
        ).isEqualTo("text/plain")
    }

    @Test
    fun `commit writes bytes and marks the slot non-pending`() {
        val env = FakeDownloadsEnvironment()
        val writer = DownloadsWriter(env)

        val handle = writer.beginWrite("photo.jpg", mimeType = "image/jpeg")
        handle.outputStream.write(byteArrayOf(0x42, 0x43, 0x44))
        handle.commit()

        val slot = env.slots["photo.jpg"]!!
        assertThat(slot.committed).isTrue()
        assertThat(slot.pending).isFalse()
        assertThat(slot.discarded).isFalse()
        assertThat(slot.buffer.toByteArray()).isEqualTo(byteArrayOf(0x42, 0x43, 0x44))
    }

    @Test
    fun `discard deletes the slot and leaves it not committed`() {
        val env = FakeDownloadsEnvironment()
        val writer = DownloadsWriter(env)

        val handle = writer.beginWrite("partial.bin", mimeType = null)
        handle.outputStream.write(byteArrayOf(0x01, 0x02))
        handle.discard()

        val slot = env.slots["partial.bin"]!!
        assertThat(slot.discarded).isTrue()
        assertThat(slot.committed).isFalse()
    }

    @Test
    fun `commit and discard are individually idempotent`() {
        val env = FakeDownloadsEnvironment()
        val writer = DownloadsWriter(env)

        val handle = writer.beginWrite("idempotent.bin", mimeType = null)
        handle.commit()
        // Second commit must not throw and must not flip state.
        handle.commit()
        // Discard after commit is a no-op (handle is already disposed)
        // — must not flip the slot to discarded.
        handle.discard()

        val slot = env.slots["idempotent.bin"]!!
        assertThat(slot.committed).isTrue()
        assertThat(slot.discarded).isFalse()
    }

    // ------------------------------------------------------------------
    // Collision suffix.
    // ------------------------------------------------------------------

    @Test
    fun `collision retries with NearDrop-style numeric suffix`() {
        val env = FakeDownloadsEnvironment(preReservedNames = mutableSetOf("report.pdf"))
        val writer = DownloadsWriter(env)

        val handle = writer.beginWrite("report.pdf", mimeType = "application/pdf")

        assertThat(handle.displayName).isEqualTo("report (1).pdf")
        assertThat(env.slots).containsKey("report (1).pdf")
    }

    @Test
    fun `collision suffix increments past multiple existing files`() {
        val env =
            FakeDownloadsEnvironment(
                preReservedNames =
                    mutableSetOf(
                        "report.pdf",
                        "report (1).pdf",
                        "report (2).pdf",
                    ),
            )
        val writer = DownloadsWriter(env)

        val handle = writer.beginWrite("report.pdf", mimeType = null)

        assertThat(handle.displayName).isEqualTo("report (3).pdf")
    }

    @Test
    fun `collision suffix lands before the last extension`() {
        // For multi-extension names we want the suffix on the
        // user-meaningful position, which is BEFORE the last
        // extension (so `archive.tar.gz` -> `archive.tar (1).gz`).
        val env = FakeDownloadsEnvironment(preReservedNames = mutableSetOf("archive.tar.gz"))
        val writer = DownloadsWriter(env)

        val handle = writer.beginWrite("archive.tar.gz", mimeType = null)

        assertThat(handle.displayName).isEqualTo("archive.tar (1).gz")
    }

    @Test
    fun `extensionless collisions get the suffix at the end`() {
        val env = FakeDownloadsEnvironment(preReservedNames = mutableSetOf("Makefile"))
        val writer = DownloadsWriter(env)

        val handle = writer.beginWrite("Makefile", mimeType = null)

        assertThat(handle.displayName).isEqualTo("Makefile (1)")
    }

    @Test
    fun `collision retry honors maxCollisionAttempts`() {
        // Saturate every attempt by claiming the base name AND the first
        // 2 suffix variants are taken; then bound the retry to 2.
        // The writer should fail after exhausting attempts.
        val env =
            FakeDownloadsEnvironment(
                preReservedNames =
                    mutableSetOf(
                        "saturated.bin",
                        "saturated (1).bin",
                        "saturated (2).bin",
                    ),
            )
        val writer = DownloadsWriter(env, maxCollisionAttempts = 2)

        val ex =
            assertThrows<IOException> {
                writer.beginWrite("saturated.bin", mimeType = null)
            }
        assertThat(ex).hasMessageThat().contains("saturated.bin")
        assertThat(ex).hasMessageThat().contains("collision retries")
    }

    // ------------------------------------------------------------------
    // Non-collision IOException is propagated unchanged.
    // ------------------------------------------------------------------

    @Test
    fun `non-collision IOException from environment propagates immediately`() {
        // A "disk full" style failure must NOT be hidden behind a
        // collision retry. Build an environment whose insertPending
        // always throws an unrelated IOException.
        val env =
            object : DownloadsEnvironment {
                override fun insertPending(
                    displayName: String,
                    mimeType: String?,
                ): DownloadsEnvironment.Destination = throw IOException("disk full")

                override fun openOutputStream(destination: DownloadsEnvironment.Destination) = error("not reached")

                override fun commit(
                    destination: DownloadsEnvironment.Destination,
                    lastModifiedTimestampMillis: Long,
                ) = error("not reached")

                override fun discard(destination: DownloadsEnvironment.Destination) = error("not reached")
            }
        val writer = DownloadsWriter(env)

        val ex =
            assertThrows<IOException> {
                writer.beginWrite("anything.bin", mimeType = null)
            }
        assertThat(ex).hasMessageThat().contains("disk full")
    }
}
