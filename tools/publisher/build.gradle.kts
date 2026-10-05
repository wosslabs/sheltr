plugins {
    kotlin("jvm") version "2.4.20"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.yaml:snakeyaml:2.6")
    implementation("org.apache.pdfbox:pdfbox:3.0.3")
    implementation("org.jsoup:jsoup:1.23.2")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
