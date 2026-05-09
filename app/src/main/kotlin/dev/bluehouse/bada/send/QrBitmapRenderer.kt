/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Android-only adapter that renders the BitMatrix produced by
 * [QrMatrix.encode] into a [Bitmap] suitable for display in an
 * `ImageView`.
 *
 * This file is deliberately *thin*: all the validation and ZXing wiring
 * lives in [QrMatrix] (pure JVM, fully testable). The only Android-
 * specific concern here is allocating the [Bitmap] and blitting the
 * matrix into it — kept minimal so the Android-only surface is small
 * enough to be left untested without losing meaningful coverage.
 *
 * Performance note: a naive per-cell `setPixel(x, y, color)` loop is
 * orders of magnitude slower than building an `IntArray` row-by-row and
 * pushing it via `setPixels(...)` in a single call. For a 800×800
 * bitmap (typical phone QR display size) the difference is roughly
 * "imperceptible" vs. "noticeable jank" on mid-range hardware, so we
 * use the bulk-blit path.
 */
public object QrBitmapRenderer {
    /**
     * Render [content] as a square QR-code [Bitmap] of [size]×[size]
     * pixels.
     *
     * Dark cells map to [Color.BLACK]; light cells map to [Color.WHITE].
     * The returned bitmap uses [Bitmap.Config.ARGB_8888] — overkill for
     * a strictly two-colour image, but it is the most broadly compatible
     * config and keeps the code straightforward.
     *
     * @param content URL or arbitrary string to encode. Must be non-empty.
     * @param size Edge length of the resulting square bitmap, in pixels.
     *   Must be strictly positive.
     * @throws IllegalArgumentException if [content] is empty or [size]
     *   is non-positive (validated by [QrMatrix.encode]).
     */
    public fun render(
        content: String,
        size: Int,
    ): Bitmap {
        val matrix = QrMatrix.encode(content, size)
        val width = matrix.width
        val height = matrix.height

        // Build the pixel buffer in one pass and push it to the bitmap
        // via a single setPixels(...) call. This is dramatically faster
        // than per-cell setPixel() — see class docs for the rationale.
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                pixels[rowOffset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
