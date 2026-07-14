package com.udnahc.immichgallery.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppDatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = instrumentation,
        file = instrumentation.targetContext.getDatabasePath(TEST_DATABASE_NAME),
        driver = BundledSQLiteDriver(),
        databaseClass = AppDatabase::class
    )

    @Test
    fun migrationOneToTwoPreservesTimelineAndMosaicRows() {
        helper.createDatabase(1).use { connection ->
            connection.execSQL(
                "INSERT INTO timeline_buckets " +
                        "(timeBucket, displayLabel, count, sortOrder) " +
                        "VALUES ('2024-01-01', 'January 2024', 1, 0)"
            )
            connection.execSQL(
                "INSERT INTO timeline_mosaic_assignments " +
                        "(timeBucket, groupMode, sectionKey, columnCount, familiesKey, " +
                        "assetFingerprint, assignmentsJson, updatedAt) " +
                        "VALUES ('2024-01-01', 'MONTH', '2024-01-01', 2, 'FOUR_TILE', " +
                        "'fingerprint', '[]', 1)"
            )
        }

        helper.runMigrationsAndValidate(
            version = 2,
            migrations = listOf(MIGRATION_1_2)
        ).use { connection ->
            connection.prepare(
                "SELECT displayLabel FROM timeline_buckets WHERE timeBucket = '2024-01-01'"
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("January 2024", statement.getText(0))
            }
            connection.prepare(
                "SELECT assetFingerprint FROM timeline_mosaic_assignments " +
                        "WHERE timeBucket = '2024-01-01'"
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("fingerprint", statement.getText(0))
            }
        }
    }

    private companion object {
        const val TEST_DATABASE_NAME = "migration-test.db"
    }
}
