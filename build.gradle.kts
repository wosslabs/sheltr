// Declares Kotlin plugin versions once for all subprojects (apply false).
// Keeps the Kotlin/Native build service from being loaded by two sibling projects
// (tools/publisher and apps/mobile/shared).
plugins {
    kotlin("jvm") version "2.4.20" apply false
    kotlin("plugin.serialization") version "2.4.20" apply false
    kotlin("multiplatform") version "2.4.21" apply false
    kotlin("android") version "2.4.20" apply false
    kotlin("plugin.compose") version "2.4.20" apply false
    id("com.android.library") version "8.7.3" apply false
    // Same AGP version as com.android.library, for apps/mobile/android (the single-platform Compose app module).
    id("com.android.application") version "8.7.3" apply false
}
