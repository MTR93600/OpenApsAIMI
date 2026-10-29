package app.aaps.database

import app.aaps.database.daos.ApsResultVariableSens
import app.aaps.database.di.JvmAppDatabaseBuilder
import app.aaps.database.entities.APSResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The variable sensitivity query of the graph, against a real database file.
 *
 * The value is read with `json_extract` out of `resultJson`. That is SQL, so only a real SQLite can
 * check it, and iOS runs the same bundled SQLite as this test.
 *
 * `resultJson` is written by kotlinx with `allowSpecialFloatingPointValues`, so a result can hold
 * `NaN` or `Infinity`. Plain JSON does not allow them. The bundled SQLite reads them as JSON5, and one
 * such row must not make the whole query fail, because then the graph phase of every calculation would
 * fail with it.
 */
class ApsResultVariableSensTest {

    private val directory = createTempDirectory("aaps-varsens-test")
    private val database = JvmAppDatabaseBuilder().provideAppDatabase("$directory/aaps.db")

    @AfterTest
    fun tearDown() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `reads the value of each result in the interval, oldest first`() = runTest {
        database.apsResultDao.insert(apsResult(NOW + 2 * FIVE_MIN, """{"variable_sens":52.5}"""))
        database.apsResultDao.insert(apsResult(NOW, """{"bg":120.0,"variable_sens":48.0,"predBGs":{"IOB":[120,118,116]}}"""))
        database.apsResultDao.insert(apsResult(NOW + FIVE_MIN, """{"variable_sens":50.0}"""))
        // Just outside the interval, on both sides
        database.apsResultDao.insert(apsResult(NOW - 1, """{"variable_sens":10.0}"""))
        database.apsResultDao.insert(apsResult(NOW + 2 * FIVE_MIN + 1, """{"variable_sens":20.0}"""))

        assertThat(database.apsResultDao.getVariableSens(NOW, NOW + 2 * FIVE_MIN)).containsExactly(
            ApsResultVariableSens(NOW, 48.0),
            ApsResultVariableSens(NOW + FIVE_MIN, 50.0),
            ApsResultVariableSens(NOW + 2 * FIVE_MIN, 52.5)
        ).inOrder()
    }

    @Test
    fun `a missing value and NaN give null without failing the other rows`() = runTest {
        // A result without the field
        database.apsResultDao.insert(apsResult(NOW, """{"bg":120.0}"""))
        database.apsResultDao.insert(apsResult(NOW + FIVE_MIN, """{"variable_sens":NaN,"eventualBG":Infinity}"""))
        database.apsResultDao.insert(apsResult(NOW + 2 * FIVE_MIN, """{"sensitivityRatio":NaN,"variable_sens":45.0}"""))
        database.apsResultDao.insert(apsResult(NOW + 3 * FIVE_MIN, """{"variable_sens":Infinity}"""))

        assertThat(database.apsResultDao.getVariableSens(NOW, NOW + 3 * FIVE_MIN)).containsExactly(
            ApsResultVariableSens(NOW, null),
            ApsResultVariableSens(NOW + FIVE_MIN, null),
            ApsResultVariableSens(NOW + 2 * FIVE_MIN, 45.0),
            ApsResultVariableSens(NOW + 3 * FIVE_MIN, Double.POSITIVE_INFINITY)
        ).inOrder()
    }

    @Test
    fun `a row that is not JSON gives null without failing the other rows`() = runTest {
        database.apsResultDao.insert(apsResult(NOW, """{"variable_sens":40.0}"""))
        // Half of an emoji: SQLite joins it with the closing quote, so the stored text is not JSON
        database.apsResultDao.insert(apsResult(NOW + FIVE_MIN, "{\"reason\":\"cut \uD83D\",\"variable_sens\":41.0}"))
        database.apsResultDao.insert(apsResult(NOW + 2 * FIVE_MIN, ""))
        database.apsResultDao.insert(apsResult(NOW + 3 * FIVE_MIN, """{"variable_sens":43.0}"""))

        assertThat(database.apsResultDao.getVariableSens(NOW, NOW + 3 * FIVE_MIN)).containsExactly(
            ApsResultVariableSens(NOW, 40.0),
            ApsResultVariableSens(NOW + FIVE_MIN, null),
            ApsResultVariableSens(NOW + 2 * FIVE_MIN, null),
            ApsResultVariableSens(NOW + 3 * FIVE_MIN, 43.0)
        ).inOrder()
    }

    private fun apsResult(timestamp: Long, resultJson: String) = APSResult(
        timestamp = timestamp,
        algorithm = APSResult.Algorithm.SMB,
        glucoseStatusJson = null,
        currentTempJson = null,
        iobDataJson = null,
        profileJson = null,
        autosensDataJson = null,
        mealDataJson = null,
        resultJson = resultJson
    )

    private companion object {

        const val NOW = 1_800_000_000_000L
        const val FIVE_MIN = 5 * 60 * 1000L
    }
}
