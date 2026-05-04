/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.crypto

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Derives the Quick Share traffic keys that follow a successful UKEY2
 * handshake.
 *
 * The chain has three stages, each of which is a single HKDF-SHA256 call.
 * Bit-exact parity with NearDrop's `NearbyConnection.swift:finalizeKeyExchange`
 * is **required** — any drift in salt, info, or input ordering produces keys
 * that silently fail to decrypt over the air. The reference implementation
 * lives at
 * [grishka/NearDrop](https://github.com/grishka/NearDrop/blob/master/NearbyShare/NearbyConnection.swift).
 *
 * ```
 * # Stage 1 — UKEY2 layer (inputs come from [dev.bluehouse.libredrop.protocol.ukey2.Ukey2HandshakeResult])
 * ukeyInfo    = ukeyClientInitMsg || ukeyServerInitMsg
 *               (raw serialized Ukey2Message bytes; NOT length-prefixed)
 * authString  = HKDF-SHA256(ikm = dhs,        salt = "UKEY2 v1 auth", info = ukeyInfo, L = 32)
 * nextSecret  = HKDF-SHA256(ikm = dhs,        salt = "UKEY2 v1 next", info = ukeyInfo, L = 32)
 *
 * # Stage 2 — D2D layer (per-direction master keys)
 * D2D_salt    = SHA256("D2D")
 *             = 0x82AA55A0D397F88346CA1CEE8D3909B95F13FA7DEB1D4AB38376B8256DA85510
 * d2dClient   = HKDF-SHA256(ikm = nextSecret, salt = D2D_salt,        info = "client", L = 32)
 * d2dServer   = HKDF-SHA256(ikm = nextSecret, salt = D2D_salt,        info = "server", L = 32)
 *
 * # Stage 3 — SecureMessage layer (split into encrypt + HMAC keys)
 * SM_salt     = SHA256("SecureMessage")
 *             = 0xBF9D2A53C63616D75DB0A7165B91C1EF73E537F2427405FA23610A4BE657642E
 * clientEnc   = HKDF-SHA256(ikm = d2dClient,  salt = SM_salt,         info = "ENC:2", L = 32)
 * clientHmac  = HKDF-SHA256(ikm = d2dClient,  salt = SM_salt,         info = "SIG:1", L = 32)
 * serverEnc   = HKDF-SHA256(ikm = d2dServer,  salt = SM_salt,         info = "ENC:2", L = 32)
 * serverHmac  = HKDF-SHA256(ikm = d2dServer,  salt = SM_salt,         info = "SIG:1", L = 32)
 * ```
 *
 * Role mapping for the per-direction send/receive selection:
 *
 *  - **server** sends with `serverEnc`/`serverHmac`, receives with `clientEnc`/`clientHmac`.
 *  - **client** sends with `clientEnc`/`clientHmac`, receives with `serverEnc`/`serverHmac`.
 *
 * Role swap is the most error-prone failure mode in the entire stack — a
 * dedicated [D2DSessionKeys.forRole] selector exists to keep the swap in one
 * place, and a unit test asserts that swapping the role flips send and receive
 * symmetrically.
 *
 * Sensitive data discipline: never log [D2DSessionKeys] fields, [authString],
 * [nextSecret], or any of the intermediate D2D keys. They gate confidentiality
 * and integrity of every Quick Share frame on the connection.
 */
public object D2DKeyDerivation {
    /** Output size of every key in this derivation chain. AES-256 / HMAC-SHA256 = 32 bytes. */
    public const val KEY_SIZE: Int = 32

    /**
     * HKDF salt for the UKEY2 `authString` derivation. ASCII-safe; encoded as
     * US-ASCII to keep the byte representation invariant across JVMs and
     * Android API levels (UTF-8 yields identical bytes for this string, but
     * pinning to US-ASCII makes future copy-paste regressions impossible).
     */
    internal val UKEY2_AUTH_SALT: ByteArray =
        "UKEY2 v1 auth".toByteArray(StandardCharsets.US_ASCII)

    /** HKDF salt for the UKEY2 `nextSecret` derivation. */
    internal val UKEY2_NEXT_SALT: ByteArray =
        "UKEY2 v1 next".toByteArray(StandardCharsets.US_ASCII)

    /**
     * D2D HKDF salt — `SHA-256("D2D")`. Hard-coded as the literal 32-byte
     * digest rather than computed lazily so that:
     *
     *  1. The salt's value is reviewable inline against NearDrop's source
     *     (`NearbyConnection.swift:376-378`).
     *  2. There is no `MessageDigest` allocation per derivation.
     *
     * The companion test [dev.bluehouse.libredrop.protocol.crypto.D2DKeyDerivationTest]
     * recomputes `SHA-256("D2D")` at test time and asserts equality with this
     * constant, so a typo here cannot pass CI.
     */
    internal val D2D_SALT: ByteArray =
        byteArrayOf(
            0x82.toByte(),
            0xAA.toByte(),
            0x55.toByte(),
            0xA0.toByte(),
            0xD3.toByte(),
            0x97.toByte(),
            0xF8.toByte(),
            0x83.toByte(),
            0x46.toByte(),
            0xCA.toByte(),
            0x1C.toByte(),
            0xEE.toByte(),
            0x8D.toByte(),
            0x39.toByte(),
            0x09.toByte(),
            0xB9.toByte(),
            0x5F.toByte(),
            0x13.toByte(),
            0xFA.toByte(),
            0x7D.toByte(),
            0xEB.toByte(),
            0x1D.toByte(),
            0x4A.toByte(),
            0xB3.toByte(),
            0x83.toByte(),
            0x76.toByte(),
            0xB8.toByte(),
            0x25.toByte(),
            0x6D.toByte(),
            0xA8.toByte(),
            0x55.toByte(),
            0x10.toByte(),
        )

    /**
     * SecureMessage HKDF salt — `SHA-256("SecureMessage")`. NearDrop computes
     * this at runtime; we precompute it for the same reasons as [D2D_SALT].
     * Verified against `SHA-256("SecureMessage")` by the companion test.
     */
    internal val SECURE_MESSAGE_SALT: ByteArray =
        byteArrayOf(
            0xBF.toByte(),
            0x9D.toByte(),
            0x2A.toByte(),
            0x53.toByte(),
            0xC6.toByte(),
            0x36.toByte(),
            0x16.toByte(),
            0xD7.toByte(),
            0x5D.toByte(),
            0xB0.toByte(),
            0xA7.toByte(),
            0x16.toByte(),
            0x5B.toByte(),
            0x91.toByte(),
            0xC1.toByte(),
            0xEF.toByte(),
            0x73.toByte(),
            0xE5.toByte(),
            0x37.toByte(),
            0xF2.toByte(),
            0x42.toByte(),
            0x74.toByte(),
            0x05.toByte(),
            0xFA.toByte(),
            0x23.toByte(),
            0x61.toByte(),
            0x0A.toByte(),
            0x4B.toByte(),
            0xE6.toByte(),
            0x57.toByte(),
            0x64.toByte(),
            0x2E.toByte(),
        )

    /** HKDF info for the per-direction `d2dClientKey` derivation. */
    internal val D2D_CLIENT_INFO: ByteArray = "client".toByteArray(StandardCharsets.US_ASCII)

    /** HKDF info for the per-direction `d2dServerKey` derivation. */
    internal val D2D_SERVER_INFO: ByteArray = "server".toByteArray(StandardCharsets.US_ASCII)

    /**
     * HKDF info for the SecureMessage encrypt key. The `:2` suffix signals
     * SecureMessage encryption scheme version 2 (AES-256-CBC), which is the
     * only scheme Quick Share negotiates. Treat this constant as opaque — its
     * value is part of the wire-visible derivation and cannot be changed.
     */
    internal val SM_ENC_INFO: ByteArray = "ENC:2".toByteArray(StandardCharsets.US_ASCII)

    /**
     * HKDF info for the SecureMessage HMAC key. The `:1` suffix signals
     * SecureMessage signature scheme version 1 (HMAC-SHA256), again the only
     * value Quick Share uses.
     */
    internal val SM_SIG_INFO: ByteArray = "SIG:1".toByteArray(StandardCharsets.US_ASCII)

    /**
     * Performs the full three-stage derivation and returns a [D2DSessionKeys]
     * bundle ready to drive a SecureMessage transport.
     *
     * @param dhs `SHA-256(ECDH-shared-secret-X-magnitude)` — exactly 32 bytes.
     *   Sourced from `Ukey2HandshakeResult.dhs`.
     * @param ukeyClientInitMsg Raw serialized `Ukey2Message` bytes for the
     *   ClientInit frame (NOT length-prefixed and NOT the inner
     *   `Ukey2ClientInit` payload). Sourced from
     *   `Ukey2HandshakeResult.clientInitMsg`.
     * @param ukeyServerInitMsg Raw serialized `Ukey2Message` bytes for the
     *   ServerInit frame. Sourced from `Ukey2HandshakeResult.serverInitMsg`.
     * @param role The local role on this connection — selects which pair of
     *   keys is used for sending vs. receiving in [D2DSessionKeys.forRole].
     * @return A fully populated [D2DSessionKeys] with [authString],
     *   [D2DSessionKeys.nextSecret], the two D2D master keys, all four
     *   SecureMessage keys, and the resolved local role.
     * @throws IllegalArgumentException if `dhs.size != 32`.
     */
    public fun derive(
        dhs: ByteArray,
        ukeyClientInitMsg: ByteArray,
        ukeyServerInitMsg: ByteArray,
        role: D2DRole,
    ): D2DSessionKeys {
        require(dhs.size == KEY_SIZE) {
            "dhs must be exactly $KEY_SIZE bytes (SHA-256 output), got ${dhs.size}"
        }

        // Stage 1 — UKEY2 layer.
        val ukeyInfo = concat(ukeyClientInitMsg, ukeyServerInitMsg)
        val authString = Hkdf.derive(ikm = dhs, salt = UKEY2_AUTH_SALT, info = ukeyInfo, length = KEY_SIZE)
        val nextSecret = Hkdf.derive(ikm = dhs, salt = UKEY2_NEXT_SALT, info = ukeyInfo, length = KEY_SIZE)

        // Stage 2 — D2D layer.
        val d2dClientKey =
            Hkdf.derive(ikm = nextSecret, salt = D2D_SALT, info = D2D_CLIENT_INFO, length = KEY_SIZE)
        val d2dServerKey =
            Hkdf.derive(ikm = nextSecret, salt = D2D_SALT, info = D2D_SERVER_INFO, length = KEY_SIZE)

        // Stage 3 — SecureMessage layer (four keys: enc + HMAC, per direction).
        val clientEncryptKey =
            Hkdf.derive(ikm = d2dClientKey, salt = SECURE_MESSAGE_SALT, info = SM_ENC_INFO, length = KEY_SIZE)
        val clientHmacKey =
            Hkdf.derive(ikm = d2dClientKey, salt = SECURE_MESSAGE_SALT, info = SM_SIG_INFO, length = KEY_SIZE)
        val serverEncryptKey =
            Hkdf.derive(ikm = d2dServerKey, salt = SECURE_MESSAGE_SALT, info = SM_ENC_INFO, length = KEY_SIZE)
        val serverHmacKey =
            Hkdf.derive(ikm = d2dServerKey, salt = SECURE_MESSAGE_SALT, info = SM_SIG_INFO, length = KEY_SIZE)

        return D2DSessionKeys(
            role = role,
            authString = authString,
            nextSecret = nextSecret,
            d2dClientKey = d2dClientKey,
            d2dServerKey = d2dServerKey,
            clientEncryptKey = clientEncryptKey,
            clientHmacKey = clientHmacKey,
            serverEncryptKey = serverEncryptKey,
            serverHmacKey = serverHmacKey,
        )
    }

    /**
     * Concatenates two byte arrays into a freshly allocated buffer.
     *
     * Inlined here (rather than calling `a + b`) so that the allocation is
     * obvious in the derivation flow and so we never accidentally rely on a
     * Kotlin operator that copies through an intermediate `List`.
     */
    private fun concat(
        a: ByteArray,
        b: ByteArray,
    ): ByteArray {
        val out = ByteArray(a.size + b.size)
        a.copyInto(out, destinationOffset = 0)
        b.copyInto(out, destinationOffset = a.size)
        return out
    }

    /**
     * Returns `SHA-256(input)`. Used only by tests to recompute [D2D_SALT] and
     * [SECURE_MESSAGE_SALT] from their string preimages and assert they match
     * the hard-coded byte arrays.
     */
    internal fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)
}

