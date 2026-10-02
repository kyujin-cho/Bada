/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui.sheet

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.max
import kotlin.math.min

/**
 * A horizontal rounded "pill" determinate progress bar: a light track with a
 * Bada-blue fill whose width tracks [setProgress] (0..100), animated toward each
 * new target. Used by the consent receive sheet's Receiving panel.
 *
 * Even at 0% a short rounded dot is drawn at the left so the bar reads as
 * "started" before the transfer total is known, matching the dot the consent
 * panel shows while it waits for the first chunk.
 *
 * Programmatic custom view: corner radii, stroke widths, and ARGB colour
 * components are inherently numeric drawing constants, so MagicNumber is suppressed.
 */
@Suppress("MagicNumber")
public class RoundedProgressBar
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        private val density = context.resources.displayMetrics.density

        private val trackPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRACK_COLOR }
        private val fillPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FILL_COLOR }

        /** Current displayed fill fraction in [0, 1]; animated toward [targetFraction]. */
        private var fraction: Float = 0f

        /** Latest fill fraction requested through [setProgress], in [0, 1]. */
        private var targetFraction: Float = 0f
        private var animator: ValueAnimator? = null

        private val trackRect = RectF()
        private val fillRect = RectF()

        /**
         * Set the progress fill to [percent] (0..100), animating from the current
         * fill. Clamped to the valid range; safe to call on every progress tick.
         *
         * A call that lands while a segment is animating only moves the target; the
         * running segment re-arms toward the latest target when it ends. Restarting
         * the animator on every call froze the fill (#298): the receive sheet calls
         * this once per payload chunk, hundreds of times a second on Wi-Fi LAN, and
         * a freshly started ValueAnimator does not advance on its first frame, so the
         * bar sat near empty while the percent label kept counting.
         */
        public fun setProgress(percent: Int) {
            targetFraction = (percent.coerceIn(0, 100)) / 100f
            if (animator?.isStarted != true) {
                animateTowardTarget()
            }
        }

        private fun animateTowardTarget() {
            if (fraction == targetFraction) return
            animator =
                ValueAnimator.ofFloat(fraction, targetFraction).apply {
                    duration = ANIM_MS
                    interpolator = DecelerateInterpolator()
                    addUpdateListener {
                        fraction = it.animatedValue as Float
                        invalidate()
                    }
                    addListener(
                        object : AnimatorListenerAdapter() {
                            private var cancelled = false

                            override fun onAnimationCancel(animation: Animator) {
                                cancelled = true
                            }

                            override fun onAnimationEnd(animation: Animator) {
                                if (!cancelled) animateTowardTarget()
                            }
                        },
                    )
                    start()
                }
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            animator = null
            super.onDetachedFromWindow()
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            // Track height defaults to BAR_HEIGHT_DP when the layout left it as
            // wrap_content; width is whatever the parent grants.
            val h = resolveSize((BAR_HEIGHT_DP * density).toInt(), heightMeasureSpec)
            val w = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
            setMeasuredDimension(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val radius = h / 2f

            trackRect.set(0f, 0f, w, h)
            canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

            // Keep a minimum visible cap (a "dot") so 0% still reads as started.
            val fillWidth = max(h, min(w, w * fraction))
            fillRect.set(0f, 0f, fillWidth, h)
            canvas.drawRoundRect(fillRect, radius, radius, fillPaint)
        }

        private companion object {
            private const val BAR_HEIGHT_DP = 8f
            private const val ANIM_MS = 240L

            // Light neutral track; Bada blue (#0A84FF) fill — same accent the
            // ring progress + device badges use.
            private const val TRACK_COLOR = 0xFFE3E6EC.toInt()
            private const val FILL_COLOR = 0xFF0A84FF.toInt()
        }
    }
