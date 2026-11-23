/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui.theme

import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import com.google.android.material.listitem.ListItemLayout

/**
 * Helpers for the Material 3 Expressive list layouts (segmented lists built
 * from MDC ListItemLayout + ListItemCardView) and for the per-theme layout
 * regions (ViewStubs whose layout is a theme token).
 */
internal object ExpressiveLists {
    /**
     * Inflates the ViewStub [stubId] in [root], if it is still there. Its
     * `android:layout` is a theme token, so this picks the region's variant
     * for the current theme. Call it before looking up the region's views.
     */
    fun inflateStub(
        root: View,
        stubId: Int,
    ) {
        root.findViewById<ViewStub>(stubId)?.inflate()
    }

    /**
     * Gives every visible ListItemLayout child of [container] its segmented
     * position (first, middle, last, or single). The item shapes follow:
     * rounded outer corners on the ends, extra small corners inside.
     */
    fun applySegmentedPositions(container: ViewGroup) {
        val items =
            container.children
                .filterIsInstance<ListItemLayout>()
                .filter { it.isVisible }
                .toList()
        items.forEachIndexed { index, item -> item.updateAppearance(index, items.size) }
    }

    /**
     * Makes each list item in [container] that holds a switch toggle it when
     * the item is tapped. M3 lists allow one selection interaction per item,
     * and the whole item is its target.
     */
    fun toggleSwitchOnItemClick(container: ViewGroup) {
        container.children.filterIsInstance<ListItemLayout>().forEach { item ->
            val card = item.getChildAt(0) ?: return@forEach
            val switch = card.findSwitch() ?: return@forEach
            card.setOnClickListener { switch.toggle() }
        }
    }

    private fun View.findSwitch(): SwitchCompat? =
        when (this) {
            is SwitchCompat -> this
            is ViewGroup -> children.firstNotNullOfOrNull { it.findSwitch() }
            else -> null
        }
}
