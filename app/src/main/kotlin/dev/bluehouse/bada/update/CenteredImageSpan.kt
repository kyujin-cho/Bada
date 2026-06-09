/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.update

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.style.ImageSpan

/**
 * `ImageSpan` variant that paints the drawable at the **vertical centre
 * of the line** instead of pinned to the typographic baseline.
 *
 * `ImageSpan.ALIGN_CENTER` is the platform's centred-alignment mode,
 * but it was only added in API 29 — Bada's `minSdk` is 24, so we need
 * a hand-rolled draw that runs on every supported version. The dot
 * appended to the "Check for updates" overflow item title uses this
 * span so it sits visually centred against the large CJK glyphs of
 * the Korean / Japanese / Chinese menu labels, not anchored to the
 * baseline like the U+25CF / U+2022 text bullets would be.
 */
internal class CenteredImageSpan(
    drawable: Drawable,
) : ImageSpan(drawable, ALIGN_BASELINE) {
    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val drawableHeight = drawable.bounds.height()
        val transY = top + ((bottom - top) - drawableHeight) / 2
        canvas.save()
        canvas.translate(x, transY.toFloat())
        drawable.draw(canvas)
        canvas.restore()
    }
}
