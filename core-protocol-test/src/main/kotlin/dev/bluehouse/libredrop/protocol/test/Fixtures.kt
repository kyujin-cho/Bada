/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.test

/**
 * Shared test fixtures and Known-Answer Test (KAT) vectors for the Quick
 * Share protocol. Real vectors land alongside the algorithm implementations
 * (HKDF in #9, UKEY2 in #10, SecureMessage in #13, PIN derivation in #12,
 * etc.); this placeholder keeps the module's surface area non-empty while
 * the rest of Phase 1 is built out.
 */
public object Fixtures {
    /** Marker so callers can confirm the fixtures module is on the classpath. */
    public const val MODULE_NAME: String = "core-protocol-test"
}