/**
 * Local role on a Quick Share connection.
 *
 * Determines which of the two encrypt/HMAC key pairs is used for sending vs.
 * receiving on this side of the connection. The mapping is:
 *
 *  - [SERVER] — sends with `server*` keys, receives with `client*` keys.
 *  - [CLIENT] — sends with `client*` keys, receives with `server*` keys.
 *
 * Sender of the UKEY2 ClientInit is the `CLIENT`; the responder is the
 * `SERVER`. These roles are independent of TCP listen/connect direction —
 * it is the UKEY2 message ordering that fixes the role.
 */
public enum class D2DRole {
    /** Local side initiated the UKEY2 handshake (sent ClientInit). */
    CLIENT,

    /** Local side responded to the UKEY2 handshake (sent ServerInit). */
    SERVER,
}

/**
 * Bundle of every key produced by [D2DKeyDerivation.derive] plus enough chain
 * context to validate the derivation.
 *
 * Intentionally a plain `class` rather than a `data class`: the auto-generated
 * `equals`/`hashCode` for `ByteArray` would use reference identity (a known
 * JVM gotcha), and we do not need destructuring or `copy()` on key material
 * (in fact, exposing `copy()` would tempt callers to mutate one field while
 * leaving the chain inconsistent).
 *
 * @property role Local role this bundle was derived for. Selects the
 *   per-direction send/receive split via [forRole].
 * @property authString HKDF output `(salt = "UKEY2 v1 auth")` over the
 *   ECDH-derived `dhs` and concatenated UKEY2 init messages. 32 bytes.
 *   Consumed by issue #12 to drive the PIN-code derivation; exposed here so
 *   callers do not have to re-run the UKEY2 stage.
 * @property nextSecret HKDF output `(salt = "UKEY2 v1 next")`. 32 bytes.
 *   Intermediate IKM for the D2D layer; exposed for testing/diagnostics.
 * @property d2dClientKey D2D master key for the client direction. 32 bytes.
 *   Exposed so test code can verify the chain stage-by-stage.
 * @property d2dServerKey D2D master key for the server direction. 32 bytes.
 * @property clientEncryptKey AES-256 SecureMessage encrypt key for traffic
 *   sent by the CLIENT (i.e., decrypt key when reading on the server side).
 * @property clientHmacKey HMAC-SHA256 SecureMessage signature key for traffic
 *   sent by the CLIENT.
 * @property serverEncryptKey AES-256 SecureMessage encrypt key for traffic
 *   sent by the SERVER.
 * @property serverHmacKey HMAC-SHA256 SecureMessage signature key for traffic
 *   sent by the SERVER.
 */
