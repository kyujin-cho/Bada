/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.send

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [QrMatrix].
 *
 * The QR-bitmap pipeline used by [ShowQrActivity] (#84) is split into:
 *
 *  - [QrMatrix.encode] — pure JVM, returns a ZXing `BitMatrix`. Tested
 *    here because it is the half that does all the validation, hint
 *    wiring, and ZXing invocation — i.e. everything that can plausibly
 *    go wrong on the encode side.
 *  - [QrBitmapRenderer.render] — Android-only adapter (requires
 *    `android.graphics.Bitmap`). A thin pixel-blit; left untested
 *    because the only failure mode is bitmap allocation, which is
 *    well-trodden Android territory and would require Robolectric to
 *    exercise on a host JVM.
 *
 * We do **not** verify the encoded matrix decodes back to the original
 * URL: doing so would require running ZXing's decoder against a
 * camera-style luminance source, which is overkill for verifying that
 * "we asked ZXing to encode something and it did". The tests below
 * cover dimensions, argument validation, and that a typical Quick
 * Share URL doesn't trip the encoder.
 */
class QrMatrixTest {
    /**
     * Sample URL shaped like the real Quick Share QR URL produced by
     * [io.github.kyujincho.wvmg.protocol.qr.QrUrl.build]. Length is
     * representative (~85 chars), so this exercises the encoder at the
     * payload size we'll see in production.
     */
    private val sampleUrl: String =
        "https://quickshare.google/qrcode#key=" +
            "AkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQg"

    @Test
    fun `encode produces a square BitMatrix at the requested size`() {
        // QR codes are always square; ZXing pads/scales to the requested
        // dimensions when both are equal and >= the natural module count.
        val size = 256
        val matrix = QrMatrix.encode(sampleUrl, size)

        assertEquals(size, matrix.width)
        assertEquals(size, matrix.height)
    }

    @Test
    fun `encode produces a non-blank matrix for a typical URL`() {
        // A blank matrix would mean ZXing silently returned an empty
        // canvas. We check that at least one cell is dark — a real QR
        // code has roughly 50% dark cells, so this is a very loose
        // sanity gate.
        val matrix = QrMatrix.encode(sampleUrl, 200)

        var anyDark = false
        outer@ for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) {
                    anyDark = true
                    break@outer
                }
            }
        }
        assertTrue("encoded matrix must contain at least one dark cell", anyDark)
    }

    @Test
    fun `encode honours a custom error-correction level`() {
        // Just verify the parameter is plumbed through — encoding under
        // L vs H usually changes the module count, but at a fixed pixel
        // size (200) ZXing scales both to the same dimensions, so we
        // can't verify visually. Instead we make sure both calls succeed
        // and produce same-sized matrices.
        val low = QrMatrix.encode(sampleUrl, 200, ErrorCorrectionLevel.L)
        val high = QrMatrix.encode(sampleUrl, 200, ErrorCorrectionLevel.H)

        assertEquals(200, low.width)
        assertEquals(200, high.width)
    }

    @Test
    fun `encode rejects empty content`() {
        // ZXing throws WriterException on empty input with an opaque
        // message. We validate up front so callers get a clearer
        // IllegalArgumentException with a stable message.
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                QrMatrix.encode("", 200)
            }
        assertTrue(
            "error message should mention non-empty: ${ex.message}",
            ex.message?.contains("non-empty") == true,
        )
    }

    @Test
    fun `encode rejects non-positive size`() {
        // size <= 0 is meaningless — ZXing would also reject it but
        // with a NegativeArraySizeException or similar. We surface a
        // proper IAE.
        assertThrows(IllegalArgumentException::class.java) {
            QrMatrix.encode(sampleUrl, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            QrMatrix.encode(sampleUrl, -5)
        }
    }

    @Test
    fun `encode is deterministic for identical inputs`() {
        // Determinism matters: two ShowQrActivity launches with the
        // same URL (e.g. screen rotation) should render bit-for-bit
        // identical bitmaps so any framing/contrast tweaks made on the
        // receiver's camera don't reset between attempts.
        val first = QrMatrix.encode(sampleUrl, 128)
        val second = QrMatrix.encode(sampleUrl, 128)

        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        for (y in 0 until first.height) {
            for (x in 0 until first.width) {
                assertEquals(
                    "matrix mismatch at ($x,$y)",
                    first.get(x, y),
                    second.get(x, y),
                )
            }
        }
    }

    @Test
    fun `encode handles a long URL without throwing`() {
        // Sanity check: even at the upper end of plausible Quick Share
        // URL lengths, the encoder should still produce a valid matrix
        // (ZXing automatically picks a higher QR version as needed).
        val longUrl = "https://quickshare.google/qrcode#key=" + "A".repeat(200)
        val matrix = QrMatrix.encode(longUrl, 256)

        assertEquals(256, matrix.width)
        assertEquals(256, matrix.height)
    }
}
