// Declares Kotlin plugin versions once for all subprojects (apply false).
// Keeps the Kotlin/Native build service from being loaded by two sibling projects
// (tools/publisher and apps/mobile/shared).
plugins {
    kotlin("jvm") version "2.4.20" apply false
    kotlin("plugin.serialization") version "2.4.20" apply false
    kotlin("multiplatform") version "2.4.20" apply false
    id("com.android.library") version "8.7.3" apply false
}
