/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui.theme

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.view.View
import androidx.annotation.AttrRes
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.motion.MotionUtils
import dev.bluehouse.bada.R
import com.google.android.material.R as MaterialR

/**
 * The M3 Expressive motion-physics system applied to Bada's own animations.
 *
 * On Android Views the spring tokens are "available, not added to
 * components" (m3.material.io/styles/motion/overview/specs), so the theme
 * provides `motionSpring{Fast,Default,Slow}{Spatial,Effects}` and the app has
 * to drive its custom animations with them. Per the guide: spatial springs
 * (position, scale, size, corners) overshoot and bounce into place; effects
 * springs (color, opacity) never overshoot; fast suits small components,
 * default suits partial-screen surfaces such as bottom sheets.
 *
 * Only Expressive code paths call this. The Bada theme keeps its tuned
 * duration-and-interpolator animations.
 */
internal object ExpressiveMotion {
    /** Small components: buttons, tab icons, toggles. */
    @AttrRes val FAST_SPATIAL: Int = MaterialR.attr.motionSpringFastSpatial

    /** Partial-screen surfaces: the send and consent sheets. */
    @AttrRes val DEFAULT_SPATIAL: Int = MaterialR.attr.motionSpringDefaultSpatial

    /** Opacity and color, without overshoot. */
    @AttrRes val DEFAULT_EFFECTS: Int = MaterialR.attr.motionSpringDefaultEffects

    /** The theme's spring for [token]; falls back to expressive default spatial values. */
    fun spring(
        context: Context,
        @AttrRes token: Int,
    ): SpringForce = MotionUtils.resolveThemeSpringForce(context, token, R.style.Motion_Bada_Spring_Fallback)

    /**
     * Drives a progress value from 0 to 1 with the spring [token]. A spatial
     * spring passes 1 and settles back, so callers that map progress to a
     * size or offset get the expressive bounce for free. [onEnd] runs on
     * natural end and on cancel.
     */
    fun animateProgress(
        context: Context,
        @AttrRes token: Int,
        onUpdate: (Float) -> Unit,
        onEnd: () -> Unit,
    ) {
        if (!animationsEnabled()) {
            // "Remove animations" / animator duration scale 0: jump to the end
            // state so callers that reveal content in onEnd still run.
            onUpdate(1f)
            onEnd()
            return
        }
        SpringAnimation(FloatValueHolder(0f)).apply {
            spring = spring(context, token).setFinalPosition(1f)
            setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
            addUpdateListener { _, value, _ -> onUpdate(value) }
            addEndListener { _, _, _, _ -> onEnd() }
            start()
        }
    }

    /**
     * False when the user turned animations off. M3 motion asks for reduced
     * motion to drop decorative effects such as shape morphing and bounces.
     */
    fun animationsEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

    /**
     * Press / pop feedback: jumps [view] to [fromScale] and springs it back
     * to its rest size. A second call cancels the first, so rapid taps always
     * restart from the rest-relative jump instead of stacking springs.
     */
    fun popScale(
        view: View,
        fromScale: Float,
        @AttrRes token: Int = FAST_SPATIAL,
    ) {
        if (!animationsEnabled()) return
        listOf(
            DynamicAnimation.SCALE_X to R.id.bada_spring_scale_x,
            DynamicAnimation.SCALE_Y to R.id.bada_spring_scale_y,
        ).forEach { (property, tagId) ->
            (view.getTag(tagId) as? SpringAnimation)?.cancel()
            property.setValue(view, fromScale)
            val animation =
                SpringAnimation(view, property).apply {
                    spring = spring(view.context, token).setFinalPosition(1f)
                }
            view.setTag(tagId, animation)
            animation.start()
        }
    }
}
