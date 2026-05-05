/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.ukey2

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.EcP256PublicKey
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.GenericPublicKey
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.PublicKeyType
import dev.bluehouse.libredrop.protocol.ukey2.Ukey2.P256_COORDINATE_SIZE
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.InvalidKeySpecException

/**
 * Encoding/decoding helpers for the `GenericPublicKey` wrapper that UKEY2
 * uses to ferry NIST P-256 public keys on the wire.
 *
 * The wire format is a `securemessage.GenericPublicKey` with `type =
 * EC_P256` and an inner `EcP256PublicKey { x, y }`. Both `x` and `y` are
 * documented in `securemessage.proto` as "big-endian two's complement
 * (slightly wasteful)" — i.e., signed bytes, which means a value whose
 * high bit is set is prepended with a `0x00` sign byte and runs to 33
 * bytes, while a value smaller than 2^248 may be shorter than 32.
 *
 * In practice, **Quick Share peers in the wild are not consistent**: some
 * always pad to exactly 32 bytes, some (notably NearDrop and the macOS
 * macOS Quick Share clients on certain SDK versions) emit the
 * `BigInteger.toByteArray()` form which can be 31, 32, or 33 bytes. This
 * is a well-known interop gotcha — NearDrop's
 * `if clientX.count > 32 { clientX = clientX.suffix(32) }` exists for
 * exactly this reason. We therefore:
 *
 *  - **On parse**: accept any length up to 33 bytes; left-pad short values
 *    with zeros, drop the leading sign byte from oversized values
 *    (`suffix(32)` semantics), then validate that the result is a valid
 *    point on the curve via JCE's `KeyFactory.generatePublic()`. Lengths
 *    outside `1..33` and values that fail point validation surface as
 *    [Ukey2HandshakeException] with [Ukey2AlertType.BAD_PUBLIC_KEY].
 *
 *  - **On encode**: take the magnitude bytes from `BigInteger.toByteArray()`
 *    and **always normalize to exactly 32 bytes** by padding or by
 *    stripping a leading sign byte. Emitting non-32-byte coordinates
 *    happens to be tolerated by NearDrop and Quick Share clients we've
 *    tested, but emitting the canonical 32-byte form maximizes
 *    interoperability and minimizes the chance of bug-for-bug parity
 *    with a misbehaving peer.
 *
 * This object is an internal implementation detail of the UKEY2 module;
 * it is `internal` so the helpers don't leak into `:core-protocol`'s
 * `explicitApi` surface, but [serialize] / [parse] are exposed via the
 * `Ukey2Client` / `Ukey2Server` public APIs.
 */
internal object Ukey2KeyEncoding {
    /**
     * Encodes a JCE [ECPublicKey] into the canonical `GenericPublicKey`
     * wire format used by UKEY2 and SecureMessage.
     */
    fun serialize(publicKey: ECPublicKey): ByteArray {
        val w = publicKey.w
        val x = encodeP256Coordinate(w.affineX)
        val y = encodeP256Coordinate(w.affineY)
        val ecKey =
            EcP256PublicKey
                .newBuilder()
                .setX(ByteString.copyFrom(x))
                .setY(ByteString.copyFrom(y))
                .build()
        return GenericPublicKey
            .newBuilder()
            .setType(PublicKeyType.EC_P256)
            .setEcP256PublicKey(ecKey)
            .build()
            .toByteArray()
    }

