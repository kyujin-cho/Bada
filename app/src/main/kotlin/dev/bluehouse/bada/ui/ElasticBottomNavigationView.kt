/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
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

        private fun dp(value: Float): Float = value * resources.displayMetrics.density

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
        }
    }
