/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.namecard

import dev.bluehouse.bada.protocol.namecard.NameCardBootstrap

/**
 * **Name Card consent wire codec** — the tiny opcode language the two phones speak on the CONSENT
 * GATT characteristic during a token-authenticated symmetric exchange.
 *
 * ## What it is / what carries it
 * A one-byte-opcode framing. The client WRITES these to the server's CONSENT characteristic; the
 * server NOTIFIES them back. It exists so each side learns the other's Share / Receive-Only choice
 * the instant it is made, and so the client can prove — inside HELLO — that it holds the session's
 * NFC rendezvous token.
 *
 * ## Messages ([ConsentMessage])
 *  - [ConsentMessage.Hello] `0x01 <version> <16B token>` — sent on connect/subscribe. The token is
 *    the session credential from the NFC tap; the server verifies it in constant time
 *    ([NameCardTokens.verify]) and disconnects any peer whose HELLO is missing or wrong. A HELLO
 *    without a full-length token is malformed and decodes to `null`.
 *  - [ConsentMessage.ChoiceShare] `0x02` — "I tapped Share" (my card is/was sent).
 *  - [ConsentMessage.ChoiceReceiveOnly] `0x03` — "I tapped Receive Only" (I will not send my card).
 *  - [ConsentMessage.Bye] `0x04` — terminal marker so the peer distinguishes "done" from a dropped
 *    link (a raw disconnect before both chose = No Response).
 *
 * ## Forward compatibility
 * [decode] tolerates EXTRA trailing bytes beyond a message's spec length (parse the known prefix and
 * ignore the rest) so a future build can append fields without breaking this one. Malformed input —
 * empty array, unknown opcode, or a HELLO shorter than its spec length — decodes to `null`.
 *
 * ## Status
 * Pure-JVM (zero `android.*` imports), unit-tested in `NameCardConsentCodecTest` (round-trip all
 * four opcodes; every malformed case; trailing-byte tolerance). The BLE transport that carries
 * these bytes ([NameCardBleExchange]) is compile-only on this box; device-verified only.
 */
internal object NameCardConsentCodec {
    /** Opcode: HELLO — `0x01 <version> <16B token>`. */
    const val OP_HELLO: Byte = 0x01

    /** Opcode: CHOICE_SHARE — `0x02`. */
    const val OP_CHOICE_SHARE: Byte = 0x02

    /** Opcode: CHOICE_RECEIVE_ONLY — `0x03`. */
    const val OP_CHOICE_RECEIVE_ONLY: Byte = 0x03

    /** Opcode: BYE — `0x04`. */
    const val OP_BYE: Byte = 0x04

    /** Protocol version carried in HELLO by this build. */
    const val PROTOCOL_VERSION: Byte = 0x02

    /** Byte length of the rendezvous token carried in HELLO. */
    const val HELLO_TOKEN_LEN: Int = NameCardBootstrap.TOKEN_LEN

    private const val HELLO_HEADER_LEN = 2

    /** Encode a [message] to its wire bytes. */
    fun encode(message: ConsentMessage): ByteArray =
        when (message) {
            is ConsentMessage.Hello -> {
                require(message.token.size == HELLO_TOKEN_LEN) {
                    "HELLO token must be $HELLO_TOKEN_LEN bytes, got ${message.token.size}"
                }
                byteArrayOf(OP_HELLO, message.version) + message.token
            }
            ConsentMessage.ChoiceShare -> byteArrayOf(OP_CHOICE_SHARE)
            ConsentMessage.ChoiceReceiveOnly -> byteArrayOf(OP_CHOICE_RECEIVE_ONLY)
            ConsentMessage.Bye -> byteArrayOf(OP_BYE)
        }

    /** Convenience: this build's HELLO bytes for the session [token]. */
    fun helloBytes(token: ByteArray): ByteArray = encode(ConsentMessage.Hello(PROTOCOL_VERSION, token))

    /**
     * Decode wire [bytes] into a [ConsentMessage], or `null` if malformed (empty, unknown opcode, or
     * a HELLO shorter than opcode+version+token). Extra trailing bytes beyond the spec length are
     * tolerated (forward-compat) — only the known prefix is read. A pre-token HELLO (`0x01 <ver>`
     * with no 16-byte token) is deliberately malformed: there is no unauthenticated fallback.
     */
    fun decode(bytes: ByteArray): ConsentMessage? {
        if (bytes.isEmpty()) return null
        return when (bytes[0]) {
            OP_HELLO ->
                if (bytes.size >= HELLO_HEADER_LEN + HELLO_TOKEN_LEN) {
                    ConsentMessage.Hello(
                        version = bytes[1],
                        token = bytes.copyOfRange(HELLO_HEADER_LEN, HELLO_HEADER_LEN + HELLO_TOKEN_LEN),
                    )
                } else {
                    null
                }
            OP_CHOICE_SHARE -> ConsentMessage.ChoiceShare
            OP_CHOICE_RECEIVE_ONLY -> ConsentMessage.ChoiceReceiveOnly
            OP_BYE -> ConsentMessage.Bye
            else -> null
        }
    }
}

/** A decoded consent-channel message. See [NameCardConsentCodec]. */
internal sealed interface ConsentMessage {
    /**
     * `0x01 <version> <16B token>` — the peer's session-auth announcement; [token] is verified
     * against the token minted for this tap before the peer becomes eligible for anything.
     */
    data class Hello(
        val version: Byte,
        val token: ByteArray,
    ) : ConsentMessage {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Hello) return false
            return version == other.version && token.contentEquals(other.token)
        }

        override fun hashCode(): Int = 31 * version.toInt() + token.contentHashCode()
    }

    /** `0x02` — peer tapped Share. */
    data object ChoiceShare : ConsentMessage

    /** `0x03` — peer tapped Receive Only. */
    data object ChoiceReceiveOnly : ConsentMessage

    /** `0x04` — peer is closing the link after reaching a terminal state. */
    data object Bye : ConsentMessage
}