    /**
     * Parses a `GenericPublicKey` wire blob into a JCE [ECPublicKey],
     * validating that:
     *
     *  1. The protobuf deserializes cleanly.
     *  2. `type == EC_P256` and an `ec_p256_public_key` is present.
     *  3. `x` and `y` are between 1 and 33 bytes (inclusive).
     *  4. The resulting point lies on the P-256 curve and is not the
     *     point at infinity.
     *
     * On any failure the method throws [Ukey2HandshakeException] with
     * [Ukey2AlertType.BAD_PUBLIC_KEY] so callers can emit the correct
     * `Ukey2Alert` and tear down the connection.
     *
     * **Why an explicit on-curve check?** SunEC's
     * `KeyFactory.generatePublic()` does **not** validate that the
     * supplied (x, y) pair actually satisfies the curve equation — it
     * only checks coordinate ranges. Skipping curve validation enables
     * the classic *invalid-curve attack*: an attacker submits a point
     * that lies on a different (weaker) curve, the ECDH operation runs
     * scalar multiplication anyway, and the resulting "shared secret"
     * leaks bits of the local private key. We therefore re-validate
     * `y^2 ≡ x^3 + ax + b (mod p)` ourselves before trusting the point
     * for any subsequent ECDH.
     */
    @Suppress("ReturnCount", "ThrowsCount")
    fun parse(genericPublicKeyBytes: ByteArray): ECPublicKey {
        val genericKey =
            try {
                GenericPublicKey.parseFrom(genericPublicKeyBytes)
            } catch (ex: InvalidProtocolBufferException) {
                throw Ukey2HandshakeException(
                    alert = Ukey2AlertType.BAD_PUBLIC_KEY,
                    message = "Could not deserialize GenericPublicKey",
                    cause = ex,
                )
            }

        if (genericKey.type != PublicKeyType.EC_P256 || !genericKey.hasEcP256PublicKey()) {
            throw Ukey2HandshakeException(
                alert = Ukey2AlertType.BAD_PUBLIC_KEY,
                message = "GenericPublicKey type is not EC_P256",
            )
        }

        val ecKey = genericKey.ecP256PublicKey
        val xBytes = normalizeCoordinate(ecKey.x.toByteArray())
        val yBytes = normalizeCoordinate(ecKey.y.toByteArray())

        // BigInteger(1, ...) explicitly forces a positive value regardless
        // of the high bit; the wire format is unsigned magnitude even
        // though the field is documented as two's complement.
        val x = BigInteger(1, xBytes)
        val y = BigInteger(1, yBytes)

        val params = p256Parameters()
        validatePointOnCurve(x, y, params)

        val point = ECPoint(x, y)
        return try {
            val spec = ECPublicKeySpec(point, params)
            val keyFactory = KeyFactory.getInstance("EC")
            val candidate = keyFactory.generatePublic(spec) as PublicKey
            candidate as ECPublicKey
        } catch (ex: InvalidKeySpecException) {
            throw Ukey2HandshakeException(
                alert = Ukey2AlertType.BAD_PUBLIC_KEY,
                message = "Peer GenericPublicKey is not a valid P-256 point",
                cause = ex,
            )
        } catch (ex: IllegalArgumentException) {
            // Thrown by ECPoint(...) if either coordinate is negative,
            // which cannot happen via BigInteger(1, ...) — kept for
            // defense-in-depth in case a future refactor reintroduces
            // signed parsing.
            throw Ukey2HandshakeException(
                alert = Ukey2AlertType.BAD_PUBLIC_KEY,
                message = "Peer EC coordinates are not in valid range",
                cause = ex,
            )
        }
    }

    /**
     * Verifies that `(x, y)` is a non-identity point on the curve
     * defined by [params]. Throws [Ukey2HandshakeException] with
     * [Ukey2AlertType.BAD_PUBLIC_KEY] if the check fails.
     *
     * Implements three guards from SEC1 §3.2.2 ("Public Key
     * Validation"):
     *
     *  - **Identity**: `(x, y)` is not the point at infinity. The JCE
     *    represents the identity as `ECPoint.POINT_INFINITY`; callers
     *    that synthesize one via `BigInteger(1, ...)` will instead pass
     *    `(0, 0)` or similar, which is rejected by the curve equation
     *    on P-256 anyway. We still guard explicitly to be safe.
     *
     *  - **Range**: `0 <= x < p` and `0 <= y < p`. JCE accepts negative
     *    or oversized coordinates without complaint, so we range-check
     *    against the field prime ourselves.
     *
     *  - **Curve equation**: `y^2 mod p == (x^3 + a*x + b) mod p`.
     *    This is the actual on-curve test that defends against
     *    invalid-curve attacks during the subsequent ECDH.
     *
     * Cofactor checks (group-order multiplication) are unnecessary for
     * P-256 because its cofactor is 1 — every on-curve point is in the
     * full group.
     */
    @Suppress("ReturnCount", "ThrowsCount")
    private fun validatePointOnCurve(
        x: BigInteger,
        y: BigInteger,
        params: java.security.spec.ECParameterSpec,
    ) {
        val curve = params.curve
        val field =
            curve.field as? ECFieldFp
                ?: throw Ukey2HandshakeException(
                    alert = Ukey2AlertType.BAD_PUBLIC_KEY,
                    message = "Curve field is not a prime-order Fp field; cannot validate P-256",
                )
        val p = field.p
        if (!isInFieldRange(x, p) || !isInFieldRange(y, p)) {
            throw Ukey2HandshakeException(
                alert = Ukey2AlertType.BAD_PUBLIC_KEY,
                message = "Peer EC coordinates are out of field range [0, p)",
            )
        }

        // y^2 mod p
        val lhs = y.modPow(BIG_INTEGER_TWO, p)
        // x^3 + a*x + b mod p
        val rhs =
            x
                .modPow(BigInteger.valueOf(CURVE_EQ_X_EXPONENT), p)
                .add(curve.a.multiply(x))
                .add(curve.b)
                .mod(p)
        if (lhs != rhs) {
            throw Ukey2HandshakeException(
                alert = Ukey2AlertType.BAD_PUBLIC_KEY,
                message = "Peer EC point is not on the P-256 curve",
            )
        }
    }

