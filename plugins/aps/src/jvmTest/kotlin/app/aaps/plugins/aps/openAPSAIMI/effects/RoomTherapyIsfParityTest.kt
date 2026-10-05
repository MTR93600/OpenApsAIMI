package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.keys.BooleanKey
import app.aaps.database.DATABASE_VERSION
import app.aaps.database.di.JvmAppDatabaseBuilder
import app.aaps.database.persistence.converters.toDb
import app.aaps.plugins.aimicontracts.AimiTherapyCommand
import app.aaps.plugins.aimiengine.AimiCommonEngineSwitch
import app.aaps.plugins.aimitestkit.AimiTestSnapshots
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Android scene `autosensHalfDoublesScheduledBasalAndRestingHeartRateStrengthensIsf`
 * strengthens ISF 50 to 45 from four watch rows. The iOS tick reads those rows from Room,
 * through the same three DAOs, and the common function prints the same line.
 *
 * Android `DetermineBasalAIMI2` still calls `persistenceLayer`.
 */
class RoomTherapyIsfParityTest {

    private val files = mutableListOf<File>()

    @AfterTest
    fun deleteFiles() {
        AimiCommonEngineSwitch.enabled = false
        files.forEach { file ->
            JvmAppDatabaseBuilder().deleteDatabase(file.absolutePath)
        }
    }

    @Test
    fun aFreshFileIsSchema35AndEmpty() {
        val reads = openReads("fresh-empty.db")
        assertEquals(DATABASE_VERSION, reads.windows.userVersion())
        assertTrue(reads.windows.heartRates(0L, Long.MAX_VALUE).isEmpty())
        assertTrue(reads.windows.steps(0L, Long.MAX_VALUE).isEmpty())
        assertTrue(reads.windows.bolusesFromTime(0L, ascending = true).isEmpty())
        reads.close()
    }

    @Test
    fun schema34MigratesTo35WithTheCommonList() {
        val file = tempFile("migrate-34.db")
        installSchema(file, 34)
        val reads = RoomAimiTherapyReads(JvmAppDatabaseBuilder().provideTherapyWindowReads(file.absolutePath))
        assertEquals(DATABASE_VERSION, reads.windows.userVersion())
        assertTrue(reads.windows.heartRates(0L, Long.MAX_VALUE).isEmpty())
        reads.close()
    }

    @Test
    fun theSameFourRoomRowsStrengthenIsfTo45() {
        val now = aimiWallClockMs()
        val reads = openReads("isf-45.db")
        listOf(
            hr(now - 40 * 60_000L, 80.0),
            hr(now - 30 * 60_000L, 80.0),
            hr(now - 20 * 60_000L, 80.0),
            hr(now - 2 * 60_000L, 110.0),
        ).forEach { reads.windows.insertHeartRate(it.toDb()) }
        reads.windows.insertSteps(steps(now - 10 * 60_000L).toDb())
        reads.windows.insertBolus(bolus(timestamp = now - 60_000L, valid = false).toDb())
        reads.windows.insertBolus(bolus(timestamp = now - 30_000L, valid = true).toDb())

        val log = mutableListOf<String>()
        val result = decideHeartRateIsfFromTherapyReads(
            reads = reads,
            nowMs = now,
            stepsFromWatch = true,
            startingSensitivity = 50.0f,
            delta = 2.0f,
            glucoseMgdl = 140.0,
            bgMgdl = 160.0,
            iob = 1.0,
            bolusFromMs = 0L,
            bolusAscending = true,
            profile = profile(),
            preferences = StepsWatchPreferences(enabled = true),
            consoleLog = log,
        )
        assertEquals(45.0f, result.variableSensitivity, 0.001f)
        assertEquals(
            "💓 HR_TREND_ISF x0.90 (hr10 110 / hr60 88, steps10 0)",
            result.logLine,
        )
        assertEquals(1, result.boluses.size)
        assertTrue(result.boluses.single().isValid)

        AimiCommonEngineSwitch.enabled = true
        val (hold, neutral) = holdAimiEngineWired(IosNeutralScene.MEAL, reads)
        val tick = hold.evaluate(
            AimiTestSnapshots.emptyInput(),
            AimiTestSnapshots.emptyState(),
            AimiTestSnapshots.emptyModels(),
        )
        val smb = tick.command as AimiTherapyCommand.Smb
        val tbr = tick.pairedCommand as AimiTherapyCommand.TempBasal
        assertEquals("3.30", aimiFmt2(smb.insulinU))
        assertEquals("2.00", aimiFmt2(tbr.rateUPerHour))
        assertEquals(listOf(80.0, 80.0, 80.0, 110.0), neutral.therapyCaches.heartRates.map { it.beatsPerMinute })
        assertEquals(1, neutral.therapyCaches.boluses.size)
        reads.close()
    }

