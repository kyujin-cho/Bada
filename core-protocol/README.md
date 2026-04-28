# `:core-protocol`

Pure-Kotlin/JVM implementation of the Quick Share / Nearby Share wire stack.
Imports `android.*` are forbidden in this module — adding one is a regression
that the review must catch. Anything platform-specific belongs in
`:discovery-android`, `:service-android`, or `:app`.

The reading order for the protocol layers is documented in the project root
`CLAUDE.md` (see "Protocol layers (reading guide)"); the rest of this file
captures policies that are easy to forget when adding new code.

## Cryptographic comparison policy

**Never use `ByteArray.contentEquals`, `Arrays.equals`, `==`, or any other
short-circuiting equality on byte arrays that may be derived from secrets
or that are produced by a MAC / signature / commitment.**

Use `java.security.MessageDigest.isEqual(byte[], byte[])` instead. It is
documented to run in time independent of the input contents (a length
check followed by a constant-time XOR-then-OR loop, hardened against
timing attacks since OpenJDK 7u40+; every Android API level we ship to
includes the hardened implementation).

The rule applies to:

- HMAC tags (e.g. the `signature` field of a `SecureMessage`).
- Hash commitments (e.g. the SHA-512 cipher commitment in UKEY2's
  `ClientInit`).
- Authentication tokens (e.g. the QR-code advertising token in
  `QrTlvMatcher`).
- Any HKDF-derived key material being compared for equality (e.g. KAT
  tests on `DerivedQrKeys`).

Plain `contentEquals` is acceptable in two narrow cases, both of which
should carry a comment explaining why constant-time compare was not
necessary:

1. The bytes are **not** secret and **not** a MAC tag (TLV record values,
   discovery `endpoint_id`, received user-data payloads after they have
   been HMAC-verified upstream).
2. Equality is being used for diagnostics / `data class` semantics on a
   plaintext that the transport layer has already authenticated, and the
   secret material is not in scope.

When in doubt, default to `MessageDigest.isEqual`. The cost is negligible
and the failure mode of `contentEquals` is silent.

### Audit guardrail

`HmacComparisonAuditTest` (under
`src/test/kotlin/.../crypto/HmacComparisonAuditTest.kt`) walks the
`:core-protocol` Kotlin sources and asserts that the cryptographic compare
sites — HMAC verify, UKEY2 commitment compare, QR advertising-token
compare, and the `DerivedQrKeys` equals override — all use
`MessageDigest.isEqual` and that no `contentEquals` / `Arrays.equals`
appears within a few lines of an HMAC, signature, or MAC-tag identifier
in the production source set. New cryptographic comparisons should either
satisfy the audit or extend it.
