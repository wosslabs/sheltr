package com.sheltr.shared.store

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.sheltr.shared.store.db.SheltrDatabase
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import java.io.File

/**
 * Android driver over app.cash.sqldelight's android-driver (SupportSQLiteOpenHelper-backed). [path] is passed as
 * the driver's database name: Context.getDatabasePath treats a name that is an absolute path (starts with "/") as
 * the exact file to open, rather than resolving it under the app's default databases directory, so this honors
 * the same file-path contract JdbcStoreDriverFactory and the native iOS driver follow -- ContentStore's
 * activePath/stagingPath are always absolute.
 *
 * [RequerySQLiteOpenHelperFactory] swaps in requery's bundled SQLite build in place of the platform's own: the
 * platform's SQLite does not compile in FTS5 (confirmed against AOSP's external/sqlite/dist/Android.bp -- FTS3
 * and FTS4 are enabled, FTS5 is not), but this app's schema needs FTS5. Without this factory, schema creation
 * fails with "no such module: fts5" on every real device, not only under Robolectric.
 *
 * The driver's default callback creates the schema itself when the file's user_version is 0, which is normally
 * the case exactly when [create] is true (a brand new file) -- the same reasoning as NativeStoreDriverFactory for
 * iOS. But unlike the JVM/iOS drivers, this one does not fail or no-op on its own if [create] is false and the
 * file is missing: SQLiteOpenHelper would just treat the absent file as new and silently create the schema
 * anyway, violating the explicit "the file already holds a complete database" half of [StoreDriverFactory.open]'s
 * contract. [open] checks this itself instead, matching [JdbcStoreDriverFactory]'s explicit `if (create)` gate.
 *
 * No Context is available at the commonMain boundary, so the app module -- which owns the one Context it has --
 * constructs this factory and passes it to ContentStore.
 *
 * UNVERIFIED IN THIS ENVIRONMENT: requery's native library ships only Android device ABIs (arm64-v8a,
 * armeabi-v7a, x86, x86_64), with no desktop build, so it cannot load under Robolectric or any other host-JVM
 * test runner -- there is no way to exercise this class, including its FTS5 search path, without a real device
 * or emulator, neither of which is available here. See AndroidStoreDriverFactoryInstrumentedTest
 * (androidInstrumentedTest source set): the equivalent round trip, written to run via
 * `gradle :apps:mobile:shared:connectedAndroidTest` but not executed as part of this work. This is the single
 * most load-bearing untested path in the Android port: if FTS5-via-requery does not actually work on a real
 * device, the app's only retrieval mechanism is broken on Android. Treat it as a required manual/CI verification
 * item before release, not a formality.
 */
class AndroidStoreDriverFactory(private val context: Context) : StoreDriverFactory {
    override fun open(path: String, create: Boolean): SqlDriver {
        check(create || File(path).isFile) { "no database file at $path to open" }
        return AndroidSqliteDriver(
            SheltrDatabase.Schema,
            context,
            name = path,
            factory = RequerySQLiteOpenHelperFactory(),
        )
    }
}
