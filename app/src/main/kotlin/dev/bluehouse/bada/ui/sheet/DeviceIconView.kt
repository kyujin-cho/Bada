/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui.sheet

import android.content.Context
import android.view.Gravity
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * One discovered device in the
 * send sheet, rendered as a circular [RingProgressView] icon, the device
 * name below it, and a status line ("Connecting", "Sending"). Tapping
 * plays a bounce so the user sees the tap registered.
 *
 * [peerId] carries the discovered peer's stable identity so the host can
 * route this chip's click back through the unchanged selection path.
 * `nameView` is the centered peer-name label shown directly below the 64dp
 * circular device icon in both the external share sheet and the in-app send
 * screen. It renders up to two 13sp lines and inherits the activity theme's
 * primary text color, keeping it dark in day mode and light in night mode.
 * [SendPeerPickerController] creates the view whenever discovery renders a
 * connectable peer. [DeviceIconViewSourceTest] guards the no-hardcoded-color
 * contract; rendered day/night behavior still requires an on-device UI check.
 *
 * Programmatic custom view: dp sizes, text sizes, and ARGB colour components are
 * inherently numeric layout constants, so MagicNumber is suppressed.
 */
@Suppress("MagicNumber")
public class DeviceIconView(
    context: Context,
    public val peerId: String,
    name: String,
) : LinearLayout(context) {
    private val ring: RingProgressView
    private val nameView: TextView
    private val statusView: TextView

    init {
        val d = context.resources.displayMetrics.density
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val pad = (8 * d).toInt()
        setPadding(pad, pad, pad, pad)

        ring = RingProgressView(context)
        val size = (64 * d).toInt()
        addView(ring, LayoutParams(size, size))

        nameView =
            TextView(context).apply {
                text = name
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, (6 * d).toInt(), 0, 0)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        addView(nameView, LayoutParams((96 * d).toInt(), LayoutParams.WRAP_CONTENT))

        statusView =
            TextView(context).apply {
                setTextColor(STATUS_COLOR)
                textSize = 11f
                gravity = Gravity.CENTER
            }
        addView(statusView)
    }

    /** Bounce the icon to acknowledge a tap. */
    public fun bounce() {
        ring
            .animate()
            .scaleX(BOUNCE_DOWN_SCALE)
            .scaleY(BOUNCE_DOWN_SCALE)
            .setDuration(BOUNCE_DOWN_MS)
            .withEndAction {
                ring
                    .animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setInterpolator(OvershootInterpolator(BOUNCE_TENSION))
                    .setDuration(BOUNCE_UP_MS)
                    .start()
            }.start()
    }

    /** Transfer complete: icon morphs to a green check, with a little bounce. */
    public fun complete() {
        ring.setComplete()
        bounce()
    }

    public fun setStatus(status: String?) {
        statusView.text = status ?: ""
    }

    public fun setProgress(percent: Int) {
        ring.setProgress(percent)
    }

    public fun setName(name: String) {
        nameView.text = name
    }

    public companion object {
        private const val STATUS_COLOR = 0xFF0A84FF.toInt()
        private const val BOUNCE_DOWN_SCALE = 0.82f
        private const val BOUNCE_DOWN_MS = 90L
        private const val BOUNCE_UP_MS = 320L
        private const val BOUNCE_TENSION = 3.5f
    }
}
