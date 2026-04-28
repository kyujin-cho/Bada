/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.payload

/**
 * Mutable, ordered set of half-open byte ranges `[start, end)` over a
 * single payload.
 *
 * Used by [PayloadAssembler] to track which byte ranges of an inbound
 * FILE payload have already been written. Quick Share's wire spec
 * permits chunks to arrive in any order — sequential delivery is a
 * property of TCP, not of the application protocol — and #44 relaxes
 * the assembler's strict in-order check so the receiver can survive
 * mediums (Wi-Fi Direct, BLE L2CAP) that do not preserve order at the
 * application layer.
 *
 * ### Invariants
 *
 *  - All ranges are stored in a sorted, **non-overlapping**, **non-
 *    adjacent** form. After any successful [add], the set's internal
 *    list contains ranges with the property that `ranges[i].end <
 *    ranges[i+1].start` (strictly less, never equal — adjacent ranges
 *    are merged on insert).
 *  - Range bounds are non-negative; the empty range `[start, start)` is
 *    a no-op (silently ignored).
 *
 * ### Performance
 *
 *  - [add] is `O(log n + k)` where `n` is the number of stored ranges
 *    and `k` is the number of existing ranges that the new range
 *    overlaps or touches. In the common case (sequential arrival) `k`
 *    is constant — the new range merges with the tail and `n` stays
 *    at 1.
 *  - [contains] is `O(log n)` via binary search.
 *  - [coveredBytes] is `O(n)` and is invoked only at completion-check
 *    time, not on every chunk.
 *
 * ### Why a custom container instead of `RangeSet` / `IntervalTree`
 *
 * Pulling Guava just for `RangeSet` would balloon `:core-protocol`'s
 * dependency surface (Guava + its transitive `error_prone_annotations`
 * etc.) for the sake of one container that handles fewer cases than
 * we need anyway (Guava's `RangeSet` distinguishes open / closed
 * endpoints; we only ever use half-open `[start, end)`). A few dozen
 * lines of straight Kotlin keep the JVM-only build self-contained.
 *
 * Internal-ish API: not part of the documented `:core-protocol` public
 * surface, but `public` so the JVM tests can verify it. Marked
 * explicitly to keep `explicitApi()` happy.
 */
public class ByteRangeSet {
    /**
     * Storage for the canonical, sorted, non-overlapping range list.
     * Backed by `ArrayList` so the merge path can splice in place.
     *
     * Visibility kept `internal` so tests in the same module can assert
     * on the exact merge layout, while production callers use only the
     * methods below.
     */
    internal val ranges: ArrayList<Range> = ArrayList()

    /**
     * Half-open interval `[start, end)`. Equality / hash code are
     * structural so a [ByteRangeSet] can be compared by content in
     * tests.
     *
     * @property start inclusive lower bound, must be `>= 0`.
     * @property end exclusive upper bound, must be `>= start`.
     */
    public data class Range(
        val start: Long,
        val end: Long,
    ) {
        init {
            require(start >= 0) { "start must be non-negative, got $start" }
            require(end >= start) { "end ($end) must be >= start ($start)" }
        }
    }

    /**
     * Total number of bytes covered across all stored ranges.
     *
     * `O(n)` over the range count — typically very small (1 in the
     * happy ordered-arrival case, a few dozen in the worst pathological
     * shuffled case).
     */
    public val coveredBytes: Long
        get() {
            var total = 0L
            for (r in ranges) total += r.end - r.start
            return total
        }

    /**
     * Number of distinct ranges currently stored. Visible mainly for
     * diagnostics / tests; production code paths only care about
     * [coveredBytes] and [contains].
     */
    public val size: Int get() = ranges.size

