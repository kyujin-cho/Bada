/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.crypto.kat

import com.google.common.truth.Truth.assertWithMessage
import io.github.kyujincho.wvmg.protocol.test.HmacSha256Vectors
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Smoke test for the JVM's `javax.crypto.Mac("HmacSHA256")` primitive against
 * RFC 4231's published Known-Answer Test (KAT) vectors.
 *
 * Quick Share never ships a custom HMAC implementation: every signing and
 * verification call goes through JCE. Locking the JCE primitive against
 * RFC 4231 here means a future provider swap, a misconfigured JCA algorithm
 * string, or a typo that switched HMAC to a different hash will surface here
 * before it can leak into the SecureMessage signing path covered by #13.
 *
 * Vectors live in `:core-protocol-test` ([HmacSha256Vectors]) so the same
 * answer-table can be reused from Android instrumentation tests when the
 * protocol stack lights up later in Phase 1.
 */
class HmacSha256KatTest {
    /**
     * Runs every RFC 4231 vector as its own JUnit Jupiter dynamic test so
     * the failure report calls out exactly which vector mismatched. The
     * static-method-name fallback ("rfc 4231 vector test") would obscure
     * which case fired.
     */
    @TestFactory
    fun `RFC 4231 vectors match javax-crypto Mac HmacSHA256 output`(): List<DynamicTest> =
        HmacSha256Vectors.all.map { vector ->
            DynamicTest.dynamicTest(vector.name) {
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(vector.key, "HmacSHA256"))
                val actual = mac.doFinal(vector.data)
                assertWithMessage("HMAC-SHA256 mismatch for ${vector.name}")
                    .that(actual)
                    .isEqualTo(vector.expectedTag)
            }
        }
}
