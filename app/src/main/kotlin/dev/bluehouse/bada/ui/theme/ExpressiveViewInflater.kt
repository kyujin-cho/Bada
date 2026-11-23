/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui.theme

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.use
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.theme.MaterialComponentsViewInflater
import dev.bluehouse.bada.R

/**
 * View inflater of the Material 3 Expressive theme (`viewInflaterClass` in
 * themes_expressive.xml).
 *
 * MaterialComponentsViewInflater already turns `<Button>` into an M3
 * MaterialButton. It does not touch switches, so layouts that declare
 * `SwitchCompat` would keep the AppCompat switch, which has no M3 track and
 * thumb. This inflater builds a [MaterialSwitch] (a SwitchCompat subclass,
 * so code that finds the view as SwitchCompat keeps working) for those
 * tags. A horizontal `LinearLayout` marked `app:badaButtonGroup="true"`
 * becomes a [BadaButtonGroup] (M3 Expressive standard button group). The
 * Bada theme does not use this class, so its switches and rows are
 * unchanged.
 *
 * AppCompat creates the inflater reflectively, so it must stay public with
 * a no-argument constructor.
 */
public class ExpressiveViewInflater : MaterialComponentsViewInflater() {
    override fun createView(
        context: Context,
        name: String,
        attrs: AttributeSet,
    ): View? =
        when {
            name == SWITCH_COMPAT || name == FRAMEWORK_SWITCH -> MaterialSwitch(context, attrs)
            name == LINEAR_LAYOUT && isButtonGroup(context, attrs) -> BadaButtonGroup(context, attrs)
            else -> super.createView(context, name, attrs)
        }

    private fun isButtonGroup(
        context: Context,
        attrs: AttributeSet,
    ): Boolean =
        context.obtainStyledAttributes(attrs, intArrayOf(R.attr.badaButtonGroup)).use { it.getBoolean(0, false) }

    private companion object {
        const val SWITCH_COMPAT = "androidx.appcompat.widget.SwitchCompat"
        const val FRAMEWORK_SWITCH = "Switch"
        const val LINEAR_LAYOUT = "LinearLayout"
    }
}
