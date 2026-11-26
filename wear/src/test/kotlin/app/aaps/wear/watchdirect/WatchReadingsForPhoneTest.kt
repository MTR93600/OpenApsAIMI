package app.aaps.wear.watchdirect

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WatchReadingsForPhoneTest {

    private val now = 100 * MIN

    private fun reading(at: Long, mgdl: Double = 120.0) = WatchSensorReading(mgdl, at, -1.0)

    @Test
    fun `nothing is sent while the phone did not ask`() {
        val forPhone = WatchReadingsForPhone()
        forPhone.add(reading(now), now)
        assertThat(forPhone.toSend(now)).isEmpty()
    }

    @Test
    fun `readings taken before the phone asked are sent once it asks`() {
        val forPhone = WatchReadingsForPhone()
        forPhone.add(reading(now - 5 * MIN), now)
        forPhone.add(reading(now), now)
        forPhone.setWanted(true)
        assertThat(forPhone.toSend(now).map { it.timestamp }).containsExactly(now - 5 * MIN, now).inOrder()
    }

    @Test
    fun `the same reading is kept once`() {
        val forPhone = WatchReadingsForPhone().apply { setWanted(true) }
        assertThat(forPhone.add(reading(now), now)).isTrue()
        assertThat(forPhone.add(reading(now), now)).isFalse()
        assertThat(forPhone.toSend(now)).hasSize(1)
    }

    @Test
    fun `readings stay until the phone has them, so a lost message is sent again`() {
        val forPhone = WatchReadingsForPhone().apply { setWanted(true) }
        forPhone.add(reading(now - 10 * MIN), now)
        forPhone.add(reading(now - 5 * MIN), now)
        forPhone.add(reading(now), now)
        forPhone.toSend(now)
        assertThat(forPhone.toSend(now)).hasSize(3)

        forPhone.phoneHas(now - 5 * MIN)
        assertThat(forPhone.toSend(now).map { it.timestamp }).containsExactly(now)
    }

    @Test
    fun `a reading the phone already has is not added`() {
        val forPhone = WatchReadingsForPhone().apply { setWanted(true) }
        forPhone.phoneHas(now)
        assertThat(forPhone.add(reading(now - 5 * MIN), now)).isFalse()
        assertThat(forPhone.add(reading(now), now)).isFalse()
        assertThat(forPhone.toSend(now)).isEmpty()
    }

    @Test
    fun `turned off by the phone, the readings are dropped`() {
        val forPhone = WatchReadingsForPhone().apply { setWanted(true) }
        forPhone.add(reading(now), now)
        forPhone.setWanted(false)
        forPhone.setWanted(true)
        assertThat(forPhone.toSend(now)).isEmpty()
    }

    @Test
    fun `another sensor drops the readings of the old one`() {
        val forPhone = WatchReadingsForPhone().apply { setWanted(true) }
        forPhone.phoneHas(now - 5 * MIN)
        forPhone.add(reading(now), now)
        forPhone.clear()
        assertThat(forPhone.toSend(now)).isEmpty()
        // The new sensor starts from nothing: its first reading goes, even if older than the old one's.
        assertThat(forPhone.add(reading(now - 10 * MIN), now)).isTrue()
    }

    @Test
    fun `readings older than the graph window are dropped`() {
        val forPhone = WatchReadingsForPhone().apply { setWanted(true) }
        forPhone.add(reading(now), now)
        val later = now + WatchDirectReadings.GRAPH_WINDOW_MS + MIN
        forPhone.add(reading(later), later)
        assertThat(forPhone.toSend(later).map { it.timestamp }).containsExactly(later)
    }

    @Test
    fun `the reading keeps its value and its rate of change`() {
        val forPhone = WatchReadingsForPhone().apply { setWanted(true) }
        forPhone.add(WatchSensorReading(87.0, now, 2.5), now)
        val sent = forPhone.toSend(now).single()
        assertThat(sent.mgdl).isEqualTo(87.0)
        assertThat(sent.slopeMgdlPerMin).isEqualTo(2.5)
    }

    private companion object {

        const val MIN = 60_000L
    }
}
