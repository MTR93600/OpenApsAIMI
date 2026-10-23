package app.aaps.plugins.source.notificationreader

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GlucoseDeduplicatorTest {

    private val min1 = 60_000L
    private val min3 = 3 * 60_000L
    private val min5 = 5 * 60_000L
    private val min15 = 15 * 60_000L
    private val bg = 120
    private val bgNext = 125

    private class MemStore : GlucoseDeduplicator.StateStore {

        var data: String? = null
        override fun load(): String? = data
        override fun save(json: String) {
            data = json
        }
    }

    private fun configWith(vararg packages: Pair<String, Int?>): PackageConfig {
        val entries = packages.joinToString(",") { (pkg, interval) ->
            val intervalField = interval?.let { ", \"intervalMinutes\": $it" } ?: ""
            """{ "package": "$pkg", "sensor": "Unknown"$intervalField }"""
        }
        return PackageConfig.fromJson("""{ "version": 2, "packages": [$entries] }""")
    }

    // ----- basic -----

    @Test
    fun `first reading is always accepted`() {
        val d = GlucoseDeduplicator(configWith("p" to 5), MemStore())
        assertThat(d.process("p", 0L, bg)).isTrue()
    }

    @Test
    fun `seed from package metadata`() {
        val d = GlucoseDeduplicator(configWith("p" to 1), MemStore())
        d.process("p", 0L, bg)
        assertThat(d.currentIntervalMs("p")).isEqualTo(min1)
    }

    @Test
    fun `seed default when package has no metadata`() {
        val d = GlucoseDeduplicator(configWith("p" to null), MemStore())
        d.process("p", 0L, bg)
        assertThat(d.currentIntervalMs("p")).isEqualTo(min5)
    }

    @Test
    fun `unknown package uses default seed`() {
        val d = GlucoseDeduplicator(configWith(), MemStore())
        assertThat(d.process("unknown", 0L, bg)).isTrue()
        assertThat(d.currentIntervalMs("unknown")).isEqualTo(min5)
    }

    // ----- duplicate window -----

    @Test
    fun `same value within window is rejected`() {
        val d = GlucoseDeduplicator(configWith("p" to 5), MemStore())
        d.process("p", 0L, bg)
        // threshold = 5min - 1min margin = 4min
        assertThat(d.process("p", 30_000L, bg)).isFalse()
        assertThat(d.process("p", 3 * min1, bg)).isFalse()
    }

    @Test
    fun `value change within window is accepted after min gap`() {
        val d = GlucoseDeduplicator(configWith("p" to 5), MemStore())
        d.process("p", 0L, bg)
        // Below BgQualityCheck DOUBLED (20s) + floor → reject.
        assertThat(d.process("p", 5_000L, bgNext)).isFalse()
        // Safe gap (≥21s) with new value → accept; interval unchanged.
        assertThat(d.process("p", 30_000L, bgNext)).isTrue()
        assertThat(d.currentIntervalMs("p")).isEqualTo(min5)
    }

    @Test
    fun `value change under min accept gap is rejected - protects loop from DOUBLED`() {
        val d = GlucoseDeduplicator(configWith("p" to 5), MemStore())
        d.process("p", 0L, bg)
        assertThat(d.process("p", 1_000L, bgNext)).isFalse()
        assertThat(d.process("p", GlucoseDeduplicator.MIN_ACCEPT_GAP_MS - 1, bgNext)).isFalse()
        assertThat(d.process("p", GlucoseDeduplicator.MIN_ACCEPT_GAP_MS, bgNext)).isTrue()
    }

    @Test
    fun `migrated state without last glucose rejects short-gap until long-gap reseed`() {
        val store = MemStore()
        store.data = """[{"p":"p","t":0,"i":${min5},"pi":0,"c":0}]""" // no "v"
        val d = GlucoseDeduplicator(configWith("p" to 5), store)
        assertThat(d.process("p", 30_000L, bg)).isFalse()
        assertThat(d.process("p", 4 * min1, bg)).isTrue()
        assertThat(d.process("p", 4 * min1 + 30_000L, bg)).isFalse()
    }

    @Test
    fun `reading at or after window is accepted`() {
        val d = GlucoseDeduplicator(configWith("p" to 5), MemStore())
        d.process("p", 0L, bg)
        // gap exactly 4min → accepted (gap < threshold is false)
        assertThat(d.process("p", 4 * min1, bg)).isTrue()
    }

    @Test
    fun `reading well after window accepted (stable BG)`() {
        val d = GlucoseDeduplicator(configWith("p" to 5), MemStore())
        d.process("p", 0L, bg)
        assertThat(d.process("p", min5, bg)).isTrue()
        assertThat(d.process("p", 2 * min5, bg)).isTrue()
    }

    // ----- Dexcom transition glitch regression (the field bug) -----

    @Test
    fun `short-gap same value is rejected - Dexcom transition glitch does not drop interval`() {
        // Dexcom often re-posts the same value within the 5-min cycle.
        // Those must be rejected and the interval must not decay.
        val d = GlucoseDeduplicator(configWith("p" to 5), MemStore())
        d.process("p", 0L, bg)
        assertThat(d.process("p", 1_000L, bg)).isFalse()   // same-second repost
        assertThat(d.process("p", min1, bg)).isFalse()     // 1min repost
        assertThat(d.currentIntervalMs("p")).isEqualTo(min5)
    }

    @Test
    fun `short-gap value change under min gap is rejected and does not drop interval`() {
        val d = GlucoseDeduplicator(configWith("p" to 5), MemStore())
        d.process("p", 0L, bg)
        assertThat(d.process("p", 1_000L, bgNext)).isFalse()
        assertThat(d.currentIntervalMs("p")).isEqualTo(min5)
        assertThat(d.process("p", 30_000L, bgNext)).isTrue()
        assertThat(d.currentIntervalMs("p")).isEqualTo(min5)
    }

    @Test
    fun `seed is a hard floor - interval never drops below configured cadence`() {
        val d = GlucoseDeduplicator(configWith("p" to 5), MemStore())
        // Hammer with many short-gap same-value readings across many cycles.
        repeat(100) { i ->
            d.process("p", i * 1_000L, bg)
        }
        assertThat(d.currentIntervalMs("p")).isEqualTo(min5)
    }

    @Test
    fun `dexcom-like stream accepts exactly one reading per five-minute cycle when value stable`() {
        // Simulate the screenshot pattern: 5 notification reposts spaced ~1 minute apart within
        // each true 5-minute cycle (last repost ~3.5 min after cycle start, well inside the
        // 4-minute threshold). Expect exactly one accept per cycle when BG is unchanged.
        val d = GlucoseDeduplicator(configWith("p" to 5), MemStore())
        val repostOffsets = listOf(0L, 30_000L, 90_000L, 150_000L, 210_000L)
        val acceptTimes = mutableListOf<Long>()
        repeat(6) { cycle ->
            val cycleStart = cycle * min5
            for (offset in repostOffsets) {
                val t = cycleStart + offset
                if (d.process("p", t, bg)) acceptTimes += t
            }
        }
        assertThat(acceptTimes).hasSize(6)
        acceptTimes.forEachIndexed { i, time ->
            assertThat(time).isEqualTo(i * min5)
        }
    }

    // ----- snap up requires N consecutive -----

    @Test
    fun `snap up requires three consecutive long gaps`() {
        val d = GlucoseDeduplicator(configWith("p" to 1), MemStore())
        d.process("p", 0L, bg)
        // Gaps of 5min each → all accepted (gap >> threshold). Snap up after 3.
        d.process("p", min5, bg); assertThat(d.currentIntervalMs("p")).isEqualTo(min1)
        d.process("p", 2 * min5, bg); assertThat(d.currentIntervalMs("p")).isEqualTo(min1)
        d.process("p", 3 * min5, bg); assertThat(d.currentIntervalMs("p")).isEqualTo(min5)
    }

    @Test
    fun `snap up to 15min after three consecutive 15min gaps`() {
        val d = GlucoseDeduplicator(configWith("p" to 1), MemStore())
        d.process("p", 0L, bg)
        d.process("p", min15, bg)
        d.process("p", 2 * min15, bg)
        d.process("p", 3 * min15, bg)
        assertThat(d.currentIntervalMs("p")).isEqualTo(min15)
    }

    @Test
    fun `single missed reading does not change interval`() {
        // 5min sensor with one 10min gap (missed reading). 10min snaps to 5min interval → no change.
        val d = GlucoseDeduplicator(configWith("p" to 5), MemStore())
        d.process("p", 0L, bg)
        d.process("p", 10 * min1, bg)
        assertThat(d.currentIntervalMs("p")).isEqualTo(min5)
    }

    @Test
    fun `snap up counter reset by matching-interval gap`() {
        // Counting up from 1min toward 5min; an interrupting gap that snaps to the current
        // interval resets the counter.
        val d = GlucoseDeduplicator(configWith("p" to 1), MemStore())
        d.process("p", 0L, bg)
        d.process("p", min5, bg)              // count=1 toward 5
        d.process("p", 2 * min5, bg)          // count=2 toward 5
        d.process("p", 2 * min5 + min1, bg)   // gap=1min snaps to 1 (same as current) → reset
        d.process("p", 2 * min5 + 2 * min1, bg) // gap=1min → still reset
        // Now restart count toward 5.
        d.process("p", 2 * min5 + 2 * min1 + min5, bg)   // count=1
        d.process("p", 2 * min5 + 2 * min1 + 2 * min5, bg) // count=2
        assertThat(d.currentIntervalMs("p")).isEqualTo(min1)
        d.process("p", 2 * min5 + 2 * min1 + 3 * min5, bg) // count=3 → snap
        assertThat(d.currentIntervalMs("p")).isEqualTo(min5)
    }

    @Test
    fun `snap up counter reset by different long interval`() {
        // Counting toward 5min, then a 15min gap → counter restarts for 15.
        val d = GlucoseDeduplicator(configWith("p" to 1), MemStore())
        var t = 0L
        d.process("p", t, bg)
        t += min5; d.process("p", t, bg)   // count=1 for 5
        t += min5; d.process("p", t, bg)   // count=2 for 5
        t += min15; d.process("p", t, bg)  // count restarts → count=1 for 15
        assertThat(d.currentIntervalMs("p")).isEqualTo(min1)
        t += min15; d.process("p", t, bg)  // count=2 for 15
        t += min15; d.process("p", t, bg)  // count=3 for 15 → snap
        assertThat(d.currentIntervalMs("p")).isEqualTo(min15)
    }

    // ----- multi-package independence -----

    @Test
    fun `two packages have independent state`() {
        val d = GlucoseDeduplicator(configWith("a" to 5, "b" to 1), MemStore())
        d.process("a", 0L, bg)
        d.process("b", 0L, bg)
        // a is on 5min: reading at 1min rejected.
        assertThat(d.process("a", min1, bg)).isFalse()
        // b is on 1min: reading at 1min accepted (1min gap, threshold ~48s).
        assertThat(d.process("b", min1, bg)).isTrue()
    }

    // ----- persistence -----

    @Test
    fun `state survives recreate via store`() {
        val store = MemStore()
        val cfg = configWith("p" to 5)
        val d1 = GlucoseDeduplicator(cfg, store)
        d1.process("p", 0L, bg)
        d1.process("p", min5, bg)
        val intervalBefore = d1.currentIntervalMs("p")

        val d2 = GlucoseDeduplicator(cfg, store)
        assertThat(d2.currentIntervalMs("p")).isEqualTo(intervalBefore)
        // 30s after the last accepted (which was at min5) → rejected as duplicate.
        assertThat(d2.process("p", min5 + 30_000L, bg)).isFalse()
        // Value change after recreate must still be accepted.
        assertThat(d2.process("p", min5 + 45_000L, bgNext)).isTrue()
    }

    // ----- adaptation to wrong seed -----

    @Test
    fun `wrong-low seed converges upward after three consistent long gaps`() {
        // Seeded 1min, real 5min.
        val d = GlucoseDeduplicator(configWith("p" to 1), MemStore())
        var t = 0L
        d.process("p", t, bg)
        repeat(3) {
            t += min5
            d.process("p", t, bg)
        }
        assertThat(d.currentIntervalMs("p")).isEqualTo(min5)
    }

    @Test
    fun `notification spam with wrong-high seed is still rejected`() {
        // Even with seed=15 wrong for a 5min sensor, same-value reposts within window must still be deduped.
        // Wrong-high seed is a conservative over-estimate; snap-up does not apply and the seed
        // stays. (Packages with wrong-high seeds must be corrected in the JSON.)
        val d = GlucoseDeduplicator(configWith("p" to 15), MemStore())
        d.process("p", 0L, bg)
        assertThat(d.process("p", 30_000L, bg)).isFalse()
        assertThat(d.process("p", min1, bg)).isFalse()
        assertThat(d.process("p", min3, bg)).isFalse()
    }

    @Test
    fun `snap function boundaries`() {
        assertThat(GlucoseDeduplicator.snapGapToKnownIntervalMs(0L)).isEqualTo(min1)
        assertThat(GlucoseDeduplicator.snapGapToKnownIntervalMs(2 * min1)).isEqualTo(min1)
        assertThat(GlucoseDeduplicator.snapGapToKnownIntervalMs(2 * min1 + 1)).isEqualTo(min3)
        assertThat(GlucoseDeduplicator.snapGapToKnownIntervalMs(4 * min1)).isEqualTo(min3)
        assertThat(GlucoseDeduplicator.snapGapToKnownIntervalMs(4 * min1 + 1)).isEqualTo(min5)
        assertThat(GlucoseDeduplicator.snapGapToKnownIntervalMs(10 * min1)).isEqualTo(min5)
        assertThat(GlucoseDeduplicator.snapGapToKnownIntervalMs(10 * min1 + 1)).isEqualTo(min15)
        assertThat(GlucoseDeduplicator.snapGapToKnownIntervalMs(60 * min1)).isEqualTo(min15)
    }
}
