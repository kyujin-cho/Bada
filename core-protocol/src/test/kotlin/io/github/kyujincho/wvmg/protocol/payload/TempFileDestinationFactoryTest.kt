/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.payload

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for [TempFileDestinationFactory] — the default `:core-protocol`
 * implementation of [FileDestinationFactory] that writes to a temp dir.
 *
 * The Android production wiring uses a different factory entirely (one
 * that hands back a `MediaStore` content URI's output stream), but
 * `:core-protocol` and the JVM-side host harness both rely on this file
 * factory, so we cover its filename sanitization and the open/close
 * lifecycle here.
 */
class TempFileDestinationFactoryTest {
    @Test
    fun `factory writes bytes to a file under the configured directory`(
        @TempDir tmp: Path,
    ) {
        val factory = TempFileDestinationFactory(baseDirectory = tmp)
        val header =
            PayloadHeader
                .newBuilder()
                .setId(42)
                .setType(PayloadHeader.PayloadType.FILE)
                .setTotalSize(5)
                .setFileName("hello.bin")
                .build()
        factory.open(header).use { ch ->
            ch.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5)))
        }
        val expected = tmp.resolve("payload-42-hello.bin")
        assertThat(Files.exists(expected)).isTrue()
        assertThat(Files.readAllBytes(expected)).isEqualTo(byteArrayOf(1, 2, 3, 4, 5))
    }

    @Test
    fun `factory sanitizes path-traversal-style file names`(
        @TempDir tmp: Path,
    ) {
        val factory = TempFileDestinationFactory(baseDirectory = tmp)
        val header =
            PayloadHeader
                .newBuilder()
                .setId(1)
                .setType(PayloadHeader.PayloadType.FILE)
                .setTotalSize(0)
                .setFileName("../../../etc/passwd")
                .build()
        factory.open(header).use { /* nothing to write */ }
        // Sanitized name keeps the safe set [A-Za-z0-9._-] and replaces
        // the rest with '_'. The crucial security property is "no path
        // separators leak through" — `..` substrings on their own are
        // harmless without separators. The file MUST sit directly under
        // the configured base directory.
        val children = Files.list(tmp).use { it.toList() }
        assertThat(children).hasSize(1)
        val produced = children.single().fileName
        assertThat(produced.toString()).startsWith("payload-1-")
        assertThat(produced.toString()).doesNotContain("/")
        assertThat(produced.toString()).doesNotContain("\\")
        // And the produced path's parent is exactly the configured tmp.
        assertThat(children.single().parent).isEqualTo(tmp)
    }

    @Test
    fun `factory falls back to unnamed when fileName is empty`(
        @TempDir tmp: Path,
    ) {
        val factory = TempFileDestinationFactory(baseDirectory = tmp)
        val header =
            PayloadHeader
                .newBuilder()
                .setId(7)
                .setType(PayloadHeader.PayloadType.FILE)
                .setTotalSize(0)
                .build()
        factory.open(header).use { /* no-op */ }
        val children = Files.list(tmp).use { it.toList() }
        val produced = children.single().fileName.toString()
        assertThat(produced).isEqualTo("payload-7-unnamed")
    }
}
