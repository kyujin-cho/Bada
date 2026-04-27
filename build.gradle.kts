// Top-level build file. Plugins are declared with `apply false` so each
// module can opt in via its own `plugins { ... }` block, and shared
// configuration (ktlint, detekt, JVM toolchain) is applied to every
// subproject below.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

// Capture version-catalog values once at the root scope so that the
// `subprojects { ... }` block below — which doesn't have access to the
// typesafe `libs` accessor — can still pin tool versions reproducibly.
val detektToolVersion = libs.versions.detekt.get()
val detektFormattingDep = libs.detekt.formatting

// Shared configuration applied to every subproject (modules under :app,
// :service-android, :discovery-android, :core-protocol, :core-protocol-test).
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    // ktlint configuration. Single source of truth for code style across modules.
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.4.1")
        android.set(true)
        ignoreFailures.set(false)
        reporters {
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
        }
        filter {
            // Never lint generated sources (e.g. protobuf javalite output from #6).
            exclude { element -> element.file.path.contains("generated/") }
            exclude("**/build/**")
        }
    }

    // detekt configuration. The shared config file lives at
    // config/detekt/detekt.yml and applies to every module uniformly.
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        toolVersion = detektToolVersion
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        allRules = false
        autoCorrect = false
        parallel = true
        ignoreFailures = false
    }

    dependencies {
        add("detektPlugins", detektFormattingDep)
    }
}

// Convenience aggregate task: `./gradlew check` already covers tests, lint,
// and detekt per module — this task makes the intent explicit at the root.
tasks.register("staticAnalysis") {
    group = "verification"
    description = "Runs ktlintCheck and detekt on every module."
    dependsOn(subprojects.map { "${it.path}:ktlintCheck" })
    dependsOn(subprojects.map { "${it.path}:detekt" })
}
