package app.aaps.database.di

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Room
import androidx.room.RoomDatabase.Callback
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.sqlite.db.SupportSQLiteDatabase
import app.aaps.database.AppDatabase

/**
 * Builds the Room database. Not a DI module any more - `DatabaseBindings` provides the instance and
 * this class only holds the builder and the migration list, so the instrumented tests in this module
 * can open a database exactly the way the app does.
 */
open class AppDatabaseBuilder {

    internal fun provideAppDatabase(context: Context, fileName: String) =
        Room
            .databaseBuilder(context, AppDatabase::class.java, fileName)
            // Bundled SQLite driver: ships its own SQLite compiled from source instead of the
            // device's framework SQLite. This is Google's recommended driver (consistent engine
            // across all devices) and, crucially, it does not allocate the framework CursorWindow
            // mem buffer, eliminating CursorWindowAllocationException on memory-constrained devices.
            .setDriver(BundledSQLiteDriver())
            .addMigrations(*migrations)
            .addCallback(object : Callback() {
                // Driver mode delivers an SQLiteConnection (not a SupportSQLiteConnection), so the
                // SupportSQLiteDatabase overload of onOpen never fires here — the connection overload must.
                override fun onOpen(connection: SQLiteConnection) {
                    super.onOpen(connection)
                    createCustomIndexes(connection)
                }
            })
            .fallbackToDestructiveMigration(false)
            .build()

    /**
     * In-memory copy of the same database, used by the instrumented tests. Built the way it always
     * was: no bundled driver, so the SupportSQLiteDatabase overload of onOpen is the one that fires.
     */
    internal fun provideInMemoryAppDatabase(context: Context) =
        Room
            .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(object : Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    createCustomIndexes(db)
                }
            })
            .fallbackToDestructiveMigration(false)
            .build()

    private fun createCustomIndexes(database: SupportSQLiteDatabase) {
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_temporaryBasals_end` ON `temporaryBasals` (`timestamp` + `duration`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_extendedBoluses_end` ON `extendedBoluses` (`timestamp` + `duration`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_temporaryTargets_end` ON `temporaryTargets` (`timestamp` + `duration`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_carbs_end` ON `carbs` (`timestamp` + `duration`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_runningModes_end` ON `runningModes` (`timestamp` + `duration`)")
    }

    private fun createCustomIndexes(connection: SQLiteConnection) {
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_temporaryBasals_end` ON `temporaryBasals` (`timestamp` + `duration`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_extendedBoluses_end` ON `extendedBoluses` (`timestamp` + `duration`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_temporaryTargets_end` ON `temporaryTargets` (`timestamp` + `duration`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_carbs_end` ON `carbs` (`timestamp` + `duration`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_runningModes_end` ON `runningModes` (`timestamp` + `duration`)")
    }

    /** The common list. Instrument tests still ask the Android builder for it. */
    @VisibleForTesting
    internal val migrations: Array<Migration>
        get() = appDatabaseMigrations

}
