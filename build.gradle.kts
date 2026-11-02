// Declares the Kotlin plugin versions once for all subprojects (apply false). Gradle now builds only the
// publisher (tools/publisher); the mobile app is an Expo project in apps/mobile with its own package.json.
plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
}