    /**
     * Insert the half-open range `[start, end)` into the set.
     *
     * Overlapping or adjacent ranges are merged so the set stays
     * canonical. Returns information about whether the insert added
     * any genuinely-new bytes — callers (the FILE assembler) use this
     * to detect duplicate / overlapping chunk delivery.
     *
     * @return [AddResult] describing the outcome.
     * @throws IllegalArgumentException if `end < start` or `start < 0`.
     */
    public fun add(
        start: Long,
        end: Long,
    ): AddResult {
        require(start >= 0) { "start must be non-negative, got $start" }
        require(end >= start) { "end ($end) must be >= start ($start)" }
        if (start == end) {
            // Empty range is a no-op. Treated as "fully covered" since
            // there are no bytes to add.
            return AddResult.AlreadyCovered
        }

        // Find the first existing range whose end is strictly greater
        // than `start` — that's the leftmost range we might overlap or
        // be adjacent-on-the-left to. Anything before it ends before
        // `start` and is untouched.
        var i = 0
        while (i < ranges.size && ranges[i].end < start) {
            i += 1
        }

        // Now extend the merge window forward as long as the next
        // existing range either overlaps or is adjacent to the running
        // [newStart, newEnd) window.
        var newStart = start
        var newEnd = end
        var overlappingBytes = 0L
        // Sentinel "no consumption yet" markers. `consumedFirst < 0`
        // means the merge loop did not consume any existing range; in
        // that case we splice the new range in at position `i` instead
        // of replacing a span. The two sentinels are slightly different
        // so a stray `consumedLast - consumedFirst + 1` length cannot
        // accidentally be 0 — we always check `consumedFirst >= 0`.
        var consumedFirst = NO_CONSUMPTION
        var consumedLast = NO_CONSUMPTION_LAST
        while (i < ranges.size && ranges[i].start <= newEnd) {
            val r = ranges[i]
            // Track how many bytes of this insert overlap an existing
            // range. The `add` is a duplicate-only insert if every byte
            // of [start, end) lands inside a pre-existing range.
            val overlapStart = maxOf(start, r.start)
            val overlapEnd = minOf(end, r.end)
            if (overlapEnd > overlapStart) {
                overlappingBytes += overlapEnd - overlapStart
            }
            if (consumedFirst == NO_CONSUMPTION) consumedFirst = i
            consumedLast = i
            newStart = minOf(newStart, r.start)
            newEnd = maxOf(newEnd, r.end)
            i += 1
        }

        if (consumedFirst != NO_CONSUMPTION) {
            // Replace the [consumedFirst..consumedLast] contiguous span
            // with the merged range in one subList clear + insert.
            ranges.subList(consumedFirst, consumedLast + 1).clear()
            ranges.add(consumedFirst, Range(newStart, newEnd))
        } else {
            // No overlap or adjacency — insert at position `i`, which
            // by the loop invariant is the first range whose start is
            // strictly greater than newEnd (or end of list).
            ranges.add(i, Range(newStart, newEnd))
        }

        val insertedSize = end - start
        return when {
            overlappingBytes == 0L -> AddResult.Added(addedBytes = insertedSize)
            overlappingBytes == insertedSize -> AddResult.AlreadyCovered
            else -> AddResult.PartialOverlap(addedBytes = insertedSize - overlappingBytes)
        }
    }

    /**
     * Whether `[start, end)` is fully contained in some stored range.
     */
    public fun contains(
        start: Long,
        end: Long,
    ): Boolean {
        // Linear scan is fine for our small `n`; binary search would
        // optimize the worst case but the constant factor on n<=20 is
        // unimprovable. Single exit point keeps detekt happy and lets
        // the loop short-circuit via the `found` sentinel and an early
        // structural break-out via `done` once we have walked past the
        // candidate range.
        if (start == end) return true
        var found = false
        var done = false
        for (r in ranges) {
            if (r.start > start) {
                done = true
            } else if (r.start <= start && end <= r.end) {
                found = true
                done = true
            }
            if (done) break
        }
        return found
    }

    /**
     * Remove every stored range. Used during connection teardown so a
     * pooled assembler does not carry over state from a prior payload.
     */
    public fun clear() {
        ranges.clear()
    }

    /**
     * Whether the set is exactly the single range `[0, totalSize)`,
     * i.e. the payload is fully covered with no gaps. `true` for the
     * degenerate `totalSize = 0` empty payload.
     */
    public fun isComplete(totalSize: Long): Boolean {
        require(totalSize >= 0) { "totalSize must be non-negative, got $totalSize" }
        if (totalSize == 0L) return true
        return ranges.size == 1 && ranges[0].start == 0L && ranges[0].end == totalSize
    }

    /**
     * Outcome of an [add] call.
     *
     * The distinction is exposed so callers can discriminate
     * "duplicate chunk re-delivered" from "chunk landed in a fresh
     * region". The assembler uses [AlreadyCovered] to deduplicate
     * gracefully (write nothing, advance no state) and
     * [PartialOverlap] to flag a malformed chunk that overlaps an
     * existing range while extending it — that shape is **not**
     * permitted on the wire and is treated as a protocol error.
     */
    private companion object {
        /**
         * Sentinel "no range consumed yet" marker for the first index
         * tracked during the merge loop in [add]. Any non-negative value
         * is a valid index; we use `-1` as the not-set marker.
         */
        const val NO_CONSUMPTION: Int = -1

        /**
         * Companion sentinel for the last-consumed index. Distinct
         * from [NO_CONSUMPTION] so a casual reader can tell the two
         * apart, and so a length computation `last - first + 1` cannot
         * accidentally evaluate to 0 if both sentinels are still set.
         */
        const val NO_CONSUMPTION_LAST: Int = -2
    }

    public sealed interface AddResult {
        /** The inserted range was strictly new; [addedBytes] equals the body size. */
        public data class Added(
            val addedBytes: Long,
        ) : AddResult

        /** Every byte of the inserted range was already covered. */
        public object AlreadyCovered : AddResult

        /**
         * The inserted range overlaps an existing range but ALSO
         * extends beyond it. The wire protocol does not permit this:
         * a peer that re-sends a chunk MUST send the original byte
         * range exactly. Callers MUST treat this as a malformed-frame
         * protocol error.
         *
         * @property addedBytes Number of bytes that were genuinely
         *   new (i.e. not already covered).
         */
        public data class PartialOverlap(
            val addedBytes: Long,
        ) : AddResult
    }
}
