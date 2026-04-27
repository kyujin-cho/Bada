// :core-protocol-test — small JVM library that exposes shared test fixtures
// and KAT (Known-Answer Test) vectors for protocol code. Lives in its own
// module so both :core-protocol's JVM tests AND future Android
// instrumentation tests (#27, #28) can consume the same fixtures.

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(libs.junit.jupiter.api)
    api(libs.truth)
    // No dependency on :core-protocol itself — fixtures must stay free of
    // implementation details to remain reusable from instrumentation tests.
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
