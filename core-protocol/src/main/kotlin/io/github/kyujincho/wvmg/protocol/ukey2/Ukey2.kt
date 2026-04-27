/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.ukey2

/**
 * Protocol-wide constants and the handshake result type for the Quick Share
 * UKEY2 cipher-commitment-then-reveal key exchange.
 *
 * UKEY2 is Google's bespoke key-exchange protocol that opens every Quick
 * Share connection. The client publishes a `SHA512(ClientFinished)`
 * commitment up-front, the server picks a cipher and replies with its
 * ephemeral public key, and only then does the client reveal the actual
 * `ClientFinished` (containing its own ephemeral public key). Because the
 * commitment is bound to the cipher choice **before** the server gets to
 * see the client's public key, a man-in-the-middle cannot rewrite the
 * cipher list without invalidating the commitment.
 *
 * For Quick Share specifically there is exactly one cipher in the wild —
 * `P256_SHA512` (NIST P-256 ECDH + SHA-512 commitments) — and the
 * negotiated `next_protocol` is fixed at `"AES_256_CBC-HMAC_SHA256"`.
 * Anything else triggers a [Ukey2HandshakeException] and a `Ukey2Alert`
 * frame back to the peer.
 *
 * See [issue #10](https://github.com/kyujin-cho/WhenVivoMeetsGoogle/issues/10)
 * and [google/ukey2](https://github.com/google/ukey2) for the full spec.
 */
public object Ukey2 {
    /**
     * Highest protocol version we speak. Quick Share is locked to v1.
     */
    public const val PROTOCOL_VERSION: Int = 1

    /**
     * Length of the per-side `random` nonce in bytes. The UKEY2 spec fixes
     * this at 32, and Quick Share peers reject anything shorter or longer.
     */
    public const val RANDOM_SIZE: Int = 32

    /**
     * The single `next_protocol` string Quick Share negotiates. Required
     * verbatim — peers reply with `BAD_NEXT_PROTOCOL` for any other value.
     */
    public const val NEXT_PROTOCOL: String = "AES_256_CBC-HMAC_SHA256"

    /**
     * NIST P-256 named curve identifier as understood by `KeyPairGenerator`
     * and `ECGenParameterSpec`. The same curve is referred to as
     * `prime256v1` in OpenSSL and `P-256` in NIST publications.
     */
    public const val P256_CURVE_NAME: String = "secp256r1"

    /**
     * Byte length of the X (or Y) affine coordinate of a P-256 point on the
     * wire. Quick Share is strict: each coordinate is **exactly** 32 bytes,
     * unsigned big-endian. Implementations must zero-pad short magnitudes
     * and truncate the leading sign byte off oversized [java.math.BigInteger]
     * encodings.
     */
    public const val P256_COORDINATE_SIZE: Int = 32
}

/**
 * Result of a successful UKEY2 handshake, returned to callers of
 * `Ukey2Client.performHandshake` or `Ukey2Server.performHandshake`.
 *
 * The fields here are exactly what downstream UKEY2-derivation code (issue
 * #11) consumes:
 *
 *  - [dhs] is `SHA-256(ECDH-shared-secret-X-magnitude)`. Per NearDrop's
 *    `finalizeKeyExchange()`, the X coordinate of the shared point is
 *    converted to its **magnitude** representation (unsigned big-endian
 *    with leading zero bytes stripped) **before** hashing. This matters: a
 *    raw 32-byte coordinate that happens to start with `0x00` would
 *    produce a different SHA-256 hash than a stripped 31-byte magnitude.
 *    Bit-perfect parity with the macOS reference implementation requires
 *    matching the magnitude form.
 *
 *  - [clientInitMsg] and [serverInitMsg] are the **raw serialized
 *    `Ukey2Message` bytes** for ClientInit and ServerInit respectively
 *    (i.e., the protobuf wrapper, NOT length-prefixed and NOT the inner
 *    `Ukey2ClientInit` / `Ukey2ServerInit` payload). They are concatenated
 *    as the HKDF `info` parameter when deriving the next-protocol session
 *    keys (`UKEY2 v1 next` salt) and the auth string (`UKEY2 v1 auth`
 *    salt). Storing them is mandatory; the peer cannot recompute them
 *    locally because each side has a private random nonce.
 *
 * @property dhs SHA-256 of the ECDH shared secret X-magnitude. 32 bytes.
 * @property clientInitMsg Serialized `Ukey2Message` wrapping the
 *   `Ukey2ClientInit` we sent (client role) or received (server role).
 * @property serverInitMsg Serialized `Ukey2Message` wrapping the
 *   `Ukey2ServerInit` we received (client role) or sent (server role).
 */
public class Ukey2HandshakeResult(
    public val dhs: ByteArray,
    public val clientInitMsg: ByteArray,
    public val serverInitMsg: ByteArray,
) {
    init {
        require(dhs.size == DHS_SIZE) {
            "dhs must be exactly $DHS_SIZE bytes (SHA-256 output), got ${dhs.size}"
        }
    }

    public companion object {
        /** SHA-256 output length in bytes. Equal to `dhs.size`. */
        public const val DHS_SIZE: Int = 32
    }
}
