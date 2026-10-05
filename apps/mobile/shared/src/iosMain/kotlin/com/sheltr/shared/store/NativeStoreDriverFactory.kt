package com.sheltr.shared.store

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.sheltr.shared.store.db.SheltrDatabase

/**
 * iOS driver over the system SQLite. UNTESTED: compiled for the iOS targets only; no test runs it yet (M4 runs
 * jvmTest only). The native driver creates the schema itself when the file's user_version is 0, which is the case
 * exactly when the file is new, so [create] needs no separate handling here.
 */
object NativeStoreDriverFactory : StoreDriverFactory {
    override fun open(path: String, create: Boolean): SqlDriver {
        val slash = path.lastIndexOf('/')
        val directory = if (slash >= 0) path.substring(0, slash) else null
        val name = path.substring(slash + 1)
        return NativeSqliteDriver(
            schema = SheltrDatabase.Schema,
            name = name,
            onConfiguration = { config ->
                config.copy(extendedConfig = config.extendedConfig.copy(basePath = directory))
            },
        )
    }
}
