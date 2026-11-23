/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui.theme

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonGroup

/**
 * M3 Expressive standard button group for Bada's button rows.
 *
 * ExpressiveViewInflater builds this in place of a `LinearLayout` that
 * carries `app:badaButtonGroup="true"`, so pressing one button of the row
 * changes its shape and width and its neighbour gives way. The Bada theme
 * keeps the plain LinearLayout.
 *
 * The shared layouts separate the two buttons with a 12dp `Space`. The group
 * supplies its own spacing per button size, and MaterialButtonGroup refuses
 * non-button children with an error log, so this subclass drops them quietly.
 */
public class BadaButtonGroup
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : MaterialButtonGroup(context, attrs) {
        override fun addView(
            child: View,
            index: Int,
            params: ViewGroup.LayoutParams?,
        ) {
            if (child is MaterialButton) super.addView(child, index, params)
        }
    }
