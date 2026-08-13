/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui.sheet

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup

/**
 * Centered, uniform-column grid container for the send-side device
 * picker (the circular [DeviceIconView] chips).
 *
 * All chips have the same width, so the layout behaves as a fixed grid:
 * the column count is whatever the available width allows
 * (`floor(width / chipWidth)`), the resulting grid block is centered
 * horizontally, and chips fill that block left-to-right, top-to-bottom.
 * A partial final row therefore stays left-aligned under the first
 * columns rather than re-centering on its own — the block is centered,
 * the items within it are not. Rows are top-aligned so every chip's icon
 * lines up even when neighbouring chips wrap their names to two lines.
 *
 * The caller wraps this in a vertical scroll container so the grid
 * scrolls once it grows past the visible area. Both the in-app
 * full-screen picker (`activity_send_fullscreen.xml`) and the share-sheet
 * picker (`activity_send.xml`) host this same view so the two pickers
 * render identically; only the surrounding container differs.
 *
 * Per-chip spacing comes from each [DeviceIconView]'s own padding, so this
 * container adds no inter-child margins of its own.
 */
public class FlowLayout
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : ViewGroup(context, attrs, defStyleAttr) {
        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val widthSize = MeasureSpec.getSize(widthMeasureSpec)
            val available = (widthSize - paddingLeft - paddingRight).coerceAtLeast(0)
            val childWidthSpec = MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST)
            val childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

            var maxChildWidth = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                measureChild(child, childWidthSpec, childHeightSpec)
                maxChildWidth = maxOf(maxChildWidth, child.measuredWidth)
            }

            val columns = columnsFor(available, maxChildWidth)
            var col = 0
            var rowHeight = 0
            var contentHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                if (col == columns) {
                    contentHeight += rowHeight
                    rowHeight = 0
                    col = 0
                }
                rowHeight = maxOf(rowHeight, child.measuredHeight)
                col++
            }
            contentHeight += rowHeight

            setMeasuredDimension(
                resolveSize(maxChildWidth * columns + paddingLeft + paddingRight, widthMeasureSpec),
                resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec),
            )
        }

        override fun onLayout(
            changed: Boolean,
            l: Int,
            t: Int,
            r: Int,
            b: Int,
        ) {
            val available = (r - l - paddingLeft - paddingRight).coerceAtLeast(0)
            var maxChildWidth = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility != GONE) maxChildWidth = maxOf(maxChildWidth, child.measuredWidth)
            }
            val columns = columnsFor(available, maxChildWidth)
            // Center the full grid block; items then fill it from the left.
            val blockLeft = paddingLeft + ((available - columns * maxChildWidth) / 2).coerceAtLeast(0)

            var col = 0
            var rowTop = paddingTop
            var rowHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                if (col == columns) {
                    rowTop += rowHeight
                    rowHeight = 0
                    col = 0
                }
                val cw = child.measuredWidth
                val ch = child.measuredHeight
                // Left-align each chip within its fixed-width cell; top-align
                // so icons stay on one baseline regardless of name line count.
                val x = blockLeft + col * maxChildWidth
                child.layout(x, rowTop, x + cw, rowTop + ch)
                rowHeight = maxOf(rowHeight, ch)
                col++
            }
        }

        /** Number of equal-width columns that fit in [available]; at least 1. */
        private fun columnsFor(
            available: Int,
            childWidth: Int,
        ): Int = if (childWidth <= 0) 1 else (available / childWidth).coerceAtLeast(1)
    }
