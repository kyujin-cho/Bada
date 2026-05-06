/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.os.Bundle
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import dev.bluehouse.libredrop.MainActivity
import dev.bluehouse.libredrop.R

/**
 * Credit screen reachable from MainActivity's overflow menu.
 * Renders four contributor sections (Developer / Designer / QA /
 * Special Thanks to) and overlays a periodic diagonal blue-gradient
 * sweep on every nickname so each contributor's name flashes a
 * subtle highlight while the user reads the screen.
 *
 * The sweep is implemented by replacing the nickname TextView's
 * paint shader with a [LinearGradient] that runs from `(0,0)` to
 * `(textWidth, lineHeight)` — the diagonal direction the spec calls
 * for. The shader's color stops keep the natural text color along
 * most of the gradient and concentrate the brand-blue accent in a
 * narrow band in the middle. A [ValueAnimator] then translates the
 * shader's local matrix from `-1.5*width` to `+1.5*width`, making
 * the blue band pass over the text once per cycle. The cycles run
 * with a 3-second pause between sweeps so the highlight reads as
 * an occasional shimmer rather than a continuous distraction.
 *
 * Per-row sweeps are staggered by [STAGGER_DELAY_MS] so the page
 * does not flash all four names in unison; instead each contributor
 * lights up in turn. All animators are tracked in [activeAnimators]
 * so [onDestroy] can cancel them cleanly without leaking the
 * [TextView] references.
 */
internal class CreditActivity : AppCompatActivity() {
    private val activeAnimators = mutableListOf<ValueAnimator>()

