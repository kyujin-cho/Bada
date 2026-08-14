/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.update

/**
 * Single shared implementation of the release-version comparison used by
 * both the foreground red-dot surface ([UpdateRepository]) and the
 * background periodic poll ([UpdateCheckWorker]).
 */
internal object UpdateVersions {
    /**
     * Strict-greater comparison. Works for the project's fixed `YYYYMMDD.NN`
     * versionName scheme because every field is zero-padded to a fixed
     * width, so a plain lexicographic compare is monotonic with release
     * order — no Semver parsing required.
     */
    fun isNewer(
        candidate: String,
        installed: String,
    ): Boolean = candidate > installed
}
