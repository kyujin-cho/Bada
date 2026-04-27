/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.endpoint

/**
 * The 3-bit `deviceType` field encoded in the first byte of an [EndpointInfo]
 * descriptor.
 *
 * Values mirror PROTOCOL.md and the NearDrop Swift reference. The 3-bit field
 * has 8 possible raw values (0..7); 0..6 are documented and 7 is currently
 * undefined. Undefined raw values (including 7 and any future expansion) are
 * surfaced as [UNKNOWN] rather than throwing, so a parser running against a
 * peer that uses a newer protocol revision degrades gracefully instead of
 * dropping the entire advertisement.
 *
 * Note: the `reserved` rawValue 0 is named identically to [UNKNOWN] in the
 * canonical spec. We keep the single [UNKNOWN] entry to cover both "the peer
 * sent 0" and "the peer sent something we don't recognize".
 */
public enum class DeviceType(
    /** Raw 3-bit value as it appears on the wire. */
    public val raw: Int,
) {
    UNKNOWN(0),
    PHONE(1),
    TABLET(2),
    LAPTOP(3),
    CAR(4),
    FOLDABLE(5),
    XR(6),
    ;

    public companion object {
        /**
         * Maps the raw 3-bit on-the-wire value to a [DeviceType], collapsing
         * any unrecognized value (notably `7`, which is reserved) to
         * [UNKNOWN]. This is intentional: we never want a forward-compatible
         * peer that introduces a new device type to make our parser drop the
         * whole advertisement.
         */
        public fun fromRaw(raw: Int): DeviceType =
            when (raw) {
                PHONE.raw -> PHONE
                TABLET.raw -> TABLET
                LAPTOP.raw -> LAPTOP
                CAR.raw -> CAR
                FOLDABLE.raw -> FOLDABLE
                XR.raw -> XR
                else -> UNKNOWN
            }
    }
}