    /**
     * One-shot latch for [finish] to make sure the MainActivity
     * relaunch only happens once even if the activity teardown path
     * is hit twice (e.g., rapid double back press, or system back +
     * coordinator-driven finish racing each other).
     */
    private var hasLaunchedMainActivity: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_credit)

        val toolbar = findViewById<MaterialToolbar>(R.id.credit_toolbar)
        // Toolbar back arrow + system back gesture + predictive back
        // all converge on `finish()`. The override below catches that
        // single funnel and relaunches MainActivity before tearing
        // down — this approach replaces the previous attempts that
        // routed back-press through `OnBackPressedCallback` because
        // some custom back paths on vivo / OriginOS bypass the
        // dispatcher entirely and go straight to `finish()`.
        toolbar.setNavigationOnClickListener { finish() }

        // Still register the dispatcher callback so the predictive-
        // back preview animation (Android 14+) has a callback to
        // bind its visual transition to. The body just calls
        // `finish()`; the relaunch logic lives in our `finish`
        // override so it runs no matter which path triggers the
        // teardown.
        onBackPressedDispatcher.addCallback(this) {
            finish()
        }

        val nameViews =
            listOf(
                findViewById<TextView>(R.id.credit_kevin_name),
                findViewById<TextView>(R.id.credit_yeonfeel_name),
                findViewById<TextView>(R.id.credit_chiyak_qa_name),
                findViewById<TextView>(R.id.credit_teqhnikacross_name),
                findViewById<TextView>(R.id.credit_parami_name),
            )
        startStaggeredHighlights(nameViews)

        // Replace the avatars' default `RoundedCornerTreatment`
        // (G1 — quarter-circle arc, with a visible curvature jump
        // where the arc meets the straight edge) with our
        // `SmoothCornerTreatment` (G2-ish — cubic Bézier whose
        // control points ramp the curvature smoothly out of the
        // straight edges into the corner). On 64dp avatars at 12dp
        // corner radius the arc rendering produces a visibly
        // "clumped" corner shoulder; the smooth corner treatment
        // distributes the same transition across more pixels and
        // reads as a softer, continuous curve.
        val smoothShape =
            ShapeAppearanceModel.builder()
                .setAllCorners(SmoothCornerTreatment())
                .setAllCornerSizes(AVATAR_CORNER_RADIUS_DP * resources.displayMetrics.density)
                .build()
        listOf(
            R.id.credit_kevin_avatar,
            R.id.credit_yeonfeel_avatar,
            R.id.credit_chiyak_avatar,
            R.id.credit_teqhnikacross_avatar,
            R.id.credit_parami_avatar,
        ).forEach { id ->
            findViewById<ShapeableImageView>(id)?.shapeAppearanceModel = smoothShape
        }
    }

    /**
     * Override the base teardown path so EVERY exit from the Credit
     * screen first relaunches MainActivity. Without this, vivo /
     * OriginOS task management was sometimes draining MainActivity
     * out of the back stack while the user read the credits, so a
     * regular `super.finish()` left the task empty and dumped the
     * user on the system launcher.
     *
     * `FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP` makes the
     * relaunch:
     *   * Reuse the existing MainActivity instance when one is still
     *     in the task — onNewIntent fires, the bottom-nav tab and
     *     fragment state survive — AND
     *   * Spin up a fresh MainActivity when the system trimmed the
     *     prior instance, so the user never lands on the launcher.
     *
     * The [hasLaunchedMainActivity] latch keeps the relaunch single-
     * shot in case `finish` is called more than once during the
     * teardown.
     */
    override fun finish() {
        if (!hasLaunchedMainActivity) {
            hasLaunchedMainActivity = true
            val intent =
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            startActivity(intent)
        }
        super.finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Snapshot the list before cancelling. Each animator's
        // `cancel()` synchronously fires its `onAnimationEnd` listener,
        // which removes the animator from `activeAnimators` — iterating
        // the live list while it mutates underneath us throws
        // ConcurrentModificationException. The platform turns that into
        // a "destroy activity failed" RuntimeException and tears the
        // entire task down, which on vivo / OriginOS dumps the user on
        // the system launcher (this was the real cause of the
        // long-running "back from Credit kills the app" report — the
        // crash was masquerading as a navigation bug).
        val snapshot = activeAnimators.toList()
        activeAnimators.clear()
        snapshot.forEach { it.cancel() }
    }

    private fun startStaggeredHighlights(views: List<TextView>) {
        views.forEachIndexed { index, view ->
            view.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    installNameHighlight(view)
                }
            }, index * STAGGER_DELAY_MS)
        }
    }

    /**
     * Compute the gradient + matrix once for the given [textView]
     * and start the sweep loop. Bails out early if the text has
     * zero measured width (rare — happens when the TextView has not
     * yet been laid out at the time `post` callbacks resolve, e.g.
     * if the activity is destroyed before first layout).
     */
    private fun installNameHighlight(textView: TextView) {
        val text = textView.text?.toString() ?: return
        if (text.isEmpty()) return
        val width = textView.paint.measureText(text).coerceAtLeast(1f)
        val height = textView.lineHeight.toFloat().coerceAtLeast(1f)
        val baseColor = textView.currentTextColor
        val highlightColor = ContextCompat.getColor(this, R.color.brand_primary)

        // Diagonal gradient: start (0,0) → end (width, height) makes
        // the band slope from upper-left to lower-right across the
        // text. Color stops keep the text its natural color at the
        // far edges and let the brand blue spread through the wider
        // [0.2, 0.8] band — a softer, broader sweep than the original
        // narrow [0.4, 0.6] window so the highlight reads as a
        // gradient wash rather than a hairline.
        val shader =
            LinearGradient(
                0f,
                0f,
                width,
                height,
                intArrayOf(baseColor, baseColor, highlightColor, baseColor, baseColor),
                floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f),
                Shader.TileMode.CLAMP,
            )
        textView.paint.shader = shader

        val matrix = Matrix()
        runHighlightLoop(textView, matrix, shader, width)
    }

    /**
     * One sweep iteration. Translates the shader's local matrix
     * from `-1.5*width` to `+1.5*width` over [HIGHLIGHT_SWEEP_DURATION_MS],
     * then schedules the next iteration after [HIGHLIGHT_PAUSE_MS].
     * Re-entrant on each tail so the loop is "sweep + pause + sweep"
     * indefinitely until the activity is destroyed.
     */
    private fun runHighlightLoop(
        textView: TextView,
        matrix: Matrix,
        shader: Shader,
        width: Float,
    ) {
        if (isFinishing || isDestroyed) return
        val animator =
            ValueAnimator.ofFloat(-width * 1.5f, width * 1.5f).apply {
                duration = HIGHLIGHT_SWEEP_DURATION_MS
                interpolator = LinearInterpolator()
                addUpdateListener { animation ->
                    val translateX = animation.animatedValue as Float
                    matrix.setTranslate(translateX, 0f)
                    shader.setLocalMatrix(matrix)
                    textView.invalidate()
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            activeAnimators.remove(animation as ValueAnimator)
                            textView.postDelayed({
                                if (!isFinishing && !isDestroyed) {
                                    runHighlightLoop(textView, matrix, shader, width)
                                }
                            }, HIGHLIGHT_PAUSE_MS)
                        }
                    },
                )
            }
        activeAnimators.add(animator)
        animator.start()
    }

    private companion object {
        /**
         * Delay between each contributor row's first sweep so the
         * highlights cascade down the screen rather than firing in
         * unison. 500 ms is short enough to feel like a coordinated
         * shimmer but long enough that the animations do not blur
         * into one chord.
         */
        const val STAGGER_DELAY_MS: Long = 500L

        /** Single-sweep duration. */
        const val HIGHLIGHT_SWEEP_DURATION_MS: Long = 1500L

        /** Pause between sweeps inside the same TextView's loop. */
        const val HIGHLIGHT_PAUSE_MS: Long = 3000L

        /**
         * Avatar corner radius in dp. Mirrors the value baked into
         * the `ShapeAppearanceOverlay.LibreDrop.CreditAvatar` style;
         * we re-derive it here when installing the smooth corner
         * treatment programmatically so a future radius change in
         * one place does not silently desync the other.
         */
        const val AVATAR_CORNER_RADIUS_DP: Float = 12f
    }
}
