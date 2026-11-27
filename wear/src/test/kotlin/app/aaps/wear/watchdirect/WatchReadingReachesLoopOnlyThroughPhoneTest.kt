package app.aaps.wear.watchdirect

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The rule of direct-to-watch: a reading the watch takes from the sensor itself reaches the loop
 * only through the phone, and only when the phone asked for it.
 *
 * The loop only gets glucose through `insertCgmSourceData` on the phone. So the rule holds when
 * (1) the watch app cannot store glucose at all, (2) the direct-to-watch code sends the phone only
 * its link status and the readings of [WatchReadingsForPhone], which gives none unless the phone
 * asked (tested in [WatchReadingsForPhoneTest]). The phone checks each watch reading again
 * (`DexcomOnePlusIngest.mayFeedLoopFromWatch`, tested in `:plugins:source`).
 */
class WatchReadingReachesLoopOnlyThroughPhoneTest {

    private val mainSources = File("src/main/kotlin").absoluteFile
    private val watchDirectSources = File(mainSources, "app/aaps/wear/watchdirect")

    private fun kotlinFiles(dir: File) = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `the guard has something to check`() {
        assertThat(kotlinFiles(watchDirectSources)).isNotEmpty()
    }

    @Test
    fun `the watch app has no way to store glucose for the loop`() {
        val offenders = kotlinFiles(mainSources).filter { file ->
            val text = file.readText()
            "insertCgmSourceData" in text || "PersistenceLayer" in text
        }.map { it.name }
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `direct to watch sends the phone only its status and the readings the phone asked for`() {
        val sent = kotlinFiles(watchDirectSources).flatMap { file ->
            SENT_TO_PHONE.findAll(file.readText()).map { it.groupValues[1].trim() }.toList()
        }
        assertThat(sent).isNotEmpty()
        assertThat(sent.toSet()).containsExactly("status", "EventData.CgmWatchReadings(address, readings, sentAt = now")
    }

    @Test
    fun `the readings sent are the ones of WatchReadingsForPhone`() {
        val text = File(watchDirectSources, "WatchDirect.kt").readText()
        assertThat(text).contains("val readings = forPhone.toSend(")
    }

    @Test
    fun `a watch sample type never leaves the watch`() {
        // OnePlusGlucoseSample is the driver's reading. Inside the watch app it may only appear in the
        // link, which turns it into a WatchSensorReading for the faces.
        val users = kotlinFiles(mainSources).filter { "OnePlusGlucoseSample" in it.readText() }.map { it.name }
        assertThat(users).containsExactly("OnePlusWatchSensorLink.kt")
    }

    private companion object {

        val SENT_TO_PHONE = Regex("""EventWearToMobile\(([^)]*)\)""")
    }
}
