// :core-protocol — pure Kotlin/JVM module containing the Quick Share
// protocol implementation. By design this module has NO `android.*`
// dependencies so it can be unit-tested on a plain JVM (critical for the
// hundreds of cryptographic edge cases we'll need to cover).

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
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

// Protobuf codegen. The seven Quick Share `.proto` files live under
// `src/main/proto/` (vendored verbatim from the NearDrop reference
// implementation in #6) and are compiled into Java classes by `protoc`.
//
// We deliberately use the **javalite** runtime instead of the full
// `protobuf-java`: javalite's generated code is far smaller, has a tiny
// runtime footprint, and is the variant Google itself targets at Android.
// To make `protoc` emit lite-compatible code, the `java` builtin is
// configured with the `lite` option below; without it, generated classes
// would extend `GeneratedMessageV3` (full runtime only) and fail at link
// time against `protobuf-javalite`.
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().configureEach {
            // The `java` builtin is registered by the plugin by default, so
            // we resolve and re-configure it (rather than `id("java")`-add it
            // again, which would fail with "already exists"). The `lite`
            // option flips protoc into javalite mode, which emits
            // `GeneratedMessageLite` subclasses paired with
            // `protobuf-javalite` instead of the full `protobuf-java`
            // runtime.
            builtins.named("java") {
                option("lite")
            }
        }
    }
}

// Generated Java sources need to be visible to the Kotlin compiler so that
// Kotlin code in the same module (and downstream consumers) can reference
// the generated message classes directly. The Gradle `protobuf` plugin
// already wires the generated dirs into the `main` Java source set; the
// block below makes the same wiring explicit for Kotlin's compileKotlin
// task to keep IDE indexing and incremental builds reliable.
tasks.named("compileKotlin") {
    dependsOn("generateProto")
}

tasks.named("compileTestKotlin") {
    dependsOn("generateTestProto")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
