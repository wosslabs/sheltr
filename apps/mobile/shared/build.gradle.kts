plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    // SQLDelight 2.4.0 (Apache-2.0, https://github.com/sqldelight/sqldelight): on-device store with FTS5.
    // Keep the plugin and the driver artifacts below on the same version.
    id("app.cash.sqldelight") version "2.4.0"
    id("com.android.library")
}

repositories {
    google()
    mavenCentral()
    // requery/sqlite-android (androidMain dependency below) ships a current SQLite build with FTS5, which
    // Android's own bundled SQLite lacks; it is not published to Maven Central, only to JitPack.
    maven { url = uri("https://jitpack.io") }
}

android {
    namespace = "com.sheltr.shared"
    // androidx.core:core:1.15.0 (a transitive of requery's sqlite-android) and SQLDelight's android-driver:2.4.0
    // both require compileSdk 35. Going further, to 37 (forced by okhttp-android:5.5.0, transitive of Ktor's
    // ktor-client-okhttp engine), would need an AGP bump past this plan's pinned 8.7.3 (which caps at 35) -- so
    // ktor-client-okhttp is removed below instead; see the comment there.
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        // Needed for the androidInstrumentedTest source set (connectedAndroidTest), run on a device/emulator.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

kotlin {
    jvm()

    androidTarget {
        // Without an explicit target, Kotlin compiles to the class file version of the JDK running Gradle. On a
        // JDK newer than Robolectric's bundled ASM supports, that breaks androidUnitTest with "Unsupported class
        // file major version" before any test runs. 17 matches Android's own toolchain expectations.
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Same version as the publisher (tools/publisher/build.gradle.kts).
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            // Ed25519 verification of bundle.json.sig (security dependency; pin the exact version).
            // cryptography-kotlin 0.6.0, Apache-2.0, https://github.com/whyoleg/cryptography-kotlin
            // Wraps platform crypto, no hand-written curve arithmetic: the optimal provider uses the
            // JDK provider on jvm and CryptoKit (Ed25519 via Curve25519.Signing) on iosArm64/iosSimulatorArm64.
            implementation("dev.whyoleg.cryptography:cryptography-core:0.6.0")
            implementation("dev.whyoleg.cryptography:cryptography-provider-optimal:0.6.0")
            implementation("app.cash.sqldelight:runtime:2.4.0")
            // Ktor 3.6.0 (Apache-2.0, https://github.com/ktorio/ktor): HTTP client for bundle sync. Only the core
            // client: the bundle files are fetched as raw bytes (signature and checksums cover exact bytes), so no
            // content negotiation. HttpClient() with no engine picks the one engine on each target's classpath.
            // Keep every io.ktor artifact on the same version.
            implementation("io.ktor:ktor-client-core:3.6.0")
        }
        jvmMain.dependencies {
            // JDBC driver over xerial sqlite-jdbc (bundled SQLite, built with FTS5). Used by jvmTest and the eval runner.
            implementation("app.cash.sqldelight:sqlite-driver:2.4.0")
            // JDK java.net.http client engine: no extra dependency beyond the JDK.
            implementation("io.ktor:ktor-client-java:3.6.0")
        }
        iosMain.dependencies {
            // Links the system libsqlite3 on iOS (FTS5 is enabled in the iOS system SQLite).
            implementation("app.cash.sqldelight:native-driver:2.4.0")
            // NSURLSession engine.
            implementation("io.ktor:ktor-client-darwin:3.6.0")
        }
        androidMain.dependencies {
            // SQLDelight's Android driver, backed by androidx.sqlite's SupportSQLiteOpenHelper abstraction.
            implementation("app.cash.sqldelight:android-driver:2.4.0")
            // Android's own bundled SQLite does not compile in FTS5 (confirmed against AOSP's
            // external/sqlite/dist/Android.bp: SQLITE_ENABLE_FTS3/FTS4 are set, FTS5 is not, on every branch
            // checked), but this app's schema needs it (see the sqlite-3-38-dialect comment below). requery's
            // sqlite-android (Apache-2.0, https://github.com/requery/sqlite-android) bundles its own current
            // SQLite build with FTS5 and implements SupportSQLiteOpenHelper via RequerySQLiteOpenHelperFactory,
            // a drop-in factory for AndroidSqliteDriver -- the standard fix for SQLDelight + FTS5 on Android.
            // Distributed only via JitPack, not Maven Central (see the repositories block above). Adds a
            // per-ABI native library (~1.2-1.8 MB each) to the APK; ABI-split packaging to shrink that is a
            // separate concern.
            implementation("com.github.requery:sqlite-android:3.49.0")
            // NOT added here: io.ktor:ktor-client-okhttp (the OkHttp engine). It is unused by every task in this
            // plan -- wired in only for the later Android sync plan, not this one -- and its transitive
            // okhttp-android:5.5.0 forces compileSdk 37, which would need an AGP version beyond this plan's
            // pinned 8.7.3 (capped at compileSdk 35). Re-add it, together with whatever compileSdk/AGP bump it
            // needs, when a future task actually builds Android sync.
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation("io.ktor:ktor-client-mock:3.6.0")
        }
        androidInstrumentedTest.dependencies {
            // Only used by AndroidStoreDriverFactoryInstrumentedTest, run via connectedAndroidTest on a real
            // device/emulator -- not executed by testDebugUnitTest. androidInstrumentedTest does not inherit
            // commonTest's kotlin("test") automatically, so it's repeated here.
            implementation(kotlin("test"))
            implementation("androidx.test:runner:1.7.0")
            implementation("androidx.test.ext:junit:1.3.0")
        }
    }
}

sqldelight {
    databases {
        create("SheltrDatabase") {
            packageName.set("com.sheltr.shared.store.db")
            // FTS5 virtual tables need a dialect newer than the default sqlite-3-18 grammar.
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:2.4.0")
        }
    }
}
