plugins {
    kotlin("multiplatform")
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
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
