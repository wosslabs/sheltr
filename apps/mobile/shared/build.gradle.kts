plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    // SQLDelight 2.4.0 (Apache-2.0, https://github.com/sqldelight/sqldelight): on-device store with FTS5.
    // Keep the plugin and the driver artifacts below on the same version.
    id("app.cash.sqldelight") version "2.4.0"
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
            implementation("app.cash.sqldelight:runtime:2.4.0")
        }
        jvmMain.dependencies {
            // JDBC driver over xerial sqlite-jdbc (bundled SQLite, built with FTS5). Used by jvmTest and the eval runner.
            implementation("app.cash.sqldelight:sqlite-driver:2.4.0")
        }
        iosMain.dependencies {
            // Links the system libsqlite3 on iOS (FTS5 is enabled in the iOS system SQLite).
            implementation("app.cash.sqldelight:native-driver:2.4.0")
        }
        // Android: add app.cash.sqldelight:android-driver:2.4.0 to androidMain when androidTarget() is declared.
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

sqldelight {
    databases {
        create("SheltrDatabase") {
            packageName.set("com.sheltr.shared.store.db")
            // FTS5 virtual tables need a dialect newer than the default sqlite-3-18 grammar.
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:2.4.0")
        }
    }
}
