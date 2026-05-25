/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi

/**
 * A view that paints a **frosted "liquid glass" plate**: it captures the
 * sibling content sitting behind it, blurs that capture, and draws it
 * (clipped to a rounded rectangle) with a translucent tint on top. Place
 * it directly behind a transparent-background control (e.g. a button) so
 * the control reads as a glass plate floating over the content behind it.
 *
 * On API < 31 (no [RenderEffect]) it falls back to just the tint plate.
 *
 * Mirrors the in-view backdrop blur used by [ElasticBottomNavigationView]
 * (software-bitmap capture, NOT a hardware RenderNode recording canvas —
 * recording a hardware-accelerated view tree into a RecordingCanvas
 * crashes with "Recording currently in progress"), extracted here as a
 * standalone, reusable view.
 */
internal class BackdropBlurView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private var blurRoot: ViewGroup? = null
        private var blurRadius = dp(DEFAULT_BLUR_RADIUS_DP)
        private var cornerRadius = 0f
        private val tintPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.TRANSPARENT }

        private var backdropNode: RenderNode? = null
        private var captureBitmap: Bitmap? = null
        private var captureCanvas: Canvas? = null
        private val clipPath = Path()
        private val locThis = IntArray(2)
        private val locRoot = IntArray(2)

        /** True while being recorded into the backdrop — skip self-draw. */
        private var capturing = false

        private fun dp(value: Float): Float = value * resources.displayMetrics.density

        /**
         * Wire this plate to the hierarchy whose pixels show through it.
         * Re-captured and blurred each frame, clipped to [cornerRadiusDp].
         * [tint] is composited over the blur as a translucent frosted
         * sheet; on API < 31 it is the whole effect (no blur).
         */
        fun attachBackdropBlur(
            root: ViewGroup,
            blurRadiusDp: Float = DEFAULT_BLUR_RADIUS_DP,
            cornerRadiusDp: Float = 0f,
            tint: Int,
        ) {
            blurRoot = root
            blurRadius = dp(blurRadiusDp)
            cornerRadius = dp(cornerRadiusDp)
            tintPaint.color = tint
            viewTreeObserver.addOnPreDrawListener {
                if (blurRoot != null && !capturing) invalidate()
                true
            }
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            // As a layout_height="match_parent" child of a wrap_content
            // FrameLayout, a plain View would resolve AT_MOST to the full
            // available height and inflate the parent (collapsing siblings).
            // Claim 0 height unless measured EXACTLY; the FrameLayout then
            // sizes to its other child (the button) and re-measures us
            // EXACTLY to that height, so we end up exactly button-sized.
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            } else {
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0)
            }
        }

        override fun draw(canvas: Canvas) {
            // While being captured into a backdrop, draw nothing so this
            // plate doesn't blur itself recursively.
            if (capturing) return
            super.draw(canvas)
        }

        override fun onDraw(canvas: Canvas) {
            val radius = cornerRadius.coerceAtMost(minOf(width, height) / 2f)
            clipPath.reset()
            clipPath.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipPath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                drawBackdrop(canvas)
            }
            // Frosted tint sheet over the blur (or the whole effect pre-31).
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tintPaint)
            canvas.restore()
        }

        @RequiresApi(Build.VERSION_CODES.S)
        @Suppress("ReturnCount")
        private fun drawBackdrop(canvas: Canvas) {
            val root = blurRoot ?: return
            if (width == 0 || height == 0 || !canvas.isHardwareAccelerated) return

            val bitmap = ensureCaptureBitmap() ?: return
            bitmap.eraseColor(0)
            val capture = captureCanvas ?: return
            capture.save()
            try {
                getLocationInWindow(locThis)
                root.getLocationInWindow(locRoot)
                capture.translate((locRoot[0] - locThis[0]).toFloat(), (locRoot[1] - locThis[1]).toFloat())
                capturing = true
                root.draw(capture)
            } finally {
                capturing = false
                capture.restore()
            }

            val node = backdropNode ?: RenderNode("backdropBlur").also { backdropNode = it }
            node.setPosition(0, 0, width, height)
            node.setRenderEffect(RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP))
            val recording = node.beginRecording()
            try {
                recording.drawBitmap(bitmap, 0f, 0f, null)
            } finally {
                node.endRecording()
            }
            canvas.drawRenderNode(node)
        }

        private fun ensureCaptureBitmap(): Bitmap? {
            val existing = captureBitmap
            if (existing != null && existing.width == width && existing.height == height) return existing
            existing?.recycle()
            val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            captureBitmap = created
            captureCanvas = Canvas(created)
            return created
        }

        private companion object {
            private const val DEFAULT_BLUR_RADIUS_DP = 16f
        }
    }
