/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import dev.bluehouse.libredrop.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Activity-level ambient backdrop for the always-visible mode. Sits
 * at the bottom of the activity z-stack — the toolbar, fragment
 * content, and bottom-nav all paint on top of it, so the pulse reads
 * as the entire page's "alive" backing surface rather than a button
 * effect.
 *
 * When [startPulse] is active, draws a small fixed set of soft
 * radial-gradient blobs that slowly drift around their anchor
 * positions in lazily-orbiting circles. Each blob is rendered as a
 * single radial shader fade — opaque-ish blue at the centre,
 * transparent at the rim — so on the canvas it reads as a soft
 * blurred glow without any explicit BlurMaskFilter pass (cheaper on
 * the GPU; no software-layer requirement).
 *
 * The peak alpha per blob is intentionally tiny ([CENTER_ALPHA] /
 * 255 ≈ 8%) — even with multiple blobs overlapping, the cumulative
 * effect should read as "very subtle". Raising [CENTER_ALPHA]
 * meaningfully will start to drown the foreground UI.
 */
internal class VisibilityPulseView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        private val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }

        private val baseColor = ContextCompat.getColor(context, R.color.brand_primary)
        private val density = resources.displayMetrics.density

        private var blobs: List<Blob> = emptyList()

        /**
         * Animator runs continuously while pulsing. Its current play
         * time (in ms) drives every blob's drift phase, so all blobs
         * stay in sync and a single invalidate per frame redraws the
         * whole layer.
         */
        private val animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                // Long duration so the animator's `currentPlayTime`
                // reads as a continuous wall-clock-like seconds counter
                // before it loops. Each blob's per-period drift uses
                // the modulo of its own period, so they never lock to
                // this outer cycle visibly.
                duration = ANIMATOR_DURATION_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { invalidate() }
            }

        init {
            // Start fully invisible — `startPulse` fades up to 1.0,
            // `stopPulse` fades back to 0. Without this initial state
            // the very first toggle on would pop in at full opacity
            // before the fade animator could take effect.
            alpha = 0f
        }

        /**
         * Start the ambient pulse. Smoothly fades the view up to
         * fully visible while the underlying ValueAnimator runs the
         * blob drift in the background. Idempotent.
         */
        fun startPulse() {
            if (!animator.isStarted) {
                animator.start()
            }
            animate()
                .alpha(1f)
                .setDuration(FADE_DURATION_MS)
                .withLayer()
                .start()
        }

        /**
         * Fade the pulse out and stop the underlying animator once
         * the fade completes. Stopping the animator earlier would
         * freeze the blobs mid-drift; chaining the cancel into the
         * fade-out's end-action keeps the motion alive while the
         * alpha falls to zero, then drops the per-frame work.
         */
        fun stopPulse() {
            animate()
                .alpha(0f)
                .setDuration(FADE_DURATION_MS)
                .withLayer()
                .withEndAction {
                    if (alpha == 0f) {
                        animator.cancel()
                    }
                }.start()
        }

        @Suppress("MagicNumber")
        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w == 0 || h == 0) {
                blobs = emptyList()
                return
            }
            val wF = w.toFloat()
            val hF = h.toFloat()
            // Five blobs at fixed normalized anchor positions across
            // the page, each with a unique drift period and phase so
            // their orbits never align visually. Sizes are slightly
            // varied so the cluster does not read as five identical
            // discs. Drift radius of `60dp` keeps the motion gentle
            // (well under the blob's own radius), so each blob feels
            // like it is "breathing in place" rather than skittering
            // across the screen.
            blobs =
                listOf(
                    Blob(wF * 0.20f, hF * 0.18f, 60f * density, 14f, 0.00f, 180f * density),
                    Blob(wF * 0.78f, hF * 0.28f, 70f * density, 19f, 0.22f, 200f * density),
                    Blob(wF * 0.50f, hF * 0.50f, 80f * density, 23f, 0.45f, 220f * density),
                    Blob(wF * 0.22f, hF * 0.74f, 65f * density, 17f, 0.68f, 190f * density),
                    Blob(wF * 0.80f, hF * 0.84f, 75f * density, 21f, 0.86f, 210f * density),
                )
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            // Drop the animator when the view leaves the window;
            // otherwise a backgrounded activity would keep the
            // Choreographer ticking and silently invalidate a
            // detached View on every frame.
            animator.cancel()
        }

        @Suppress("MagicNumber")
        override fun onDraw(canvas: Canvas) {
            if (!animator.isStarted) {
                return
            }
            if (blobs.isEmpty()) {
                return
            }
            // Continuous time in seconds. Resets every ANIMATOR_DURATION_MS
            // but blob drift uses each blob's own period so the reset
            // is invisible (every blob has already wrapped many times
            // by then).
            val timeSec = animator.currentPlayTime.toFloat() / 1000f
            val rgb = baseColor and RGB_MASK
            for (blob in blobs) {
                val angle = ((timeSec / blob.periodSec + blob.phase) % 1f) * TAU
                val cx = blob.anchorX + cos(angle) * blob.driftRadius
                val cy = blob.anchorY + sin(angle) * blob.driftRadius
                // Multi-stop radial gradient gives a softer falloff
                // than the standard 2-stop linear-alpha ramp — the
                // blob reads as "blurred" rather than as a solid disc
                // with a hard rim.
                paint.shader =
                    RadialGradient(
                        cx,
                        cy,
                        blob.radius,
                        intArrayOf(
                            (CENTER_ALPHA shl ALPHA_BIT_SHIFT) or rgb,
                            ((CENTER_ALPHA / 2) shl ALPHA_BIT_SHIFT) or rgb,
                            (CENTER_ALPHA / 6 shl ALPHA_BIT_SHIFT) or rgb,
                            0,
                        ),
                        floatArrayOf(0f, 0.4f, 0.75f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                canvas.drawCircle(cx, cy, blob.radius, paint)
            }
        }

        /**
         * One floating gradient blob. Stays anchored at ([anchorX],
         * [anchorY]) and orbits in a circle of radius [driftRadius]
         * around it, completing one full lap every [periodSec]
         * seconds. [phase] (0..1) sets the initial position around
         * the orbit. [radius] is the blob's draw radius (the radial
         * gradient's outer edge).
         */
        private data class Blob(
            val anchorX: Float,
            val anchorY: Float,
            val driftRadius: Float,
            val periodSec: Float,
            val phase: Float,
            val radius: Float,
        )

        companion object {
            /**
             * Outer animator cycle. Long enough that blob drift
             * patterns wrap many times within it and the cycle
             * boundary is invisible.
             */
            private const val ANIMATOR_DURATION_MS: Long = 600_000L

            /**
             * Fade duration for both startPulse / stopPulse. 600 ms
             * is long enough to read as a deliberate "the backdrop is
             * coming alive / settling away" beat without making the
             * always-visible pill toggle feel sluggish.
             */
            private const val FADE_DURATION_MS: Long = 600L

            /**
             * Peak alpha at each blob's centre, out of 255. ~8% — the
             * spec asks for "매우 연하게" (very faint) gradient circles.
             */
            private const val CENTER_ALPHA = 22

            private const val ALPHA_BIT_SHIFT = 24
            private const val RGB_MASK = 0xFFFFFF
            private const val TAU: Float = (2.0 * Math.PI).toFloat()
        }
    }
