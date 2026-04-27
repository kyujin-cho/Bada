/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.send

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Pure-JVM half of the QR-bitmap rendering pipeline used by
 * [ShowQrActivity] (#84).
 *
 * Splitting the pipeline into a [BitMatrix]-producing helper (this file,
 * pure JVM, fully testable on a host JVM) and a separate `BitMatrix → Bitmap`
 * adapter ([QrBitmapRenderer], Android-only) keeps the testable surface
 * as wide as possible: argument validation, ZXing wiring, and the chosen
 * error-correction level can all be exercised without Robolectric.
 *
 * The Android-only adapter is intentionally a thin pixel-blit; once the
 * pure-JVM [encode] step has produced a correct [BitMatrix], the only
 * thing that can still go wrong on-device is allocating a [Bitmap] of the
 * right size — and that is well-trodden Android territory.
 */
public object QrMatrix {
    /**
     * Default ZXing error-correction level used for the Quick Share QR.
     *
     * `M` (~15% recovery) is the conventional pick for short URLs shown
     * on a phone screen — the URL fits well below the version-cap at this
     * level, and `M` gives the receiver's camera enough redundancy to
     * decode through glare and modest screen smudging without bloating
     * the module count.
     */
    public val DEFAULT_ERROR_CORRECTION: ErrorCorrectionLevel = ErrorCorrectionLevel.M

    /**
     * Encode [content] as a QR-code [BitMatrix] of the requested pixel
     * dimensions.
     *
     * @param content The string to encode. Must be non-empty — ZXing
     *   throws an opaque `WriterException` for empty input, so we
     *   validate up front for a clearer error.
     * @param size The desired bitmap edge length in pixels (square).
     *   Must be strictly positive.
     * @param errorCorrection Error-correction level. Defaults to
     *   [DEFAULT_ERROR_CORRECTION].
     * @return A [BitMatrix] where `matrix[x, y] == true` means the cell
     *   should be drawn dark (foreground) and `false` means light
     *   (background).
     * @throws IllegalArgumentException if [content] is empty or [size]
     *   is non-positive.
     */
    public fun encode(
        content: String,
        size: Int,
        errorCorrection: ErrorCorrectionLevel = DEFAULT_ERROR_CORRECTION,
    ): BitMatrix {
        require(content.isNotEmpty()) { "QR content must be non-empty" }
        require(size > 0) { "QR size must be positive, got $size" }

        val hints =
            mapOf<EncodeHintType, Any>(
                // Recovery level — see DEFAULT_ERROR_CORRECTION docs.
                EncodeHintType.ERROR_CORRECTION to errorCorrection,
                // Quick Share URLs are pure ASCII; pinning the charset
                // keeps the encoded byte segments deterministic.
                EncodeHintType.CHARACTER_SET to "UTF-8",
                // Standard 4-module quiet zone. ZXing defaults to 4
                // already, but we set it explicitly to make the
                // decoder-friendly margin visible at this seam.
                EncodeHintType.MARGIN to 1,
            )

        return QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    }
}
