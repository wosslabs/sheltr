package com.sheltr.shared.store

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.sheltr.shared.store.db.SheltrDatabase

/** JVM driver over xerial sqlite-jdbc, whose bundled SQLite includes FTS5. Used by tests and the eval runner. */
object JdbcStoreDriverFactory : StoreDriverFactory {
    override fun open(path: String, create: Boolean): SqlDriver {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        if (create) {
            try {
                SheltrDatabase.Schema.create(driver)
            } catch (e: Throwable) {
                driver.close()
                throw e
            }
        }
        return driver
    }
}
