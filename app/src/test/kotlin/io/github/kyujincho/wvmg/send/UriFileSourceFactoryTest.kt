/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel

/**
 * Pure-JVM tests for [UriFileSourceFactory].
 *
 * Per #24's testing guidance the URI → `FileSource` conversion is
 * tested via the injectable abstraction. The factory's URI-typed
 * surface ([UriFileSourceFactory.fromUri]) cannot be exercised on a
 * host JVM because `android.net.Uri` is an Android-only type — calling
 * `Uri.parse(...)` from a unit test throws "Method parse in
 * android.net.Uri not mocked".
 *
 * The conversion logic — display-name fallbacks, size coercion, MIME
 * defaulting, payload-id generation, channel-opener wiring — lives in
 * [UriFileSourceFactory.buildFileSource], which takes plain values and
 * is fully unit-testable. The thin URI-extraction shell (`fromUri` →
 * `buildFileSource`) is verified by inspection (the only Uri-typed
 * value it pulls is `lastPathSegment`, which `buildFileSource` accepts
 * as a plain `String?`).
 */
class UriFileSourceFactoryTest {
    private val sampleBytes: ByteArray = "hello quick share".toByteArray(Charsets.UTF_8)

    @Test
    fun `file source carries display name size and mime from metadata`() {
        val factory = factoryWithFixedId(1234)

        val source =
            factory.buildFileSource(
                metadata = UriMetadata("report.pdf", 4096, "application/pdf"),
                fallbackPathSegment = "ignored.bin",
                payloadId = 1234,
                open = { newChannel(sampleBytes) },
            )

        assertEquals("report.pdf", source.name)
        assertEquals(4096L, source.size)
        assertEquals("application/pdf", source.mimeType)
        assertEquals(1234L, source.payloadId)
        assertEquals(0L, source.lastModifiedTimestampMillis)
    }

    @Test
    fun `carries last modified seconds converted to millis on the wire`() {
        // Issue #41: MediaColumns.DATE_MODIFIED is documented as Unix
        // seconds. The proto wire format for
        // PayloadHeader.last_modified_timestamp_millis is millis. The
        // factory must convert at the boundary so the receiver sees the
        // sender's actual mtime (not 1000x off, not zero).
        val factory = factoryWithFixedId(2024)

        val source =
            factory.buildFileSource(
                metadata =
                    UriMetadata(
                        displayName = "dated.bin",
                        size = 4,
                        mimeType = "application/octet-stream",
                        lastModifiedSeconds = 1_700_000_000L,
                    ),
                fallbackPathSegment = null,
                payloadId = 2024,
                open = { newChannel(sampleBytes) },
            )

        assertEquals(1_700_000_000_000L, source.lastModifiedTimestampMillis)
    }

    @Test
    fun `zero last modified seconds produces zero millis on the wire`() {
        // Provider returned no DATE_MODIFIED column, surfaced as 0L.
        // The factory must keep that as 0L on the wire so the receiver
        // does not rewrite the file's mtime to the Unix epoch.
        val factory = factoryWithFixedId(2025)

        val source =
            factory.buildFileSource(
                metadata =
                    UriMetadata(
                        displayName = "undated.bin",
                        size = 0,
                        mimeType = null,
                        lastModifiedSeconds = 0L,
                    ),
                fallbackPathSegment = null,
                payloadId = 2025,
                open = { newChannel(sampleBytes) },
            )

        assertEquals(0L, source.lastModifiedTimestampMillis)
    }

    @Test
    fun `falls back to last path segment when display name missing`() {
        val factory = factoryWithFixedId(7)

        val source =
            factory.buildFileSource(
                metadata = UriMetadata(displayName = null, size = 1, mimeType = "text/plain"),
                fallbackPathSegment = "photo.jpg",
                payloadId = 7,
                open = { newChannel(sampleBytes) },
            )

        assertEquals("photo.jpg", source.name)
    }

