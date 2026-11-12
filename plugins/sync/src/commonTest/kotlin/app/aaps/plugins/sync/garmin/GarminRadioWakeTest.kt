package app.aaps.plugins.sync.garmin

import kotlin.test.Test
import kotlin.test.assertEquals

class GarminRadioWakeTest {

    private var now = 10_000L
    private val wake = GarminRadioWake(nowMs = { now })

    @Test
    fun everyWakeChecksTheServerAndTheTaskIsTakenOncePerWindow() {
        val servers = ArrayList<String>()
        val tasks = ArrayList<String>()
        wake.onRadio({ servers += "a" }, { tasks += "a" })
        now += 1_000
        wake.onRadio({ servers += "b" }, { tasks += "b" })
        now += GarminRadioWake.TASK_GAP_MS
        wake.onRadio({ servers += "c" }, { tasks += "c" })
        assertEquals(listOf("a", "b", "c"), servers)
        assertEquals(listOf("a", "c"), tasks)
        assertEquals(8, GarminRadioWake.TASK_SECONDS)
    }
}
