package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.NoOpAapsLogger
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer.HormonitorReader
import app.aaps.plugins.aps.openAPSAIMI.tpo.InMemoryAimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Hormonitor study exporter, now shared by Android and iOS. Runs on every target, so the same
 * lines are checked on the JVM and on the iOS simulator.
 *
 * Writes go through a background queue, so each test waits (bounded) for the file to appear.
 */
class AimiHormonitorStudyExporterTest {

    private val storage = InMemoryAimiStorage()

    /** Like iOS: the study files sit in the AIMI storage folder, which is where the viewer reads. */
    private inner class FakePlatform(private val primaryDenied: Boolean = false) : HormonitorStudyPlatform {
        override fun uptimeMs(): Long = 42L
        override fun appVersion(): String = "9.9.9-test"
        override fun datasetIdSeed(): String = "app.test|device-1"
        override fun sha256Hex(input: String): String = "h(" + input + ")" + "0".repeat(24)
        override fun primaryFile(name: String): AimiPath = storage.file(name).also { if (primaryDenied) storage.deniedPaths += it.value }
        override fun fallbackFile(name: String): AimiPath = AimiPath("/fallback/$name")
    }

    private fun exporter(platform: HormonitorStudyPlatform = FakePlatform()) =
        AimiHormonitorStudyExporterMTR(platform, NoOpAapsLogger, MapPreferences(), storage)

    private fun event(timestamp: Long = aimiWallClockMs()) = HormonitorDecisionEventMTR(
        eventId = "evt-1",
        eventTimestamp = timestamp,
        trigger = "loop",
        profileIsfMgdl = 50.0,
        profileBasalUph = 0.8,
        currentBgMgdl = 120.0,
        cobG = 0.0,
        iobU = 1.0,
        physioTrace = PhysioDecisionTraceMTR(timestamp = timestamp),
    )

    private fun awaitFile(path: String): String = runBlocking {
        withTimeout(5_000) {
            while (storage.files[path].isNullOrEmpty()) delay(10)
        }
        storage.files.getValue(path)
    }

    @Test
    fun anEventIsWrittenAsOneJsonLineWithTheHashedDatasetId() {
        exporter().export(event())

        val text = awaitFile("/aimi/AIMI_HORMONITOR_event_stream_v1.jsonl")
        assertTrue(text.endsWith("\n"), "one line, ended by a newline")
        val line = Json.parseToJsonElement(text.trimEnd()).jsonObject
        // sha256Hex(seed + "|hormonitor_v1"), first 24 characters: the raw ids never reach the file.
        val expectedId = FakePlatform().sha256Hex("app.test|device-1|hormonitor_v1").take(24)
        assertEquals(expectedId, line.getValue("dataset_id").jsonPrimitive.content)
        assertEquals("9.9.9-test", line.getValue("app_version").jsonPrimitive.content)
        assertEquals("1.4.0", line.getValue("schema_version").jsonPrimitive.content)
        assertEquals("evt-1", line.getValue("event_id").jsonPrimitive.content)
        // As SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'") in UTC wrote it on Android.
        assertTrue(
            Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""").matches(line.getValue("generated_at").jsonPrimitive.content),
            "generated_at: ${line.getValue("generated_at")}",
        )
    }

    @Test
    fun aRefusedPrimaryPlaceFallsBack() {
        exporter(FakePlatform(primaryDenied = true)).export(event())

        awaitFile("/fallback/AIMI_HORMONITOR_event_stream_v1.jsonl")
        assertTrue(storage.files["/aimi/AIMI_HORMONITOR_event_stream_v1.jsonl"].isNullOrEmpty())
    }

    /** End to end on the shared code: what the exporter writes is what the viewer screen lists. */
    @Test
    fun theViewerListsTheDayTheExporterWrote() {
        val now = aimiWallClockMs()
        exporter().exportDailyOutcomes(
            event = event(now),
            tirLowPct = 1.0,
            tirInRangePct = 90.0,
            tirAbovePct = 9.0,
            tdd24hTotalU = 30.0,
            snapshotSource = "test",
            snapshotAgeSeconds = 60L,
            snapshotConfidence = 0.9,
        )

        val text = awaitFile("/aimi/AIMI_HORMONITOR_daily_outcomes_v1.jsonl")
        val dayLocal = Json.parseToJsonElement(text.trimEnd()).jsonObject.getValue("day_local").jsonPrimitive.content
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(dayLocal), "day_local: $dayLocal")

        val days = runBlocking { HormonitorReader(storage).readDays() }
        assertEquals(listOf(dayLocal), days.map { it.dayLocal })
    }
}
