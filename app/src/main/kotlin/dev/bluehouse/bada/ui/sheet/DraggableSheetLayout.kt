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
import android.os.Build
import android.provider.Settings
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import androidx.core.view.doOnLayout
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog

/**
 * A bottom-sheet card container.
 * A bottom-anchored card that:
 *
 *  - slides up from below the bottom edge on entrance (a VIEW-level slide in
 *    [playEntrance], pre-hidden via [prepareOffscreen] and triggered by the host
 *    AFTER the window is shown), then its TOP edge elastically stretches up and
 *    settles with the bottom planted (see [playTopElasticStretch]); the elements
 *    ride up a little but do NOT stretch — only the rounded background does,
 *  - is draggable downward and dismisses on a sufficient swipe-down,
 *  - snaps back with a bounce otherwise.
 *
 * Child buttons still receive taps — the drag only engages once the
 * pointer travels past the touch slop downward.
 *
 * Programmatic custom view: drag thresholds, fling velocities, and animation
 * constants are inherently numeric; the gesture dispatch in onTouchEvent uses one
 * early return per pointer action. MagicNumber / ReturnCount are suppressed.
 */
@Suppress("MagicNumber", "ReturnCount")
public class DraggableSheetLayout
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : LinearLayout(context, attrs, defStyleAttr) {
        private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
        private var downRawY: Float = 0f
        private var startTransY: Float = 0f
        private var dragging: Boolean = false
        private var onDismiss: (() -> Unit)? = null

        /**
         * Optional content wrapper (every visible element — the device-name pill + the
         * state frame) that is counter-scaled by the inverse of the sheet's
         * entrance-bounce stretch, about its OWN TOP, so the elements keep their exact
         * size (do NOT stretch) and ride UP with the stretching top edge (so the device
         * pill stays glued to the top). Null (default) = the whole sheet (background +
         * content) stretches together. Set via [setBounceContent]. Views in
         * [bounceBottomAnchors] are then re-planted so the bottom action row doesn't
         * ride up with it.
         */
        private var bounceContent: View? = null

        /**
         * Optional views — the bottom action row (the "can't find the device?" link and
         * the Cancel/Done button) — that should STAY PLANTED during the entrance bounce
         * even though [bounceContent] rides up. Each is translated DOWN by the same
         * `rise` the content rides UP, cancelling it (the net ancestor scaleY on their
         * translationY is 1: sheet ×k composed with content ×1/k), so the stretch opens
         * in the gap ABOVE them instead of carrying them up. They still slide normally —
         * translationY is reset to 0 outside the bounce. Set via [setBounceBottomAnchors].
         */
        private var bounceBottomAnchors: List<View> = emptyList()

        init {
            orientation = VERTICAL
            isClickable = true
        }

        public fun setOnDismiss(r: (() -> Unit)?) {
            this.onDismiss = r
        }

        /**
         * Provide the content wrapper to counter-scale during the entrance bounce so
         * only the sheet's rounded background stretches — the elements keep their size
         * and ride up with the top edge (see [bounceContent]). Pass null to stretch the
         * whole sheet (background + content).
         */
        public fun setBounceContent(view: View?) {
            this.bounceContent = view
        }

        /**
         * Provide the bottom action-row views to keep PLANTED during the entrance bounce
         * (see [bounceBottomAnchors]): they ride with the slide-up but the bounce's
         * top-edge stretch does not carry them — the gap opens above them instead.
         */
        public fun setBounceBottomAnchors(vararg views: View) {
            this.bounceBottomAnchors = views.toList()
        }

        /**
         * Pre-hide the sheet fully BELOW the screen, immediately, BEFORE the first
         * frame is drawn — so it is invisible until [playEntrance] slides it up (no
         * flash of the sheet at its resting position). The host calls this during view
         * setup (SendActivity.wireBottomSheet) BEFORE the window becomes visible. A
         * full screen-height translate guarantees off-screen without needing the
         * not-yet-measured sheet height; [playEntrance] then refines to a precise
         * just-below-the-edge start. ONLY the send sheet pre-hides; a caller that does
         * NOT call this (e.g. the receive sheet) leaves the sheet at rest and
         * [playEntrance] skips the slide (bounce only).
         */
        public fun prepareOffscreen() {
            translationY = resources.displayMetrics.heightPixels.toFloat()
            scaleY = 1f
        }

        /**
         * Entrance: a VIEW-level slide-up from just below the bottom edge, then the
         * top-edge bounce ([playTopElasticStretch]).
         *
         * CRITICAL TIMING: the host MUST call this AFTER the activity window has
         * finished its OPEN transition — SendActivity calls it from
         * `onEnterAnimationComplete()`. Triggering the slide earlier (in onCreate /
         * the first `doOnLayout`, while the window is still running its own open
         * animation) is what made the slide invisible on OnePlus: the view slid while
         * the window was still composing, so by the time the window was on screen the
         * sheet had already arrived — it "appeared already settled" with only a fade.
         * Run in the already-visible window, the slide is plainly visible. (The slide
         * is a [android.view.ViewPropertyAnimator]; the device's animators are ON —
         * the bounce visibly animates — so duration is not collapsed.)
         *
         * If the sheet was not pre-hidden ([prepareOffscreen] not called — e.g. the
         * receive sheet), `translationY` is 0, the slide is skipped, and only the
         * bounce runs (preserving that caller's prior behavior).
         */
        public fun playEntrance(onComplete: (() -> Unit)? = null) {
            doOnLayout {
                scaleY = 1f
                val marginBottom = (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
                val doSlide = translationY != 0f // pre-hidden by prepareOffscreen()
                // OBSERVABILITY: records whether the slide ran, the geometry, and the
                // device animation scale, so an on-device bug report explains a missing
                // slide. Routed through DiagnosticLog.e so it survives OEM Log filtering.
                DiagnosticLog.e(
                    DIAG_TAG,
                    "playEntrance RUN: doSlide=$doSlide height=$height curTransY=$translationY " +
                        "animDurScale=${currentAnimatorDurationScale()} animatorsEnabled=${animatorsEnabled()}",
                )
                if (doSlide) {
                    // SEAMLESS expand+bounce as ONE continuous motion — no "expand finishes,
                    // THEN the bounce starts" beat. scaleY follows a single OVERSHOOT curve
                    // from ENTRANCE_START_SCALE: it grows toward full size and, carrying its
                    // momentum, sails slightly PAST full, then settles. The overshoot past
                    // 1.0 IS the bounce; because the curve passes THROUGH full size without
                    // stopping (continuous velocity), the expand and the bounce blend into
                    // one motion instead of running back-to-back. The card stays FULL WIDTH the
                    // whole way (scaleX fixed at 1); only the HEIGHT scales (scaleY), so it
                    // expands and overshoots VERTICALLY only; the slide finishes as the card reaches full
                    // size; content is counter-scaled and the bottom action row planted ONLY
                    // while past full size (so the pill rides the stretching top edge and the
                    // bottom stays put). Tune the bounce magnitude with ENTRANCE_OVERSHOOT_TENSION.
                    val startTransY = (height + paddingBottom + marginBottom + ENTRANCE_OFFSET_PX).toFloat()
                    val content = bounceContent
                    val anchors = bounceBottomAnchors
                    val h = height.toFloat()
                    val overshoot = OvershootInterpolator(ENTRANCE_OVERSHOOT_TENSION)
                    pivotX = width / 2f
                    pivotY = h
                    // Pre-set the start state (off-screen + half size) so the first drawn
                    // frame is already correct; it's off-screen so invisible regardless.
                    translationY = startTransY
                    scaleX = 1f // full width the whole way; only the HEIGHT expands
                    scaleY = ENTRANCE_START_SCALE
                    DiagnosticLog.e(DIAG_TAG, "entrance(overshoot) RUN: height=$height")
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = ENTRANCE_SLIDE_MS + STRETCH_DURATION_MS
                        interpolator = LinearInterpolator() // the overshoot curve is applied below
                        addUpdateListener { va ->
                            // o: 0 -> rises -> overshoots above 1 -> settles to 1 (one motion).
                            val o = overshoot.getInterpolation(va.animatedFraction)
                            val sy = ENTRANCE_START_SCALE + (1f - ENTRANCE_START_SCALE) * o
                            scaleY = sy // height only; scaleX stays 1f (full width) all the way
                            // The slide finishes exactly as the card first reaches full size.
                            val slideProg =
                                ((sy - ENTRANCE_START_SCALE) / (1f - ENTRANCE_START_SCALE)).coerceIn(0f, 1f)
                            translationY = startTransY * (1f - slideProg)
                            if (sy > 1f) {
                                // Past full size = the bounce: keep elements their size (pill
                                // rides the stretching top edge), plant the bottom action row.
                                content?.let {
                                    it.pivotY = 0f
                                    it.scaleY = 1f / sy
                                }
                                anchors.forEach { it.translationY = (sy - 1f) * h }
                            } else {
                                content?.scaleY = 1f
                                anchors.forEach { it.translationY = 0f }
                            }
                        }
                        addListener(
                            object : AnimatorListenerAdapter() {
                                // Fires on natural end AND cancel, so the reset + onComplete
                                // (revealing the peer icons) can never be skipped.
                                override fun onAnimationEnd(animation: Animator) {
                                    scaleX = 1f
                                    scaleY = 1f
                                    translationY = 0f
                                    content?.scaleY = 1f
                                    anchors.forEach { it.translationY = 0f }
                                    onComplete?.invoke()
                                }
                            },
                        )
                        start()
                    }
                } else {
                    // Not pre-hidden (receive sheet): no slide, just the bounce.
                    postDelayed({ playTopElasticStretch(onComplete) }, WINDOW_SETTLE_MS)
                }
            }
        }

        /**
         * The device-global `animator_duration_scale` (1.0 = normal, 0 = animations
         * off). Read from [Settings.Global]; defaults to 1 if unreadable. A value of
         * 0 means [android.view.ViewPropertyAnimator] / [ValueAnimator] durations are
         * zeroed by the platform — so the entrance bounce (a [ValueAnimator]) would
         * collapse to an instant snap. Logged by [playEntrance] for on-device
         * diagnostics; the slide itself is the window animation and is immune.
         */
        private fun currentAnimatorDurationScale(): Float =
            runCatching {
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f,
                )
            }.getOrDefault(1f)

        /**
         * Whether the platform currently runs animators at all. On API 26+ this is the
         * authoritative [ValueAnimator.areAnimatorsEnabled] (false when the global
         * scale is 0 OR battery-saver/reduced-motion has disabled animations); below
         * 26 we fall back to the [currentAnimatorDurationScale] check. Logged by
         * [playEntrance] so a missing bounce is explainable from a bug report.
         */
        private fun animatorsEnabled(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ValueAnimator.areAnimatorsEnabled()
            } else {
                currentAnimatorDurationScale() > 0f
            }

        /**
         * Entrance stage 2 — the bounce. Visual model: the sheet's BOTTOM stays
         * planted; the rounded card BACKGROUND stretches up at the top by
         * [ENTRANCE_TOP_EXTEND_DP] px and snaps back ([topStretchProfile] — one smooth
         * hump, no wobble). The whole sheet scales about its bottom (so the rounded
         * background stretches), and [bounceContent] (every element — the device pill +
         * the state frame) is counter-scaled by the inverse about its OWN TOP so the
         * elements:
         *   - do NOT stretch (the inverse scale cancels the parent stretch), and
         *   - ride UP WITH the stretching top edge (the TOP pivot makes the whole
         *     content rigidly follow the top, so the device-name PILL stays GLUED to
         *     the card's top edge as it extends — no gap opens above it).
         * Net: the pill + upper content track the top edge while [bounceBottomAnchors]
         * (the bottom action row) stay planted, so the stretch opens in the gap BETWEEN
         * them and nothing distorts. With no [bounceContent] set the whole sheet
         * (background + content) stretches together. Resets transforms on end.
         */
        private fun playTopElasticStretch(onComplete: (() -> Unit)? = null) {
            val h = height
            DiagnosticLog.e(DIAG_TAG, "playTopElasticStretch RUN: height=$h")
            if (h <= 0) { // not laid out / zero height — nothing to scale about
                onComplete?.invoke()
                return
            }
            pivotY = h.toFloat() // bottom edge = anchor; the TOP is free to stretch up
            val content = bounceContent
            val extendPx = ENTRANCE_TOP_EXTEND_DP * resources.displayMetrics.density
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = STRETCH_DURATION_MS
                interpolator = LinearInterpolator() // the hump shape drives the motion
                addUpdateListener { a ->
                    val rise = extendPx * topStretchProfile(a.animatedFraction) // px the top extends
                    val k = 1f + rise / h // scaleY that lifts the top edge by exactly `rise`
                    scaleY = k
                    content?.let {
                        // Counter-scale about the content's OWN TOP: the inverse scale
                        // cancels the parent stretch (elements keep their exact size)
                        // while the TOP pivot makes the whole content rigidly RIDE UP
                        // with the top edge — so the device-name pill stays GLUED to the
                        // stretching top edge (no gap opens above it).
                        it.pivotY = 0f
                        it.scaleY = 1f / k
                    }
                    // Re-plant the bottom action row: translate it DOWN by the same
                    // `rise` the content rode UP (the net ancestor scaleY on its
                    // translationY is 1), so it stays put while the gap ABOVE it
                    // stretches — the bounce doesn't carry the buttons up.
                    bounceBottomAnchors.forEach { it.translationY = rise }
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        // Fires on natural end AND on cancel, so [onComplete] (e.g.
                        // revealing delayed peer icons) can never be skipped.
                        override fun onAnimationEnd(animation: Animator) {
                            scaleY = 1f
                            content?.scaleY = 1f
                            bounceBottomAnchors.forEach { it.translationY = 0f }
                            onComplete?.invoke()
                        }
                    },
                )
                start()
            }
        }

        /**
         * Single-hump stretch profile used by [playTopElasticStretch], 0 at t=0, peak
         * of 1 at t=[TOP_STRETCH_PEAK_FRACTION], back to 0 at t=1. It is ASYMMETRIC: a
         * quick extend up to the peak then a slower, eased RECOIL back to rest — a
         * "stretch and elastically settle" feel rather than the symmetric (mechanical)
         * raised-cosine pulse it replaced. Each side is its own raised-cosine, so there
         * is ZERO velocity at t=0, at the peak, and at t=1, and the value never dips
         * below rest (no wobble). Tune the snappiness with [TOP_STRETCH_PEAK_FRACTION]
         * (smaller = quicker extend / longer elastic settle).
         */
        private fun topStretchProfile(t: Float): Float {
            val peak = TOP_STRETCH_PEAK_FRACTION.toDouble()
            val d = t.toDouble().coerceIn(0.0, 1.0)
            return if (d <= peak) {
                // quick extend up to the peak
                (0.5 * (1.0 - Math.cos(Math.PI * (d / peak)))).toFloat()
            } else {
                // slower, eased recoil back to rest
                (0.5 * (1.0 + Math.cos(Math.PI * ((d - peak) / (1.0 - peak))))).toFloat()
            }
        }

        /**
         * Call AFTER adding content that makes the sheet taller (e.g. the
         * first device icon). Animates the height increase as a smooth
         * slide-up with a slight overscroll settle (translationY overshoot —
         * up then settle), instead of an instant pop. Not a scale/bounce of
         * the whole card.
         */
        public fun animateGrow() {
            val before = height
            post {
                val delta = height - before
                if (delta <= 0) return@post // didn't grow
                translationY = delta.toFloat() // start at the old visual position
                animate()
                    .translationY(0f)
                    .setDuration(GROW_DURATION_MS)
                    .setInterpolator(OvershootInterpolator(GROW_TENSION))
                    .start()
            }
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = e.rawY
                    startTransY = translationY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    // Only claim the downward drag for pull-to-dismiss when no
                    // scrollable descendant under the pointer can still scroll
                    // up. The device-picker grid is a vertical NestedScrollView;
                    // without this guard the sheet would hijack every downward
                    // swipe and dismiss instead of letting the list scroll back
                    // toward its top. Once the list is at the top (can no longer
                    // scroll up), the downward drag resumes its dismiss role.
                    if (e.rawY - downRawY > touchSlop && !canScrollableChildScrollUp(e.rawX, e.rawY)) {
                        dragging = true
                        return true
                    }
                }
            }
            return false
        }

        /**
         * True when a visible scrollable descendant whose on-screen bounds
         * contain the pointer can still scroll up (its content is not at the
         * top). Used by [onInterceptTouchEvent] to yield the downward drag to
         * an inner vertical scroll list before the sheet's pull-to-dismiss.
         */
        private fun canScrollableChildScrollUp(
            rawX: Float,
            rawY: Float,
        ): Boolean = scrollableChildUnder(this, rawX, rawY)?.canScrollVertically(-1) == true

        private fun scrollableChildUnder(
            parent: ViewGroup,
            rawX: Float,
            rawY: Float,
        ): View? =
            (parent.childCount - 1 downTo 0)
                .asSequence()
                .map { parent.getChildAt(it) }
                .filter { it.visibility == View.VISIBLE && containsPoint(it, rawX, rawY) }
                .firstNotNullOfOrNull { child ->
                    when {
                        child.canScrollVertically(-1) -> child
                        child is ViewGroup -> scrollableChildUnder(child, rawX, rawY)
                        else -> null
                    }
                }

        private fun containsPoint(
            view: View,
            rawX: Float,
            rawY: Float,
        ): Boolean {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            return rawX >= location[0] &&
                rawX <= location[0] + view.width &&
                rawY >= location[1] &&
                rawY <= location[1] + view.height
        }

        @Suppress("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = e.rawY
                    startTransY = translationY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downRawY
                    if (dy > 0) translationY = startTransY + dy
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (translationY > height * DISMISS_FRACTION) {
                        dismiss()
                    } else {
                        animate()
                            .translationY(0f)
                            .setDuration(SNAP_DURATION_MS)
                            .setInterpolator(OvershootInterpolator(SNAP_TENSION))
                            .start()
                    }
                    return true
                }
            }
            return super.onTouchEvent(e)
        }

        /**
         * Dismiss the sheet. The slide-DOWN exit is the activity WINDOW close
         * animation ([R.style.WindowAnimation_Bada_SendSheet] `slide_down_out`,
         * the reverse of the `slide_up_in` entrance), played automatically when the
         * host [finish]es from [onDismiss]. This view does NOT also translate — a
         * second, competing view slide would double the travel — it just notifies the
         * host to finish so the window close animation is the only exit motion.
         */
        public fun dismiss() {
            onDismiss?.invoke()
        }

        public companion object {
            /** DiagnosticLog tag for the entrance observability lines (#15 OnePlus
             *  slide gap). grep `SendSheetEntrance` in a bug report to see that the
             *  entrance ran, its geometry, and the device animation scale. */
            private const val DIAG_TAG = "SendSheetEntrance"

            // Stage 1 — the VIEW-level slide-up (playEntrance, when pre-hidden via
            // prepareOffscreen). Starts ENTRANCE_OFFSET_PX below the bottom edge and
            // decelerates into rest over ENTRANCE_SLIDE_MS. Triggered by the host AFTER
            // the window open transition (SendActivity.onEnterAnimationComplete) so it
            // is not masked by the window's own animation.
            private const val ENTRANCE_OFFSET_PX = 80

            // A little quicker than the old 240ms (which was itself snappier than 300ms),
            // per the "make the slide a little quicker" request. Shared by the send sheet
            // and the consent sheet (both call playEntrance); 30ms is modest enough that
            // the consent entrance is effectively unchanged.
            private const val ENTRANCE_SLIDE_MS = 210L

            // Overshoot tension for the entrance: how far the card sails PAST full size
            // before settling (that overshoot IS the bounce). The card scales
            // ENTRANCE_START_SCALE -> 1.0 via OvershootInterpolator(this) as ONE motion;
            // higher = bigger overshoot/bounce. ~1.0 gives a small ~2-3% overshoot (a
            // subtle vertical top-edge pop). Tunable.
            private const val ENTRANCE_OVERSHOOT_TENSION = 1.0f

            // The card starts at this fraction of its HEIGHT and grows to full height
            // during the entrance (scaleY only — it stays FULL WIDTH the whole way),
            // anchored at the bottom so it "comes up" out of the bottom as it expands.
            // 0.5 = starts at half height; 1.0 = no grow (pure slide). Tunable.
            private const val ENTRANCE_START_SCALE = 0.5f

            // Fallback delay before the bounce on the NO-slide path (receive sheet,
            // which never calls prepareOffscreen): a brief settle so the bounce doesn't
            // fire the instant the window appears.
            private const val WINDOW_SETTLE_MS = 260L

            // Stage 2 — bottom-anchored stretch of the card's rounded BACKGROUND: the
            // top edge extends up by ENTRANCE_TOP_EXTEND_DP and elastically settles back,
            // NO wobble (an ASYMMETRIC single hump — quick extend, slow eased recoil; see
            // topStretchProfile). The elements are counter-scaled to keep their size and
            // ride up. A fixed dp (not a % of the tall card) so the extend is small +
            // consistent. STRETCH_DURATION_MS = its length.
            private const val ENTRANCE_TOP_EXTEND_DP = 16f
            private const val STRETCH_DURATION_MS = 200L // shortened from 260 (snappier bounce)

            // Where the top-stretch hump PEAKS (fraction of STRETCH_DURATION_MS). < 0.5
            // makes it asymmetric: a quick extend up to the peak then a slower elastic
            // settle back — the "more elastic, less mechanical" feel. Tunable.
            private const val TOP_STRETCH_PEAK_FRACTION = 0.30f

            /** Total wall-time of the entrance (slide + bounce). Callers use it to time
             *  a follow-on reveal (e.g. delaying the peer icons until the entrance has
             *  finished). Measured from when playEntrance is triggered, NOT from
             *  onCreate (the host adds its own pre-entrance delay). */
            public const val ENTRANCE_TOTAL_MS: Long = ENTRANCE_SLIDE_MS + STRETCH_DURATION_MS

            private const val GROW_DURATION_MS = 420L
            private const val GROW_TENSION = 1.4f
            private const val SNAP_DURATION_MS = 220L
            private const val SNAP_TENSION = 1.0f
            private const val DISMISS_FRACTION = 0.28f

            /**
             * Edge-to-edge: pad the sheet's bottom by the navigation-bar
             * inset so its content sits above the nav buttons, keeping
             * [baseBottomPx] as the design padding.
             */
            @JvmStatic
            public fun applyBottomInset(
                root: View,
                sheet: DraggableSheetLayout,
                baseBottomPx: Int,
            ) {
                root.setOnApplyWindowInsetsListener { _, insets ->
                    val navBottom =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                        } else {
                            @Suppress("DEPRECATION")
                            insets.systemWindowInsetBottom
                        }
                    sheet.setPadding(
                        sheet.paddingLeft,
                        sheet.paddingTop,
                        sheet.paddingRight,
                        baseBottomPx + navBottom,
                    )
                    insets
                }
                root.requestApplyInsets()
            }
        }
    }