    /**
     * Pads or truncates [raw] to exactly [P256_COORDINATE_SIZE] bytes,
     * matching the NearDrop interop quirk:
     *
     *  - Length `<= 32`: left-pad with zeros.
     *  - Length `33`: drop the leading byte (assumed to be the
     *    `BigInteger.toByteArray()` sign byte). NearDrop uses
     *    `suffix(32)`, which is the same operation.
     *
     * Lengths outside `1..33` are rejected with
     * [Ukey2HandshakeException] / [Ukey2AlertType.BAD_PUBLIC_KEY] —
     * legitimate P-256 magnitudes never exceed 33 bytes, so anything
     * larger is malicious or a misformat.
     */
    private fun normalizeCoordinate(raw: ByteArray): ByteArray {
        if (raw.isEmpty() || raw.size > P256_COORDINATE_SIZE + 1) {
            throw Ukey2HandshakeException(
                alert = Ukey2AlertType.BAD_PUBLIC_KEY,
                message = "EC coordinate length ${raw.size} is outside 1..${P256_COORDINATE_SIZE + 1}",
            )
        }
        return when {
            raw.size == P256_COORDINATE_SIZE -> raw
            raw.size == P256_COORDINATE_SIZE + 1 -> raw.copyOfRange(1, raw.size)
            else -> {
                // Left-pad with zeros: the value is shorter than the
                // curve byte length, so its high bytes are implicit zero.
                val padded = ByteArray(P256_COORDINATE_SIZE)
                raw.copyInto(padded, destinationOffset = P256_COORDINATE_SIZE - raw.size)
                padded
            }
        }
    }

    /**
     * Converts a non-negative [java.math.BigInteger] coordinate to its
     * canonical 32-byte unsigned big-endian representation.
     *
     * `BigInteger.toByteArray()` returns the **signed** two's complement
     * form, which means:
     *
     *  - For values whose magnitude fits in `< 256` bits and whose top
     *    bit is `0`, the result is already 1..32 bytes — left-pad with
     *    zeros to 32 bytes.
     *  - For values whose magnitude has the top bit of the 256-bit
     *    representation set (i.e., `>= 2^255`), `toByteArray()` returns
     *    33 bytes with a leading `0x00` sign byte. **Keep the sign
     *    byte** — Samsung One UI 8.0.5's GMS Nearby parses these bytes
     *    via `new BigInteger(byte[])` (signed two's complement) and
     *    rejects MSB-set 32-byte input as a negative integer with
     *    `Point encoding must use only non-negative integers`. The
     *    leading `0x00` makes the encoding unambiguously non-negative
     *    under either parsing convention (signed two's complement or
     *    `BigInteger(1, bytes)` unsigned magnitude). Verified on-device
     *    against a Galaxy S24 Ultra: ~50% of P-256 keypairs trigger the
     *    bug (top-bit-set is uniformly random), exactly matching the
     *    "intermittent UKEY2 silent FIN" symptom we'd been chasing.
     *
     * Output is therefore variable length: typically 32 bytes,
     * occasionally 33 bytes (when MSB=1). The proto field is `bytes`,
     * so receivers that parse via `BigInteger(1, bytes)` see the
     * correct magnitude either way.
     */
    private fun encodeP256Coordinate(coordinate: BigInteger): ByteArray {
        require(coordinate.signum() >= 0) {
            // Public-key coordinates from a real P-256 keypair are always
            // in [0, p), so this is a sanity check, not an interop guard.
            "Coordinate must be non-negative, got $coordinate"
        }
        val raw = coordinate.toByteArray()
        return when {
            // 32 bytes: top bit is 0, so the encoding is already
            // unambiguously non-negative under any parser.
            raw.size == P256_COORDINATE_SIZE -> raw
            // 33 bytes: leading 0x00 sign byte + 32-byte magnitude. The
            // top bit of the magnitude is 1; KEEPING the sign byte is
            // what unblocks Samsung's strict-signed parser.
            raw.size == P256_COORDINATE_SIZE + 1 -> {
                check(raw[0] == 0.toByte()) {
                    "Unexpected sign byte 0x${raw[0].toInt().and(BYTE_MASK).toString(HEX_RADIX)} " +
                        "in non-negative coordinate"
                }
                raw
            }
            // Smaller than 32 bytes (small magnitude): left-pad with
            // zeros to a fixed 32-byte form. The top bit will be 0 by
            // construction, so no sign byte is needed.
            raw.size < P256_COORDINATE_SIZE -> {
                val padded = ByteArray(P256_COORDINATE_SIZE)
                raw.copyInto(padded, destinationOffset = P256_COORDINATE_SIZE - raw.size)
                padded
            }
            else ->
                error(
                    "P-256 coordinate exceeded ${P256_COORDINATE_SIZE + 1} bytes (${raw.size}); " +
                        "this should not happen for a valid private/public keypair on secp256r1",
                )
        }
    }

    private const val BYTE_MASK = 0xFF
    private const val HEX_RADIX = 16
    private val BIG_INTEGER_TWO: BigInteger = BigInteger.valueOf(2L)

    /** Exponent in the Weierstrass curve equation `y^2 = x^3 + a*x + b`. */
    private const val CURVE_EQ_X_EXPONENT: Long = 3

    /** Returns true iff `value` is in `[0, prime)` — the field range for Fp curves. */
    private fun isInFieldRange(
        value: BigInteger,
        prime: BigInteger,
    ): Boolean = value.signum() >= 0 && value < prime
}
