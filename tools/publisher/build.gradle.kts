plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

application {
    mainClass.set("com.sheltr.publisher.MainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.yaml:snakeyaml:2.6")
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Relative paths in --args (for example corpus/manifest.yaml) are resolved from the repository root.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
