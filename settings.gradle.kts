pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "sheltr"

include(":tools:publisher")
include(":apps:mobile:shared")
include(":apps:mobile:android")
