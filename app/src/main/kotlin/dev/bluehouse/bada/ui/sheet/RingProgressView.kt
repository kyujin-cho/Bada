/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui.sheet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import dev.bluehouse.bada.R
import kotlin.math.max
import kotlin.math.min

/**
 * A circular avatar: a filled blue disc with a centered vector icon,
 * ringed by a progress arc (12 o'clock origin, sweeps clockwise). Used
 * for each discovered device in the send bottom sheet; the ring tracks
 * transfer progress, and the disc morphs into a green check on
 * completion.
 *
 * The centered icon is a VectorDrawable resource ([setIconResId]);
 * it defaults to the smartphone glyph so a bare view still renders a
 * device icon, mirroring the former emoji default (#277).
 *
 * Programmatic custom view: stroke widths, sweep angles, and ARGB colour
 * components are inherently numeric drawing constants, so MagicNumber is suppressed.
 */
@Suppress("MagicNumber")
public class RingProgressView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arc = RectF()

        private var progress: Int = -1 // -1 = no ring shown (idle); 0..100 shows the arc
        private var complete: Boolean = false

        /**
         * Centered icon drawn on the disc. Inflated once per [setIconResId]
         * call; the disc icon carries its own white fill from the drawable
         * resource, so no paint is involved.
         */
        private var icon: Drawable = context.getDrawable(R.drawable.ic_device_smartphone_24)!!

        init {
            val density = context.resources.displayMetrics.density
            discPaint.color = ACCENT
            trackPaint.style = Paint.Style.STROKE
            trackPaint.strokeWidth = 3 * density
            trackPaint.color = TRACK
            ringPaint.style = Paint.Style.STROKE
            ringPaint.strokeWidth = 3 * density
            ringPaint.strokeCap = Paint.Cap.ROUND
            ringPaint.color = RING
            tickPaint.color = Color.WHITE
            tickPaint.style = Paint.Style.STROKE
            tickPaint.strokeCap = Paint.Cap.ROUND
            tickPaint.strokeWidth = 4 * density
        }

        /** Morph the icon into a green disc with a white check (transfer complete). */
        public fun setComplete() {
            this.complete = true
            invalidate()
        }

        public fun setProgress(percent: Int) {
            this.progress = percent
            invalidate()
        }

        /** Swap the centered disc icon for the drawable at [resId]. */
        public fun setIconResId(resId: Int) {
            this.icon = context.getDrawable(resId)!!
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val w = width
            val h = height
            val cx = w / 2f
            val cy = h / 2f
            val ringInset = ringPaint.strokeWidth
            val discR = min(w, h) / 2f - ringInset * 2f

            if (complete) {
                discPaint.color = GREEN
                c.drawCircle(cx, cy, discR, discPaint)
                val p = Path()
                p.moveTo(cx - discR * 0.40f, cy + discR * 0.02f)
                p.lineTo(cx - discR * 0.08f, cy + discR * 0.32f)
                p.lineTo(cx + discR * 0.44f, cy - discR * 0.30f)
                c.drawPath(p, tickPaint)
                return
            }

            discPaint.color = ACCENT
            c.drawCircle(cx, cy, discR, discPaint)

            // Vector icon centered on the disc, sized as a fraction of the
            // disc radius. Tuned on a real device (vivo, #277): 0.7 read
            // oversized against the ring, 10% smaller sits right.
            val half = discR * ICON_DISC_FRACTION
            icon.setBounds(
                (cx - half).toInt(),
                (cy - half).toInt(),
                (cx + half).toInt(),
                (cy + half).toInt(),
            )
            icon.draw(c)

            if (progress >= 0) {
                val r = min(w, h) / 2f - ringInset
                arc.set(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(arc, 0f, 360f, false, trackPaint)
                c.drawArc(arc, -90f, 360f * max(0, min(100, progress)) / 100f, false, ringPaint)
            }
        }

        public companion object {
            private const val ACCENT = 0xFF2F8BFF.toInt() // blue disc
            private const val TRACK = 0x33FFFFFF // faint ring track
            private const val RING = 0xFFFFFFFF.toInt() // progress arc (white)
            private const val GREEN = 0xFF00BD13.toInt()
            private const val ICON_DISC_FRACTION = 0.63f // icon half-size as a fraction of discR
        }
    }