@Suppress("LongParameterList") // One field per stage of the documented derivation chain is intentional.
public class D2DSessionKeys(
    public val role: D2DRole,
    public val authString: ByteArray,
    public val nextSecret: ByteArray,
    public val d2dClientKey: ByteArray,
    public val d2dServerKey: ByteArray,
    public val clientEncryptKey: ByteArray,
    public val clientHmacKey: ByteArray,
    public val serverEncryptKey: ByteArray,
    public val serverHmacKey: ByteArray,
) {
    init {
        require(authString.size == D2DKeyDerivation.KEY_SIZE) {
            "authString must be ${D2DKeyDerivation.KEY_SIZE} bytes, got ${authString.size}"
        }
        require(nextSecret.size == D2DKeyDerivation.KEY_SIZE) {
            "nextSecret must be ${D2DKeyDerivation.KEY_SIZE} bytes, got ${nextSecret.size}"
        }
        require(d2dClientKey.size == D2DKeyDerivation.KEY_SIZE) {
            "d2dClientKey must be ${D2DKeyDerivation.KEY_SIZE} bytes, got ${d2dClientKey.size}"
        }
        require(d2dServerKey.size == D2DKeyDerivation.KEY_SIZE) {
            "d2dServerKey must be ${D2DKeyDerivation.KEY_SIZE} bytes, got ${d2dServerKey.size}"
        }
        require(clientEncryptKey.size == D2DKeyDerivation.KEY_SIZE) {
            "clientEncryptKey must be ${D2DKeyDerivation.KEY_SIZE} bytes, got ${clientEncryptKey.size}"
        }
        require(clientHmacKey.size == D2DKeyDerivation.KEY_SIZE) {
            "clientHmacKey must be ${D2DKeyDerivation.KEY_SIZE} bytes, got ${clientHmacKey.size}"
        }
        require(serverEncryptKey.size == D2DKeyDerivation.KEY_SIZE) {
            "serverEncryptKey must be ${D2DKeyDerivation.KEY_SIZE} bytes, got ${serverEncryptKey.size}"
        }
        require(serverHmacKey.size == D2DKeyDerivation.KEY_SIZE) {
            "serverHmacKey must be ${D2DKeyDerivation.KEY_SIZE} bytes, got ${serverHmacKey.size}"
        }
    }

    /**
     * Resolves the per-direction `(send, receive)` key pair for this side of
     * the connection.
     *
     * This selector is the one and only place the role swap happens — every
     * SecureMessage transmit/receive path should go through here so the
     * client-vs-server mapping cannot drift between modules.
     */
    public fun forRole(): DirectionalKeys =
        when (role) {
            D2DRole.CLIENT ->
                DirectionalKeys(
                    sendEncryptKey = clientEncryptKey,
                    sendHmacKey = clientHmacKey,
                    receiveEncryptKey = serverEncryptKey,
                    receiveHmacKey = serverHmacKey,
                )

            D2DRole.SERVER ->
                DirectionalKeys(
                    sendEncryptKey = serverEncryptKey,
                    sendHmacKey = serverHmacKey,
                    receiveEncryptKey = clientEncryptKey,
                    receiveHmacKey = clientHmacKey,
                )
        }

    /** Deliberately not implemented — would compare key material via
     *  reference identity. Use field-level `contentEquals` in tests instead.
     */
    override fun equals(other: Any?): Boolean = this === other

    /** See [equals]. */
    override fun hashCode(): Int = System.identityHashCode(this)

    /**
     * `toString` does **not** dump key material. Returns only the role and a
     * marker so logs that accidentally include this object cannot leak keys.
     */
    override fun toString(): String = "D2DSessionKeys(role=$role, keys=<redacted>)"
}

/**
 * Per-direction `(send, receive)` SecureMessage key pair as resolved by
 * [D2DSessionKeys.forRole].
 *
 * Each field is exactly [D2DKeyDerivation.KEY_SIZE] bytes.
 *
 * @property sendEncryptKey AES-256 key used to encrypt outgoing payloads.
 * @property sendHmacKey HMAC-SHA256 key used to sign outgoing payloads.
 * @property receiveEncryptKey AES-256 key used to decrypt incoming payloads.
 * @property receiveHmacKey HMAC-SHA256 key used to verify incoming payloads.
 */
public class DirectionalKeys(
    public val sendEncryptKey: ByteArray,
    public val sendHmacKey: ByteArray,
    public val receiveEncryptKey: ByteArray,
    public val receiveHmacKey: ByteArray,
) {
    /** Reference-identity equality — see [D2DSessionKeys.equals]. */
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "DirectionalKeys(<redacted>)"
}
