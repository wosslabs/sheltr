plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

// Android target is intentionally not declared yet: no Android SDK is installed
// on the build machine (ANDROID_HOME unset, no local.properties). Add
// androidTarget() with the Android library plugin once the SDK is available (Phase A).

repositories {
    mavenCentral()
}

kotlin {
    jvm()

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Same version as the publisher (tools/publisher/build.gradle.kts).
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            // Ed25519 verification of bundle.json.sig (security dependency; pin the exact version).
            // cryptography-kotlin 0.6.0, Apache-2.0, https://github.com/whyoleg/cryptography-kotlin
            // Wraps platform crypto, no hand-written curve arithmetic: the optimal provider uses the
            // JDK provider on jvm and CryptoKit (Ed25519 via Curve25519.Signing) on iosArm64/iosSimulatorArm64.
            implementation("dev.whyoleg.cryptography:cryptography-core:0.6.0")
            implementation("dev.whyoleg.cryptography:cryptography-provider-optimal:0.6.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
