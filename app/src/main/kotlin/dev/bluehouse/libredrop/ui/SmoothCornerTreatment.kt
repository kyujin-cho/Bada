/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.ui

import com.google.android.material.shape.CornerTreatment
import com.google.android.material.shape.ShapePath

/**
 * G2-style smooth corner treatment for ShapeableImageView /
 * MaterialShapeDrawable. Replaces the default
 * `RoundedCornerTreatment` (which renders the corner as a quarter
 * circle — G1 continuity, with a visible curvature jump where the
 * arc meets the straight edge) with a single cubic Bézier whose
 * control points are tuned so the curvature transitions smoothly
 * from zero (on the straight edges) into the corner sweep — a
 * close practical approximation of G2 continuity.
 *
 * The visual difference matters most on small radius / small
 * canvas combinations like the 12dp-corner / 64dp-square avatars
 * on the Credit screen, where the AOSP arc rendering antialiases
 * just a couple of pixels at the corner shoulder and reads as a
 * "clumped" corner. The cubic ramp distributes that transition
 * across more pixels, giving the antialiaser more room to work
 * and producing a noticeably softer corner.
 *
 * The Bézier control-point factor [SHOULDER_FACTOR] is the knob
 * that trades "circle-like" (≈ 0.55) vs "squircle-like" (lower
 * values, sharper shoulder transitions). 0.45 is the iOS-icon-ish
 * sweet spot — visibly different from a pure circle, still reads
 * as a "rounded corner" rather than a pinched custom shape.
 */
internal class SmoothCornerTreatment : CornerTreatment() {
    override fun getCornerPath(
        shapePath: ShapePath,
        angle: Float,
        interpolation: Float,
        radius: Float,
    ) {
        val r = radius * interpolation
        val k = SHOULDER_FACTOR
        // Local coord space: the corner starts on the previous edge
        // at (0, r) and ends on the next edge at (r, 0). The cubic
        // hugs the straight edges initially (control points at
        // (0, r * (1-k)) and (r * k, 0)) before curving into the
        // corner — that gradual ramp is what produces the G2-ish
        // continuous-curvature feel.
        shapePath.reset(0f, r, ANGLE_LEFT, ANGLE_LEFT - angle)
        shapePath.cubicToPoint(
            0f,
            r * (1f - k),
            r * k,
            0f,
            r,
            0f,
        )
    }

    private companion object {
        /**
         * The angle at which a CornerTreatment begins relative to
         * ShapePath's coordinate system. 180° is the value the
         * built-in `RoundedCornerTreatment` uses for every corner
         * (the per-corner rotation is handled by the surrounding
         * `MaterialShapeDrawable`); mirroring it here keeps the
         * smooth corner orientation correct on all four corners.
         */
        const val ANGLE_LEFT: Float = 180.0f

        /**
         * Bézier shoulder factor. Lower → sharper "squircle"
         * shoulder (the curve hugs the straight edge longer before
         * curving into the corner); higher → closer to a circle.
         */
        const val SHOULDER_FACTOR: Float = 0.45f
    }
}
