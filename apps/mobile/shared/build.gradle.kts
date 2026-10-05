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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
