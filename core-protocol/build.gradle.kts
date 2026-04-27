// :core-protocol — pure Kotlin/JVM module containing the Quick Share
// protocol implementation. By design this module has NO `android.*`
// dependencies so it can be unit-tested on a plain JVM (critical for the
// hundreds of cryptographic edge cases we'll need to cover).

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
    compilerOptions {
        // Treat all warnings as errors in protocol code; bit-exact correctness
        // matters more here than developer ergonomics.
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // Allowed dependencies for :core-protocol per issue #5:
    //   kotlinx.coroutines, protobuf-javalite, JCE/JDK, Tink (optional).
    // Adding anything `android.*` here is a regression — guard it in review.
    //
    // Tink is left out of the bootstrap dependency set on purpose: it ships
    // a transitive `com.google.protobuf:protobuf-java` that collides with
    // `protobuf-javalite` on Android. Issue #9 (HKDF) decides whether to
    // pull it in (with a targeted `protobuf-java` exclusion) or implement
    // HKDF directly on top of `javax.crypto.Mac`.
    api(libs.kotlinx.coroutines.core)
    api(libs.protobuf.javalite)

    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.jupiter.engine)

    // KAT vectors and shared fixtures live in :core-protocol-test so they can
    // also be reused by Android instrumentation tests later (#27, #28).
    testImplementation(project(":core-protocol-test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