    @Test
    fun `falls back to default name when display name and path segment missing`() {
        val factory = factoryWithFixedId(9)

        val source =
            factory.buildFileSource(
                metadata = UriMetadata(displayName = null, size = 1, mimeType = "text/plain"),
                fallbackPathSegment = null,
                payloadId = 9,
                open = { newChannel(sampleBytes) },
            )

        assertEquals(UriFileSourceFactory.DEFAULT_NAME, source.name)
    }

    @Test
    fun `coerces unknown size to zero`() {
        // OpenableColumns.SIZE is documented as -1 when unknown. The
        // protocol orchestrator requires a non-negative size — see
        // FileSource.init's `require(size >= 0)`.
        val factory = factoryWithFixedId(5)

        val source =
            factory.buildFileSource(
                metadata = UriMetadata(displayName = "a.bin", size = -1, mimeType = "application/octet-stream"),
                fallbackPathSegment = null,
                payloadId = 5,
                open = { newChannel(sampleBytes) },
            )

        assertEquals(0L, source.size)
    }

    @Test
    fun `null mime maps to empty string`() {
        // FileSource.mimeType is documented as accepting an empty
        // string but never null — the proto field is always present.
        val factory = factoryWithFixedId(3)

        val source =
            factory.buildFileSource(
                metadata = UriMetadata(displayName = "a.bin", size = 0, mimeType = null),
                fallbackPathSegment = null,
                payloadId = 3,
                open = { newChannel(sampleBytes) },
            )

        assertEquals("", source.mimeType)
    }

    @Test
    fun `openChannel forwards to the supplied lambda each call`() {
        val factory = factoryWithFixedId(11)
        val expectedChannel = newChannel(sampleBytes)
        var openInvocations = 0

        val source =
            factory.buildFileSource(
                metadata = UriMetadata(displayName = "x.bin", size = sampleBytes.size.toLong(), mimeType = ""),
                fallbackPathSegment = null,
                payloadId = 11,
                open = {
                    openInvocations++
                    expectedChannel
                },
            )

        // The factory must defer the channel open until the orchestrator
        // explicitly asks for it (FileSource.openChannel) — eager opens
        // would leak InputStreams when the user backs out of the picker.
        assertEquals("openChannel must not be called eagerly", 0, openInvocations)

        val channel = source.openChannel()

        assertSame(expectedChannel, channel)
        assertEquals(1, openInvocations)
    }

    @Test
    fun `random payload id is positive`() {
        // Smoke-test the default payload-id generator: the protocol
        // orchestrator demands positive ids (`f.payloadId > 0`).
        repeat(64) {
            val id = UriFileSourceFactory.randomPositivePayloadId()
            assertTrue("payload id must be positive, got $id", id > 0)
        }
    }

    @Test
    fun `random payload ids vary across invocations`() {
        // Probabilistically, two consecutive 63-bit random ids should
        // differ — failing this means we accidentally cached a value.
        val first = UriFileSourceFactory.randomPositivePayloadId()
        val second = UriFileSourceFactory.randomPositivePayloadId()
        assertNotEquals(first, second)
    }

    // -----------------------------------------------------------------
    // Helpers — kept minimal so the test focuses on the conversion rules.
    // -----------------------------------------------------------------

    /**
     * Build a factory with stubs for the metadata reader and channel
     * opener. The stubs are unused by the [UriFileSourceFactory.buildFileSource]
     * tests (which exercise the pure-JVM core directly), so we plug in
     * NoOp implementations.
     */
    private fun factoryWithFixedId(id: Long): UriFileSourceFactory =
        UriFileSourceFactory(
            metadataReader = NoOpMetadataReader,
            channelOpener = NoOpChannelOpener,
            payloadIdGenerator = { id },
        )

    private fun newChannel(bytes: ByteArray): ReadableByteChannel = Channels.newChannel(ByteArrayInputStream(bytes))

    private object NoOpMetadataReader : UriMetadataReader {
        override fun read(uri: android.net.Uri): UriMetadata =
            error("NoOpMetadataReader.read should not be invoked in buildFileSource tests")
    }

    private object NoOpChannelOpener : UriChannelOpener {
        override fun open(uri: android.net.Uri): ReadableByteChannel =
            error("NoOpChannelOpener.open should not be invoked in buildFileSource tests")
    }
}
