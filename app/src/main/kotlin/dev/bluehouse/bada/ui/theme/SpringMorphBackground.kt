/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui.theme

import android.animation.ArgbEvaluator
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.view.doOnLayout
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

/**
 * A view background that morphs between two states of one container: a
 * rounded rectangle (off) and a pill (on), each with its own fill color.
 * M3 Expressive "Morph shapes to connect function and feeling": the corner
 * radius runs on a spatial spring, so it overshoots a little and settles
 * (the springy, chewy part), while the colors run on an effects spring,
 * which never overshoots. Any text colors passed in follow the fill.
 *
 * Used by the Settings master switch row in the Expressive theme; the Bada
 * theme keeps its two static drawables.
 */
internal class SpringMorphBackground(
    private val view: View,
    private val offCornerPx: Float,
    @ColorInt private val offColor: Int,
    @ColorInt private val onColor: Int,
    @ColorInt rippleColor: Int,
    private val onColorChanged: (fraction: Float) -> Unit = {},
) {
    private val fill = MaterialShapeDrawable(ShapeAppearanceModel.builder().setAllCornerSizes(offCornerPx).build())
    private val evaluator = ArgbEvaluator()
    private val shapeProgress = FloatValueHolder(0f)
    private val colorProgress = FloatValueHolder(0f)
    private var shapeSpring: SpringAnimation? = null
    private var colorSpring: SpringAnimation? = null

    init {
        // Content-bounded ripple: with no mask layer, RippleDrawable clips to
        // the shape drawable, so the touch feedback follows the morph too.
        view.background = RippleDrawable(ColorStateList.valueOf(rippleColor), fill, null)
        view.doOnLayout { applyFrame() }
    }

    /** Jumps to [on] with no animation (first render). */
    fun snapTo(on: Boolean) {
        shapeSpring?.cancel()
        colorSpring?.cancel()
        val target = if (on) 1f else 0f
        shapeProgress.value = target
        colorProgress.value = target
        applyFrame()
    }

    /** Springs to [on]: corners on the fast spatial spring, colors on the default effects spring. */
    fun animateTo(on: Boolean) {
        if (!ExpressiveMotion.animationsEnabled()) {
            snapTo(on)
            return
        }
        val target = if (on) 1f else 0f
        shapeSpring?.cancel()
        colorSpring?.cancel()
        shapeSpring = spring(shapeProgress, target, ExpressiveMotion.FAST_SPATIAL).also { it.start() }
        colorSpring = spring(colorProgress, target, ExpressiveMotion.DEFAULT_EFFECTS).also { it.start() }
    }

    private fun spring(
        holder: FloatValueHolder,
        target: Float,
        token: Int,
    ): SpringAnimation =
        SpringAnimation(holder).apply {
            spring = ExpressiveMotion.spring(view.context, token).setFinalPosition(target)
            setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
            addUpdateListener { _, _, _ -> applyFrame() }
        }

    private fun applyFrame() {
        val pillCorner = view.height / 2f
        // Overshoot past either end is the bounce: a little past the pill is
        // clamped by the shape itself; a little below the off radius squares
        // the corners for a moment before they settle.
        val corner = (offCornerPx + (pillCorner - offCornerPx) * shapeProgress.value).coerceAtLeast(0f)
        fill.shapeAppearanceModel = fill.shapeAppearanceModel.withCornerSize(corner)
        val colorFraction = colorProgress.value.coerceIn(0f, 1f)
        fill.fillColor = ColorStateList.valueOf(evaluator.evaluate(colorFraction, offColor, onColor) as Int)
        onColorChanged(colorFraction)
    }
}
