/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui.sheet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source regression guard for the receive sheet's [RoundedProgressBar] (#298).
 * The sheet calls `setProgress` once per payload chunk, so restarting the fill
 * animator on every call starved it and left the bar near empty while the
 * percent label kept counting. This rejects a restoration of the
 * cancel-and-restart shape. It reads only the owning Kotlin source and performs
 * no Android rendering or animation.
 */
class RoundedProgressBarSourceTest {
    private val source: String by lazy {
        val file = File("src/main/kotlin/dev/bluehouse/bada/ui/sheet/RoundedProgressBar.kt")
        assertTrue("RoundedProgressBar.kt should exist at ${file.absolutePath}", file.exists())
        file.readText()
    }

    @Test
    fun `setProgress retargets a running animation instead of restarting it`() {
        val setProgressBody =
            source.substringAfter("public fun setProgress(").substringBefore("private fun animateTowardTarget")

        assertFalse(
            "setProgress must not cancel the running fill animation (#298).",
            setProgressBody.contains(".cancel()"),
        )
        assertTrue(
            "setProgress must only start a new segment when none is running.",
            setProgressBody.contains("isStarted"),
        )
    }

    @Test
    fun `a finished segment re-arms toward the latest target`() {
        val animateBody = source.substringAfter("private fun animateTowardTarget")

        assertTrue(
            "The segment end must chase the newest target set while it was running.",
            animateBody.contains("onAnimationEnd") && animateBody.contains("animateTowardTarget()"),
        )
    }
}
