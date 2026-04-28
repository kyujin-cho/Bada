/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.qr

import io.github.kyujincho.wvmg.protocol.crypto.Hkdf
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Derives the two HKDF-SHA256 keys that the Quick Share QR-code path uses.
 *
 * Per PROTOCOL.md "QR codes":
 *
 * ```
 * advertisingToken    = HKDF-SHA256(ikm=keyData, salt="", info="advertisingContext", L=16)
 * nameEncryptionKey   = HKDF-SHA256(ikm=keyData, salt="", info="encryptionKey",      L=16)
 * ```
 *
 * Both info strings are ASCII and are deliberately encoded with `US-ASCII`
 * rather than the platform default to keep the byte representation stable
 * across JVMs and Android API levels (the values fit in ASCII so UTF-8 vs.
 * US-ASCII produces identical bytes; using US-ASCII makes the intent obvious
 * and rules out a future regression if a maintainer ever reuses these
 * constants for something containing non-ASCII characters).
 *
 * The salt is **always empty** for the QR-code path; per RFC 5869 §2.2 an
 * empty salt is replaced internally with `HashLen` (= 32) zero bytes. This is
 * not a property we choose — it's mandated by the protocol — and changing it
 * would break interop with every Quick Share peer in the wild.
 *
 * Sensitive data discipline: never log [QrKeyData] bytes, the derived keys,
 * or the IKM used here. The receiver's name-encryption key in particular
 * gates AAD-bound AES-GCM decryption of the device name; leaking it lets a
 * passive attacker recover the (potentially personal) device name.
 */
public object QrKeyDerivation {
    /** Length of each derived key in bytes (16 = 128 bits, AES-128 sized). */
    public const val DERIVED_KEY_LEN: Int = 16

    /** HKDF info string for the advertising token. */
    private const val ADVERTISING_INFO: String = "advertisingContext"

    /** HKDF info string for the name encryption key. */
    private const val NAME_ENCRYPTION_INFO: String = "encryptionKey"

    /** Cached UTF-8/ASCII bytes of [ADVERTISING_INFO]; the value is interop-critical. */
    internal val ADVERTISING_INFO_BYTES: ByteArray =
        ADVERTISING_INFO.toByteArray(StandardCharsets.US_ASCII)

    /** Cached UTF-8/ASCII bytes of [NAME_ENCRYPTION_INFO]; the value is interop-critical. */
    internal val NAME_ENCRYPTION_INFO_BYTES: ByteArray =
        NAME_ENCRYPTION_INFO.toByteArray(StandardCharsets.US_ASCII)

    /**
     * Derives the advertising token used both as the visible-mode TLV value
     * and as the AAD for hidden-mode AES-GCM encryption of the device name.
     */
    public fun deriveAdvertisingToken(keyData: QrKeyData): ByteArray =
        Hkdf.derive(
            ikm = keyData.encode(),
            salt = ByteArray(0),
            info = ADVERTISING_INFO_BYTES,
            length = DERIVED_KEY_LEN,
        )

    /**
     * Derives the AES-128-GCM key used to encrypt/decrypt the device name in
     * hidden mode. Authenticated against the advertising token (passed as
     * AAD), so possession of this key alone is insufficient to decrypt — an
     * attacker also needs the matching advertising token, which is itself
     * derived from the same QR payload.
     */
    public fun deriveNameEncryptionKey(keyData: QrKeyData): ByteArray =
        Hkdf.derive(
            ikm = keyData.encode(),
            salt = ByteArray(0),
            info = NAME_ENCRYPTION_INFO_BYTES,
            length = DERIVED_KEY_LEN,
        )

    /**
     * Convenience helper that derives both keys in one call. Returns a pair
     * of `(advertisingToken, nameEncryptionKey)` — both freshly allocated,
     * exactly [DERIVED_KEY_LEN] bytes each.
     *
     * Encodes [keyData] once and reuses the same IKM buffer for both HKDF
     * calls. The two derivations share a PRK in principle (Extract is the
     * same since both calls use the same `(salt, ikm)`), but our [Hkdf]
     * helper exposes Extract as `internal` and the public surface accepts
     * `(ikm, salt, info, length)` quadruples. The micro-optimization of
     * sharing the PRK across two info strings is left for later if it ever
     * shows up in a profile; correctness and clarity win for now.
     */
    public fun deriveKeys(keyData: QrKeyData): DerivedQrKeys {
        val ikm = keyData.encode()
        return DerivedQrKeys(
            advertisingToken =
                Hkdf.derive(
                    ikm = ikm,
                    salt = ByteArray(0),
                    info = ADVERTISING_INFO_BYTES,
                    length = DERIVED_KEY_LEN,
                ),
            nameEncryptionKey =
                Hkdf.derive(
                    ikm = ikm,
                    salt = ByteArray(0),
                    info = NAME_ENCRYPTION_INFO_BYTES,
                    length = DERIVED_KEY_LEN,
                ),
        )
    }
}

/**
 * The two HKDF-derived keys that gate the QR-code matching protocol.
 *
 * Both arrays are exactly [QrKeyDerivation.DERIVED_KEY_LEN] bytes. Equality
 * is value-based on array contents so KAT tests can compare instances with
 * plain `assertEquals`.
 *
 * **Constant-time comparison.** Both fields hold secret HKDF output —
 * [advertisingToken] is the AES-GCM AAD that gates hidden-mode device-name
 * decryption, and [nameEncryptionKey] is the AES-128 key itself. Using
 * `ByteArray.contentEquals` here would short-circuit on the first differing
 * byte and leak a per-byte timing oracle to any caller that can observe
 * comparison latency. We use [MessageDigest.isEqual] instead, which performs
 * a length check followed by an XOR-then-OR loop in time independent of the
 * input contents (since OpenJDK 7u40+ / every Android API level we ship to).
 * Yes, in practice `equals` here is invoked only by KAT tests, but the
 * policy in `:core-protocol` is uniform: never use `contentEquals` on bytes
 * derived from secrets, full stop. See `:core-protocol/README.md` for the
 * project-wide rule.
 *
 * @property advertisingToken 16-byte raw value advertised by the receiver in
 *   visible mode (TLV type=1) and used as AES-GCM AAD in hidden mode.
 * @property nameEncryptionKey 16-byte AES-128 key used to encrypt the device
 *   name in hidden mode.
 */
public class DerivedQrKeys(
    public val advertisingToken: ByteArray,
    public val nameEncryptionKey: ByteArray,
) {
    init {
        require(advertisingToken.size == QrKeyDerivation.DERIVED_KEY_LEN) {
            "advertisingToken must be ${QrKeyDerivation.DERIVED_KEY_LEN} bytes"
        }
        require(nameEncryptionKey.size == QrKeyDerivation.DERIVED_KEY_LEN) {
            "nameEncryptionKey must be ${QrKeyDerivation.DERIVED_KEY_LEN} bytes"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DerivedQrKeys) return false
        // Constant-time compare on both fields: see class KDoc.
        return MessageDigest.isEqual(advertisingToken, other.advertisingToken) &&
            MessageDigest.isEqual(nameEncryptionKey, other.nameEncryptionKey)
    }

    override fun hashCode(): Int = 31 * advertisingToken.contentHashCode() + nameEncryptionKey.contentHashCode()
}
