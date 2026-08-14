/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.gestureexchange

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Shader
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import java.util.concurrent.CopyOnWriteArraySet

/** Process-local visual state for file handoff; it is separate from Name Card state. */
internal object GestureVisualSignal {
    enum class State { OFF, OPEN, CONNECTED }

    fun interface Listener {
        fun onState(state: State)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile private var state = State.OFF

    @Volatile private var stateChangedAt = 0L

    fun attach(listener: Listener) {
        listeners += listener
        val current = state.takeIf { System.currentTimeMillis() - stateChangedAt <= MAX_REPLAY_AGE_MS } ?: State.OFF
        listener.onState(current)
    }

    fun detach(listener: Listener) {
        listeners -= listener
    }

    fun onProtocolEvent(event: String) {
        when {
            event == "reader_started" || event == "hce_selected" -> publish(State.OPEN)
            event.startsWith("reader_completed") || event == "hce_completed" -> publish(State.CONNECTED)
            event.startsWith("reader_failed") ||
                event.startsWith("hce_failed") ||
                event.startsWith("hce_prepare_failed") ||
                event.contains("completed=false") -> publish(State.OFF)
        }
    }

    fun clear() {
        publish(State.OFF)
    }

    private fun publish(next: State) {
        state = next
        stateChangedAt = System.currentTimeMillis()
        listeners.forEach { it.onState(next) }
    }

    private const val MAX_REPLAY_AGE_MS = 5_000L
}

/** Adds and lifecycle-controls the non-interactive full-screen glow overlay. */
internal class GestureEdgeGlowController(
    private val activity: Activity,
) : AutoCloseable {
    private val view = GestureEdgeGlowView(activity)
    private val listener = GestureVisualSignal.Listener(view::render)
    private var attached = false

    init {
        activity.addContentView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    fun attach() {
        if (attached) return
        attached = true
        GestureVisualSignal.attach(listener)
    }

    fun detach() {
        if (!attached) return
        attached = false
        GestureVisualSignal.detach(listener)
        view.render(GestureVisualSignal.State.OFF)
    }

    override fun close() {
        detach()
        (view.parent as? ViewGroup)?.removeView(view)
    }
}

/**
 * View-system form of the mapped GMS bezel light: a symmetric top-centre
 * sweep, blurred halo, crisp comet, exact recognition and completion timing,
 * and deterministic time/easing animation only.
 */
@Suppress("MagicNumber") // Exact mapped GMS geometry, colour, and easing coordinates are intentionally literal.
private class GestureEdgeGlowView(
    context: Context,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val leftPath = Path()
    private val rightPath = Path()
    private val leftMeasure = PathMeasure()
    private val rightMeasure = PathMeasure()
    private val haloPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GLOW_COLOR
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 16f * density
            maskFilter = BlurMaskFilter(16f * density, BlurMaskFilter.Blur.NORMAL)
        }
    private val cometPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GLOW_COLOR
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 8f * density
        }
    private val dimPaint = Paint().apply { color = 0x99000000.toInt() }
    private var openProgress = 0f
    private var loopHead = 0f
    private var loopTail = 0f
    private var dimVisible = false
    private var animator: ValueAnimator? = null
    private var tailAnimator: ValueAnimator? = null

    init {
        visibility = GONE
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        elevation = 1000f * density
    }

    fun render(state: GestureVisualSignal.State) {
        post {
            when (state) {
                GestureVisualSignal.State.OFF -> reset()
                GestureVisualSignal.State.OPEN -> open()
                GestureVisualSignal.State.CONNECTED -> complete()
            }
        }
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = 16f * density
        val topLeft = cornerRadius(RoundedCorner.POSITION_TOP_LEFT) + inset
        val topRight = cornerRadius(RoundedCorner.POSITION_TOP_RIGHT) + inset
        val bottomLeft = cornerRadius(RoundedCorner.POSITION_BOTTOM_LEFT) + inset
        val bottomRight = cornerRadius(RoundedCorner.POSITION_BOTTOM_RIGHT) + inset
        leftPath.reset()
        leftPath.moveTo(w / 2f, 0f)
        leftPath.lineTo(topLeft, 0f)
        leftPath.quadTo(0f, 0f, 0f, topLeft)
        leftPath.lineTo(0f, h - bottomLeft)
        leftPath.quadTo(0f, h.toFloat(), bottomLeft, h.toFloat())
        leftPath.lineTo(w / 2f, h.toFloat())
        rightPath.reset()
        rightPath.moveTo(w / 2f, 0f)
        rightPath.lineTo(w - topRight, 0f)
        rightPath.quadTo(w.toFloat(), 0f, w.toFloat(), topRight)
        rightPath.lineTo(w.toFloat(), h - bottomRight)
        rightPath.quadTo(w.toFloat(), h.toFloat(), w - bottomRight, h.toFloat())
        rightPath.lineTo(w / 2f, h.toFloat())
        leftMeasure.setPath(leftPath, false)
        rightMeasure.setPath(rightPath, false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dimVisible) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        drawEdge(canvas, leftMeasure)
        drawEdge(canvas, rightMeasure)
    }

