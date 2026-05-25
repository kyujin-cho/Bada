/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.bottomnavigation.BottomNavigationView
import dev.bluehouse.bada.R
import kotlin.math.abs

/**
 * BottomNavigationView that draws its selection highlight as a capsule
 * which **elastically follows the finger** when the user drags across the
 * tabs, and settles (with a spring) onto whichever tab the finger lands
 * on — or springs back to the original tab if the drag returns. Used by
 * the landscape floating nav pill ([R.layout-land/activity_main]); the
 * static checked-state `itemBackground` is left transparent there so this
 * moving capsule is the only selection affordance.
 *
 * The capsule is drawn in [dispatchDraw] (after the bar background, before
 * the icons/labels) at a spring-animated centre-x and half-width so it
 * morphs between tabs of different widths. Plain taps fall through to the
 * normal item-click selection; [dispatchDraw] notices the resulting
 * selection change and animates the capsule to it. A horizontal drag past
 * the touch slop is intercepted and drives the capsule directly.
 */
internal class ElasticBottomNavigationView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : BottomNavigationView(context, attrs) {
        private val pillPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.nav_active_indicator)
            }
        private val insetVertical = dp(PILL_INSET_VERTICAL_DP)
        private val insetHorizontal = dp(PILL_INSET_HORIZONTAL_DP)
        private val pillRect = RectF()
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        private val centreX = FloatValueHolder()
        private val halfWidth = FloatValueHolder()
        private val centreSpring =
            SpringAnimation(centreX).apply {
                spring =
                    SpringForce()
                        .setStiffness(SpringForce.STIFFNESS_LOW)
                        .setDampingRatio(CENTRE_DAMPING)
                addUpdateListener { _, _, _ -> invalidate() }
            }
        private val widthSpring =
            SpringAnimation(halfWidth).apply {
                spring =
                    SpringForce()
                        .setStiffness(SpringForce.STIFFNESS_LOW)
                        .setDampingRatio(WIDTH_DAMPING)
                addUpdateListener { _, _, _ -> invalidate() }
            }

        private var pillInitialized = false
        private var syncedItemId = 0
        private var dragging = false
        private var downX = 0f

        // Backdrop ("liquid glass") blur: capture the content behind the
        // floating pill and blur it. Only wired on API 31+, where a
        // RenderNode + RenderEffect can blur a recorded hierarchy without a
        // third-party library; below 31 we fall back to the translucent
        // pill surface alone. See [attachBackdropBlur].
        private var blurRoot: ViewGroup? = null
        private var backdropNode: RenderNode? = null
        private var captureBitmap: Bitmap? = null
        private var captureCanvas: Canvas? = null
        private val backdropClip = Path()
        private val locThis = IntArray(2)
        private val locRoot = IntArray(2)

        /** True while the pill is being recorded into its own backdrop — skip self-draw. */
        private var capturing = false

        private fun dp(value: Float): Float = value * resources.displayMetrics.density

        /**
         * Blur the content behind the floating nav pill so it reads as a
         * frosted "liquid glass" bar. [root] is the view hierarchy whose
         * pixels show through the pill (e.g. the activity content frame);
         * it is re-captured and blurred each frame, clipped to the pill's
         * rounded outline. No-op below API 31, where the translucent pill
         * surface stands in for the blur.
         */
        fun attachBackdropBlur(root: ViewGroup) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            blurRoot = root
            viewTreeObserver.addOnPreDrawListener {
                if (blurRoot != null && !capturing) invalidate()
                true
            }
        }

        private fun menuView(): ViewGroup? = getChildAt(0) as? ViewGroup

        private data class Slot(
            val id: Int,
            val centre: Float,
            val width: Float,
        )

        private fun slots(): List<Slot> {
            val menu = menuView() ?: return emptyList()
            val result = ArrayList<Slot>(menu.childCount)
            for (i in 0 until menu.childCount) {
                val child = menu.getChildAt(i)
                if (child.id == View.NO_ID) continue
                val left = (menu.left + child.left).toFloat()
                result.add(Slot(child.id, left + child.width / 2f, child.width.toFloat()))
            }
            return result
        }

        /**
         * Squeeze the bar to [NAV_WIDTH_SCALE] of its natural width.
         * BottomNavigationView exposes no public attribute to set the tab
         * cell width, so we measure normally (wrap_content → the natural
         * sum of cell widths) and then re-measure the bar at a fixed
         * fraction of that. Material's menu view redistributes the items
         * evenly into the narrower EXACTLY width and keeps each tab's
         * icon+label centred, so the pill just gets tighter without
         * clipping. No-op when the width is already constrained.
         */
        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) return
            val target = (measuredWidth * NAV_WIDTH_SCALE).toInt()
            if (target <= 0) return
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(target, MeasureSpec.EXACTLY),
                heightMeasureSpec,
            )
        }

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            // Re-anchor the capsule to the selected tab after a relayout.
            pillInitialized = false
        }

        override fun onLayout(
            changed: Boolean,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
        ) {
            super.onLayout(changed, left, top, right, bottom)
            tightenLabelGaps()
        }

        /**
         * Tighten each tab's icon-to-label gap and keep the icon+label
         * block vertically centred in the capsule. Material's
         * NavigationBarItemView leaves a generous gap with no public
         * attribute to shrink it, so we pull the label up; pulling only the
         * label up would leave the block top-heavy, so we also nudge both
         * the icon and the label down by half the reduction to re-centre.
         * Net: gap shrinks by [LABEL_GAP_REDUCTION_DP], block stays centred.
         * translationY survives relayout and re-applies on every [onLayout].
         */
        private fun tightenLabelGaps() {
            val menu = menuView() ?: return
            val down = dp(LABEL_GAP_REDUCTION_DP) / 2f
            val labelShift = -dp(LABEL_GAP_REDUCTION_DP) + down
            for (i in 0 until menu.childCount) {
                val item = menu.getChildAt(i) as? ViewGroup ?: continue
                labelGroup(item)?.translationY = labelShift
                iconView(item)?.translationY = down
            }
        }

        /** The label container inside a tab (the child group holding the text labels). */
        private fun labelGroup(item: ViewGroup): View? {
            for (j in 0 until item.childCount) {
                val child = item.getChildAt(j)
                if (child is ViewGroup && containsText(child)) return child
            }
            return null
        }

        /** The icon view inside a tab. */
        private fun iconView(item: ViewGroup): View? {
            for (j in 0 until item.childCount) {
                val child = item.getChildAt(j)
                if (child is ImageView) return child
            }
            return null
        }

        private fun containsText(group: ViewGroup): Boolean {
            for (j in 0 until group.childCount) {
                if (group.getChildAt(j) is TextView) return true
            }
            return false
        }

        override fun draw(canvas: Canvas) {
            // While being captured into our own backdrop, draw nothing so
            // the pill (and its icons) don't blur into their own backdrop.
            if (capturing) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) drawBackdrop(canvas)
            super.draw(canvas)
        }

        @RequiresApi(Build.VERSION_CODES.S)
        @Suppress("ReturnCount")
        private fun drawBackdrop(canvas: Canvas) {
            val root = blurRoot ?: return
            if (width == 0 || height == 0 || !canvas.isHardwareAccelerated) return

            // Capture the content behind the pill into a SOFTWARE bitmap.
            // We must not draw the view tree into a hardware RenderNode
            // RecordingCanvas: hardware-accelerated children would try to
            // begin their own display-list recording mid-recording and
            // crash ("Recording currently in progress"). A software canvas
            // takes the direct onDraw path with no nested render nodes.
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

            // Blur the captured bitmap through a RenderEffect node and paint
            // it clipped to the pill's rounded outline.
            val node = backdropNode ?: RenderNode("navBackdropBlur").also { backdropNode = it }
            node.setPosition(0, 0, width, height)
            node.setRenderEffect(
                RenderEffect.createBlurEffect(
                    dp(BACKDROP_BLUR_RADIUS_DP),
                    dp(BACKDROP_BLUR_RADIUS_DP),
                    Shader.TileMode.CLAMP,
                ),
            )
            val recording = node.beginRecording()
            try {
                recording.drawBitmap(bitmap, 0f, 0f, null)
            } finally {
                node.endRecording()
            }
            val radius = (height / 2f).coerceAtMost(dp(PILL_CORNER_DP))
            backdropClip.reset()
            backdropClip.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(backdropClip)
            canvas.drawRenderNode(node)
            canvas.restore()
        }

        /** Lazily (re)allocate the offscreen capture bitmap to match the pill size. */
        private fun ensureCaptureBitmap(): Bitmap? {
            val existing = captureBitmap
            if (existing != null && existing.width == width && existing.height == height) return existing
            existing?.recycle()
            val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            captureBitmap = created
            captureCanvas = Canvas(created)
            return created
        }

        override fun dispatchDraw(canvas: Canvas) {
            val menu = menuView()
            if (menu != null && menu.width > 0) {
                syncCapsuleTarget()
                if (pillInitialized) {
                    val hw = (halfWidth.value - insetHorizontal).coerceAtLeast(0f)
                    val top = menu.top + insetVertical
                    val bottom = menu.bottom - insetVertical
                    val radius = (bottom - top) / 2f
                    pillRect.set(centreX.value - hw, top, centreX.value + hw, bottom)
                    canvas.drawRoundRect(pillRect, radius, radius, pillPaint)
                }
            }
            super.dispatchDraw(canvas)
        }

        /**
         * Keep the capsule pointed at the selected tab when not being
         * dragged: snap to it on first layout, spring to it when the
         * selection changes via a tap or programmatically.
         */
        private fun syncCapsuleTarget() {
            val current = slots().firstOrNull { it.id == selectedItemId } ?: slots().firstOrNull() ?: return
            if (!pillInitialized) {
                centreX.value = current.centre
                halfWidth.value = current.width / 2f
                syncedItemId = selectedItemId
                pillInitialized = true
            } else if (!dragging && selectedItemId != syncedItemId) {
                syncedItemId = selectedItemId
                centreSpring.animateToFinalPosition(current.centre)
                widthSpring.animateToFinalPosition(current.width / 2f)
            }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    dragging = false
                }
                MotionEvent.ACTION_MOVE ->
                    if (!dragging && abs(ev.x - downX) > touchSlop) {
                        dragging = true
                        return true
                    }
            }
            return super.onInterceptTouchEvent(ev)
        }

        @Suppress("ReturnCount")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && abs(event.x - downX) > touchSlop) dragging = true
                    if (dragging) {
                        followFinger(event.x)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (dragging) {
                        dragging = false
                        settleTo(event.x)
                        return true
                    }
            }
            return super.onTouchEvent(event)
        }

        /** Drag in progress: spring the capsule toward the finger (clamped). */
        private fun followFinger(x: Float) {
            val slots = slots()
            if (slots.isEmpty()) return
            val clamped = x.coerceIn(slots.first().centre, slots.last().centre)
            centreSpring.animateToFinalPosition(clamped)
            slots.minByOrNull { abs(it.centre - x) }?.let { widthSpring.animateToFinalPosition(it.width / 2f) }
        }

        /** Drag released: pick the nearest tab, select it, settle the capsule. */
        private fun settleTo(x: Float) {
            val target = slots().minByOrNull { abs(it.centre - x) } ?: return
            if (target.id != selectedItemId) {
                // Drives MainActivity's OnItemSelectedListener (fragment swap);
                // syncCapsuleTarget then springs the capsule onto the new tab.
                selectedItemId = target.id
            } else {
                // Returned to the original tab — spring the capsule back.
                centreSpring.animateToFinalPosition(target.centre)
                widthSpring.animateToFinalPosition(target.width / 2f)
            }
        }

        private companion object {
            private const val PILL_INSET_VERTICAL_DP = 6f
            private const val PILL_INSET_HORIZONTAL_DP = 4f
            private const val CENTRE_DAMPING = 0.72f
            private const val WIDTH_DAMPING = 0.85f
            private const val LABEL_GAP_REDUCTION_DP = 8f
            private const val NAV_WIDTH_SCALE = 0.8f
            private const val BACKDROP_BLUR_RADIUS_DP = 16f
            private const val PILL_CORNER_DP = 32f
        }
    }
