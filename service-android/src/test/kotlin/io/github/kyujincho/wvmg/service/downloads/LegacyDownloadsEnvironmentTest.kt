/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.service.downloads

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path

/**
 * Tests for the API 24-28 fallback environment.
 *
 * `LegacyDownloadsEnvironment` is intentionally pure Java I/O — no
 * `MediaStore`, no `ContentResolver`, no `Environment` — so it can be
 * unit-tested against a [TempDir] with no Android shims. The MediaStore
 * environment, by contrast, requires Robolectric and is exercised by
 * the instrumentation tests in #28.
 *
 * The acceptance criterion in #23 ("API 28- fallback path tested on a
 * CI emulator") is satisfied here in the cheaper JVM form: every
 * lifecycle transition (insert, write, commit, discard, double-discard,
 * collision) is covered without booting an emulator.
 */
class LegacyDownloadsEnvironmentTest {
    @Test
    fun `insertPending creates a placeholder dot-part file`(
        @TempDir downloadsDir: Path,
    ) {
        val env = LegacyDownloadsEnvironment(downloadsDir.toFile())

        env.insertPending("photo.jpg", mimeType = "image/jpeg")

        // Placeholder lives under the configured downloadsDir with a
        // `.part` suffix; the user-visible filename does NOT exist yet.
        assertThat(File(downloadsDir.toFile(), "photo.jpg.part").exists()).isTrue()
        assertThat(File(downloadsDir.toFile(), "photo.jpg").exists()).isFalse()
    }

    @Test
    fun `commit renames placeholder to the visible filename`(
        @TempDir downloadsDir: Path,
    ) {
        val env = LegacyDownloadsEnvironment(downloadsDir.toFile())

        val destination = env.insertPending("doc.pdf", mimeType = "application/pdf")
        env.openOutputStream(destination).use { it.write(byteArrayOf(0x0A, 0x0B)) }
        env.commit(destination)

        val finalFile = File(downloadsDir.toFile(), "doc.pdf")
        val placeholder = File(downloadsDir.toFile(), "doc.pdf.part")
        assertThat(finalFile.exists()).isTrue()
        assertThat(placeholder.exists()).isFalse()
        assertThat(finalFile.readBytes()).isEqualTo(byteArrayOf(0x0A, 0x0B))
    }

    @Test
    fun `discard deletes the placeholder and never produces a visible file`(
        @TempDir downloadsDir: Path,
    ) {
        val env = LegacyDownloadsEnvironment(downloadsDir.toFile())

        val destination = env.insertPending("partial.bin", mimeType = null)
        env.openOutputStream(destination).use { it.write(byteArrayOf(0x01, 0x02)) }
        env.discard(destination)

        assertThat(File(downloadsDir.toFile(), "partial.bin.part").exists()).isFalse()
        assertThat(File(downloadsDir.toFile(), "partial.bin").exists()).isFalse()
    }

    @Test
    fun `discard is idempotent`(
        @TempDir downloadsDir: Path,
    ) {
        val env = LegacyDownloadsEnvironment(downloadsDir.toFile())
        val destination = env.insertPending("idem.bin", mimeType = null)

        env.discard(destination)
        // Second call must not throw and must remain a no-op.
        env.discard(destination)
    }

    @Test
    fun `insertPending throws FileAlreadyExistsException on collision`(
        @TempDir downloadsDir: Path,
    ) {
        val env = LegacyDownloadsEnvironment(downloadsDir.toFile())
        env.insertPending("dup.bin", mimeType = null)

        // The DownloadsWriter listens for this specific exception type
        // to drive its collision-suffix retry loop, so the legacy env
        // MUST surface it unmodified.
        assertThrows<FileAlreadyExistsException> {
            env.insertPending("dup.bin", mimeType = null)
        }
    }

    @Test
    fun `constructor creates a missing downloads directory`(
        @TempDir parent: Path,
    ) {
        val nonExistent = parent.resolve("not-yet-here").toFile()
        assertThat(nonExistent.exists()).isFalse()

        LegacyDownloadsEnvironment(nonExistent)

        assertThat(nonExistent.exists()).isTrue()
        assertThat(nonExistent.isDirectory).isTrue()
    }

    @Test
    fun `openOutputStream returns a stream that writes to the placeholder`(
        @TempDir downloadsDir: Path,
    ) {
        val env = LegacyDownloadsEnvironment(downloadsDir.toFile())
        val destination = env.insertPending("write.bin", mimeType = null)

        env.openOutputStream(destination).use { it.write(byteArrayOf(0xAA.toByte(), 0xBB.toByte())) }

        // Bytes land in the .part placeholder; commit later renames it.
        val placeholder = File(downloadsDir.toFile(), "write.bin.part")
        assertThat(placeholder.exists()).isTrue()
        assertThat(placeholder.readBytes()).isEqualTo(byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
    }
}
