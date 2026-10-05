plugins {
    kotlin("jvm") version "2.4.20"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.yaml:snakeyaml:2.6")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
