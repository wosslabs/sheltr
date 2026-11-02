plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

repositories {
    google()
    mavenCentral()
    // apps/mobile/shared's androidMain depends on com.github.requery:sqlite-android (JitPack only, see that
    // module's build.gradle.kts); this app module resolves :shared's full transitive graph for its own APK, so
    // it needs the same repository to find that artifact.
    maven { url = uri("https://jitpack.io") }
}

android {
    namespace = "com.sheltr.android"
    // Matches apps/mobile/shared's compileSdk (see that module's build.gradle.kts for why: androidx.core 1.15.0
    // and SQLDelight's android-driver 2.4.0, both transitives of :shared, require compileSdk 35).
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sheltr.android"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Where the app fetches the signed bundle, and the Ed25519 public key (64 hex characters, raw 32 bytes) it
        // verifies it with. Empty means sync is off and the app only has the bundled sample content. Set them per
        // build, never in source: ./gradlew -Psheltr.publisherBaseUrl=https://... -Psheltr.publisherPublicKeyHex=...
        // See docs/publishing.md.
        val baseUrl = providers.gradleProperty("sheltr.publisherBaseUrl").getOrElse("")
        val publicKeyHex = providers.gradleProperty("sheltr.publisherPublicKeyHex").getOrElse("")
        buildConfigField("String", "PUBLISHER_BASE_URL", "\"$baseUrl\"")
        buildConfigField("String", "PUBLISHER_PUBLIC_KEY_HEX", "\"$publicKeyHex\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Compose-under-Robolectric (createAndroidComposeRule) needs resources resolvable from the test
            // classpath, e.g. the platform/AppCompat theme attributes ComponentActivity's window reads.
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        getByName("main") {
            // Points directly at the repository's checksum-pinned apps/mobile/android-sample-bundle instead of
            // a copy under src/main/assets: a copy could silently drift from that source (different bytes than
            // what AssetBundleInstallerTest in :apps:mobile:shared actually verifies against). AGP packages this
            // directory's own contents (bundle.json, documents/*.json) straight into assets/, so
            // AndroidAssetBundleReader in SheltrApplication.kt reads them with no subdirectory prefix.
            assets.srcDirs("../android-sample-bundle")
        }
    }
}

kotlin {
    // Same reasoning as apps/mobile/shared's androidTarget: without this, Kotlin compiles to the class file
    // version of the JDK running Gradle, which breaks Robolectric ("Unsupported class file major version") on a
    // JDK newer than its bundled ASM supports. 17 matches Android's own toolchain expectations.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":apps:mobile:shared"))

    // Not the latest Compose BOM/activity-compose: verified against Google's Maven metadata (dl.google.com)
    // that the current releases (compose-bom 2026.09.00, activity-compose 1.13.0 as of this writing) require
    // compileSdk 36+ and AGP 8.9.1/9.1.0+ -- confirmed by actually trying them first and reading AGP's own
    // dependency-compatibility report, not guessed. This project is pinned to AGP 8.7.3/compileSdk 35 (see the
    // compileSdk comment above), so 2025.01.00/1.9.3 are the newest versions confirmed compatible with that
    // ceiling; navigation-compose and lifecycle-viewmodel-compose are pinned to their contemporaries for the
    // same reason. Bump together with a future compileSdk/AGP upgrade, not independently.
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // Per-app language support (the Settings screen language picker, via AppCompatDelegate/LocaleListCompat).
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Same version as :apps:mobile:shared's commonMain (which declares it `implementation`, not `api`, so it
    // isn't exposed transitively -- this module needs its own direct dependency to reference HttpClient).
    implementation("io.ktor:ktor-client-core:3.6.0")
    // The HTTP engine for bundle sync. CIO is pure Kotlin: unlike ktor-client-okhttp it does not pull in
    // okhttp-android:5.5.0, which would force compileSdk 37 (this project is capped at 35 by AGP 8.7.3).
    implementation("io.ktor:ktor-client-cio:3.6.0")

    testImplementation("junit:junit:4.13.2")
    // Same version as the main-source kotlinx-coroutines-android above. SearchViewModelTest/SearchScreenTest
    // need TestDispatcher/runTest/advanceUntilIdle to drive SearchViewModel's Dispatchers.IO hop
    // deterministically instead of racing a real thread pool.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // debugImplementation, not testImplementation: Robolectric (testDebugUnitTest) reuses the debug variant's
    // merged manifest (confirmed via build/intermediates/unit_test_config_directory's test_config.properties,
    // which points at processDebugManifestForPackage's output, not a test-specific manifest), so this library's
    // <activity android:name="androidx.activity.ComponentActivity"> entry -- needed for
    // createAndroidComposeRule<ComponentActivity>() to resolve a launchable activity -- only takes effect from
    // here. Excluded from release builds, same as any debugImplementation dependency.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // Same version as apps/mobile/shared's androidUnitTest, for consistency.
    testImplementation("androidx.test:core:1.7.0")
}
