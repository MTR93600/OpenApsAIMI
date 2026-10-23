package app.aaps.implementation.androidNotification

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** A silent-channel alarm must ring by itself when the player is silent, and only then. */
class SilentChannelWatchTest {

    private val pending = mutableListOf<Runnable>()
    private val watch = SilentChannelWatch(
        schedule = { check, _ -> pending += check },
        unschedule = { check -> pending -= check },
        intervalMs = 5_000L
    )

    /** Runs the next scheduled check, as the handler would after the interval. */
    private fun tick() {
        val next = pending.removeAt(0)
        next.run()
    }

    @Test
    fun aPlayingAlarmIsLeftAlone() {
        var rang = 0
        watch.start(isAudible = { true }, ringFromChannel = { rang++ })

        repeat(3) { tick() }

        assertThat(rang).isEqualTo(0)
        assertThat(pending).hasSize(1)
    }

    @Test
    fun aSilentPlayerMakesTheAlarmRingFromItsChannelOnce() {
        var audible = true
        var rang = 0
        watch.start(isAudible = { audible }, ringFromChannel = { rang++ })
        tick()

        audible = false
        tick()

        assertThat(rang).isEqualTo(1)
        assertThat(pending).isEmpty()
    }

    @Test
    fun aStoppedWatchDoesNothing() {
        var rang = 0
        watch.start(isAudible = { false }, ringFromChannel = { rang++ })

        watch.stop()

        assertThat(pending).isEmpty()
        assertThat(rang).isEqualTo(0)
    }

    @Test
    fun aNewAlarmReplacesTheOldWatch() {
        var firstRang = 0
        var secondRang = 0
        watch.start(isAudible = { false }, ringFromChannel = { firstRang++ })
        watch.start(isAudible = { false }, ringFromChannel = { secondRang++ })

        tick()

        assertThat(firstRang).isEqualTo(0)
        assertThat(secondRang).isEqualTo(1)
    }
}