    private fun drawEdge(
        canvas: Canvas,
        measure: PathMeasure,
    ) {
        val full = measure.length
        val partial = full * PARTIAL_FRACTION
        val start = loopTail * full
        val end =
            if (loopHead > 0f || loopTail > 0f) {
                partial + loopHead * (full - partial)
            } else {
                openProgress * partial
            }
        if (end <= start) return
        val halo = Path()
        measure.getSegment(start, (end + 8f * density).coerceAtMost(full), halo, true)
        canvas.drawPath(halo, haloPaint)

        val tailLength = 30f * density
        val solidEnd = (end - (full - end).coerceIn(0f, tailLength)).coerceAtLeast(start)
        val solid = Path()
        measure.getSegment(start, solidEnd, solid, true)
        cometPaint.shader = null
        canvas.drawPath(solid, cometPaint)
        if (solidEnd < end) {
            val tail = Path()
            val from = FloatArray(2)
            val to = FloatArray(2)
            measure.getSegment(solidEnd, end, tail, true)
            measure.getPosTan(solidEnd, from, null)
            measure.getPosTan(end, to, null)
            cometPaint.shader =
                LinearGradient(
                    from[0],
                    from[1],
                    to[0],
                    to[1],
                    GLOW_COLOR,
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                )
            canvas.drawPath(tail, cometPaint)
            cometPaint.shader = null
        }
    }

    private fun open() {
        cancelAnimators()
        visibility = VISIBLE
        openProgress = 0f
        loopHead = 0f
        loopTail = 0f
        dimVisible = false
        var started = false
        animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = OPEN_DELAY_MS
                duration = OPEN_DURATION_MS
                interpolator = shootInterpolator()
                addUpdateListener {
                    if (!started) {
                        started = true
                        dimVisible = true
                        heavyClick()
                    }
                    openProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
    }

    private fun complete() {
        if (visibility != VISIBLE) visibility = VISIBLE
        cancelAnimators()
        dimVisible = false
        heavyClick()
        animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = HEAD_DURATION_MS
                interpolator = PathInterpolator(0.3f, 0f, 0.1f, 1f)
                addUpdateListener {
                    loopHead = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        tailAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = TAIL_DURATION_MS
                interpolator = shootInterpolator()
                addUpdateListener {
                    loopTail = it.animatedValue as Float
                    invalidate()
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            GestureVisualSignal.clear()
                        }
                    },
                )
                start()
            }
    }

    private fun reset() {
        cancelAnimators()
        openProgress = 0f
        loopHead = 0f
        loopTail = 0f
        dimVisible = false
        visibility = GONE
        invalidate()
    }

    private fun cancelAnimators() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        tailAnimator?.removeAllListeners()
        tailAnimator?.cancel()
        tailAnimator = null
    }

    private fun cornerRadius(position: Int): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            rootWindowInsets?.getRoundedCorner(position)?.radius?.toFloat() ?: 0f
        } else {
            0f
        }

    private fun heavyClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val vibrator =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                        ?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
            vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        } else {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun shootInterpolator(): PathInterpolator {
        val path = Path()
        path.moveTo(0f, 0f)
        path.cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
        path.cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
        return PathInterpolator(path)
    }

    private companion object {
        private const val GLOW_COLOR = 0xFF8AB4F8.toInt()
        private const val PARTIAL_FRACTION = 0.3f
        private const val OPEN_DELAY_MS = 300L
        private const val OPEN_DURATION_MS = 1_992L
        private const val HEAD_DURATION_MS = 433L
        private const val TAIL_DURATION_MS = 1_510L
    }
}