    @Test
    fun aClosedDatabaseLogsTheAndroidFailureAndDoesNotStrengthen() {
        val reads = openReads("closed.db")
        reads.close()
        val log = mutableListOf<String>()
        val result = decideHeartRateIsfFromTherapyReads(
            reads = reads,
            nowMs = aimiWallClockMs(),
            stepsFromWatch = true,
            startingSensitivity = 50.0f,
            delta = 2.0f,
            glucoseMgdl = 140.0,
            bgMgdl = 160.0,
            iob = 1.0,
            bolusFromMs = 0L,
            bolusAscending = true,
            profile = profile(),
            preferences = StepsWatchPreferences(enabled = true),
            consoleLog = log,
        )
        assertEquals(50.0f, result.variableSensitivity, 0.001f)
        assertEquals(null, result.logLine)
        val hr = log.single { it.startsWith("HR windows failed (") }
        assertTrue(hr.contains("averages 80, baseline not real"), hr)
        assertTrue(log.any { it.startsWith("Steps window failed (") && it.contains("steps empty") }, log.toString())
        assertTrue(log.any { it.startsWith("Bolus window failed (") && it.contains("boluses empty") }, log.toString())
    }

    private fun openReads(name: String): RoomAimiTherapyReads {
        val file = tempFile(name)
        return RoomAimiTherapyReads(JvmAppDatabaseBuilder().provideTherapyWindowReads(file.absolutePath))
    }

    private fun tempFile(name: String): File {
        val file = File(System.getProperty("java.io.tmpdir"), "aimi-room-$name")
        JvmAppDatabaseBuilder().deleteDatabase(file.absolutePath)
        files += file
        return file
    }

    private fun installSchema(file: File, version: Int) {
        val schema = schemaFile(version).readText()
        val database = Json.parseToJsonElement(schema).jsonObject.getValue("database").jsonObject
        val driver = BundledSQLiteDriver()
        driver.open(file.absolutePath).use { connection ->
            database.getValue("entities").jsonArray.forEach { entityElement ->
                val entity = entityElement.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                connection.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { indexElement ->
                    connection.execSQL(
                        indexElement.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table),
                    )
                }
            }
            database.getValue("setupQueries").jsonArray.forEach { query ->
                connection.execSQL(query.jsonPrimitive.content)
            }
            connection.execSQL("PRAGMA user_version = $version")
        }
    }

    private fun schemaFile(version: Int): File {
        val name = "$version.json"
        val candidates = listOf(
            File("database/impl/src/androidDeviceTest/assets/app.aaps.database.AppDatabase/$name"),
            File("../../database/impl/src/androidDeviceTest/assets/app.aaps.database.AppDatabase/$name"),
            File("src/androidDeviceTest/assets/app.aaps.database.AppDatabase/$name"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("schema $version not found from ${File(".").absolutePath}")
    }

    private fun hr(timestamp: Long, bpm: Double) = HR(
        duration = 60_000L,
        timestamp = timestamp,
        beatsPerMinute = bpm,
        device = "watch",
    )

    private fun steps(timestamp: Long) = SC(
        duration = 60_000L,
        timestamp = timestamp,
        steps5min = 0,
        steps10min = 0,
        steps15min = 0,
        steps30min = 0,
        steps60min = 0,
        steps180min = 0,
        device = "watch",
    )

    private fun bolus(timestamp: Long, valid: Boolean) = BS(
        timestamp = timestamp,
        amount = 1.0,
        type = BS.Type.SMB,
        isValid = valid,
        iCfg = ICfg(insulinLabel = "test", peak = 75, dia = 5.0, concentration = 1.0),
    )

    private fun profile() = OapsProfileAimi(
        dia = 5.0,
        min_5m_carbimpact = 0.0,
        max_iob = 10.0,
        max_daily_basal = 1.0,
        max_basal = 1.0,
        min_bg = 80.0,
        max_bg = 120.0,
        target_bg = 100.0,
        carb_ratio = 10.0,
        sens = 50.0,
        autosens_adjust_targets = false,
        max_daily_safety_multiplier = 1.0,
        current_basal_safety_multiplier = 1.0,
        high_temptarget_raises_sensitivity = false,
        low_temptarget_lowers_sensitivity = false,
        sensitivity_raises_target = false,
        resistance_lowers_target = false,
        adv_target_adjustments = false,
        exercise_mode = false,
        half_basal_exercise_target = 160,
        maxCOB = 120,
        skip_neutral_temps = false,
        remainingCarbsCap = 0,
        enableUAM = false,
        A52_risk_enable = false,
        SMBInterval = 3,
        enableSMB_with_COB = false,
        enableSMB_with_temptarget = false,
        allowSMB_with_high_temptarget = false,
        enableSMB_always = false,
        enableSMB_after_carbs = false,
        maxSMBBasalMinutes = 30,
        maxUAMSMBBasalMinutes = 30,
        bolus_increment = 0.1,
        carbsReqThreshold = 1,
        current_basal = 1.0,
        temptargetSet = false,
        autosens_max = 1.5,
        out_units = "mg/dl",
        lgsThreshold = 70,
        variable_sens = 50.0,
        insulinDivisor = 1,
        TDD = 40.0,
        peakTime = 75.0,
        futureActivity = 0.0,
        sensorLagActivity = 0.0,
        historicActivity = 0.0,
        currentActivity = 0.0,
    )
}
