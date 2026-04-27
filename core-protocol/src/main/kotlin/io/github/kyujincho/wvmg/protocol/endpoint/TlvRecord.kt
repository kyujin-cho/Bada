/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.endpoint

/**
 * A single TLV (Type-Length-Value) record appended to an [EndpointInfo]
 * payload after the (optional) device-name section.
 *
 * Wire layout: `type(1 byte) | length(1 byte) | value(`length` bytes)`.
 *
 * The two known [type] values are documented in PROTOCOL.md:
 *
 *  - `1` = QR code data (visible: 4-byte advertising token; hidden:
 *    AES-GCM-encrypted name material).
 *  - `2` = vendor ID (1-byte vendor: `0` = none, `1` = Samsung).
 *
 * Unknown types are NOT rejected by the parser — they round-trip verbatim so
 * we stay forward-compatible with new TLV records introduced by Google's
 * Quick Share rollout.
 *
 * @property type Unsigned 8-bit type tag (`0..255`).
 * @property value Opaque payload. Length must fit in 1 byte (`0..255`).
 */
public data class TlvRecord(
    val type: Int,
    val value: ByteArray,
) {
    init {
        require(type in 0..MAX_BYTE_VALUE) {
            "TLV type must fit in 1 byte (0..$MAX_BYTE_VALUE), got $type"
        }
        require(value.size in 0..MAX_BYTE_VALUE) {
            "TLV value length must fit in 1 byte (0..$MAX_BYTE_VALUE), got ${value.size}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TlvRecord) return false
        return type == other.type && value.contentEquals(other.value)
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + value.contentHashCode()
        return result
    }

    public companion object {
        /** Maximum value of a 1-byte unsigned field (used for both `type` and `length`). */
        public const val MAX_BYTE_VALUE: Int = 0xFF
    }
}
