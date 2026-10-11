package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalSampleMTR
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.sqrt

/**
 * The maths behind `IosHealthKitPhysioRepository`, with no HealthKit call in it.
 *
 * HealthKit cannot be driven from a test: the simulator holds no health data and the real samples
 * need a paired Apple Watch. So everything that decides a number lives here, takes plain data
 * classes, and is covered by `IosHealthKitPhysioMathTest`. The repository around it only turns
 * HealthKit objects into these plain classes and back.
 *
 * Every function here answers the same question its Health Connect twin answers in
 * `AIMIPhysioDataRepositoryMTR`, because AIMI must dose the same on both phones.
 */
internal object IosHealthKitPhysioMath {

    // ═══════════════════════════════════════════════════════════════════════
    // SLEEP
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * `HKCategoryValueSleepAnalysis`, copied here as plain numbers so a test does not need
     * HealthKit on the classpath. The values are fixed by Apple and cannot change.
     */
    const val SLEEP_IN_BED = 0L
    const val SLEEP_ASLEEP_UNSPECIFIED = 1L
    const val SLEEP_AWAKE = 2L
    const val SLEEP_ASLEEP_CORE = 3L
    const val SLEEP_ASLEEP_DEEP = 4L
    const val SLEEP_ASLEEP_REM = 5L

    /** Values that mean "really asleep". `inBed` and `awake` are not sleep. */
    private val ASLEEP_VALUES = setOf(
        SLEEP_ASLEEP_UNSPECIFIED,
        SLEEP_ASLEEP_CORE,
        SLEEP_ASLEEP_DEEP,
        SLEEP_ASLEEP_REM,
    )

    /**
     * Two sleep samples closer than this belong to the same night.
     *
     * Health Connect gives one `SleepSessionRecord` per night, and the Android code uses its whole
     * span, brief wakings included. HealthKit has no session: it gives one sample per stage. So the
     * samples are glued back into nights here, and an hour is the bridge. A nap after a one hour
     * break is a second night here and a second session on Health Connect, so the two agree.
     */
    const val SESSION_BRIDGE_MS = 60L * 60L * 1000L

    /** The same flat estimate the Android side writes; see [sleepFrom]. */
    const val SLEEP_EFFICIENCY = 0.85

    /** One HealthKit `sleepAnalysis` sample, reduced to what the maths needs. */
    data class SleepSegment(
        val startMs: Long,
        val endMs: Long,
        val categoryValue: Long,
    )

    /** One night, after the samples have been glued back together. */
    data class SleepSession(
        val startMs: Long,
        val endMs: Long,
    )

    /**
     * Builds the shared [SleepDataMTR] from HealthKit sleep samples.
     *
     * Follows `AIMIPhysioDataRepositoryMTR.fetchSleepData` field by field:
     *
     * - `durationHours` is the sum of the night spans, so a broken night counts its interior
     *   waking, exactly as a Health Connect session span does.
     * - `efficiency` is the same flat [SLEEP_EFFICIENCY] guess. Neither phone measures it.
     * - **The stage minutes stay at zero, even though HealthKit knows them.** This looks like
     *   throwing data away and it is deliberate: `AIMIPhysioFeatureExtractorMTR` branches on
     *   `deepSleepMinutes <= 0` and takes a different sleep quality score when it is set. Filling
     *   it here would make the same night dose differently on an iPhone than on an Android phone.
     *   When Android starts reading stages, both sides change together.
     *
     * Stage samples are preferred. If the window holds none - an iPhone with no Apple Watch, where
     * Sleep Focus writes only `inBed` - the `inBed` samples are used instead, the way the Android
     * side falls back from the heart rate aggregate to `RestingHeartRateRecord`. Health Connect
     * would have had a session for that night too, so dropping it would be the real difference.
     *
     * @param nowMs wall clock, used to find a night that is still running.
     * @return null when the window holds no usable sleep, which is what the caller expects.
     */
    fun sleepFrom(segments: List<SleepSegment>, nowMs: Long): SleepDataMTR? {
        val asleep = segments.filter { it.categoryValue in ASLEEP_VALUES }
        val usable = asleep.ifEmpty { segments.filter { it.categoryValue == SLEEP_IN_BED } }
        val sessions = mergeSessions(usable)
        if (sessions.isEmpty()) return null

        val totalHours = sessions.sumOf { (it.endMs - it.startMs) / 3_600_000.0 }
        val ongoing = sessions.firstOrNull { nowMs >= it.startMs && nowMs <= it.endMs }
        return SleepDataMTR(
            startTime = sessions.first().startMs,
            endTime = sessions.last().endMs,
            durationHours = totalHours,
            efficiency = SLEEP_EFFICIENCY,
            deepSleepMinutes = 0,
            remSleepMinutes = 0,
            lightSleepMinutes = 0,
            awakeMinutes = 0,
            fragmentationScore = 0.0,
            ongoingStartMs = ongoing?.startMs,
            ongoingEndMs = ongoing?.endMs,
        )
    }

    /**
     * Glues overlapping and nearly touching samples into nights.
     *
     * Overlap matters: an Apple Watch and a third party sleep app can both write the same night,
     * and summing both would report twice the sleep.
     */
    fun mergeSessions(segments: List<SleepSegment>): List<SleepSession> {
        val ordered = segments
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }
        if (ordered.isEmpty()) return emptyList()

        val sessions = mutableListOf<SleepSession>()
        var start = ordered.first().startMs
        var end = ordered.first().endMs
        for (index in 1 until ordered.size) {
            val segment = ordered[index]
            if (segment.startMs <= end + SESSION_BRIDGE_MS) {
                if (segment.endMs > end) end = segment.endMs
            } else {
                sessions += SleepSession(start, end)
                start = segment.startMs
                end = segment.endMs
            }
        }
        sessions += SleepSession(start, end)
        return sessions
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HRV - RMSSD FROM BEAT TO BEAT INTERVALS
    // ═══════════════════════════════════════════════════════════════════════

    /** Shortest believable beat to beat interval, 200 beats per minute. */
    const val MIN_RR_MS = 300.0

    /** Longest believable beat to beat interval, 30 beats per minute. */
    const val MAX_RR_MS = 2000.0

    /** Fewer successive differences than this is noise, not an RMSSD. */
    const val MIN_SUCCESSIVE_DIFFS = 5

    /**
     * One beat out of an `HKHeartbeatSeriesSample`.
     *
     * @param offsetSeconds seconds after the series start, as HealthKit reports it.
     * @param precededByGap HealthKit lost one or more beats before this one.
     */
    data class Heartbeat(
        val offsetSeconds: Double,
        val precededByGap: Boolean,
    )

    /**
     * RMSSD in milliseconds, the real one.
     *
     * RMSSD is the root mean square of the differences between successive beat to beat intervals.
     * It is **not** SDNN, which is the metric HealthKit publishes as a ready made quantity. The two
     * are different numbers from the same beats, and `hrvRmssd` scales insulin through `smbMult`,
     * so the field has to hold what its name says.
     *
     * Two things break a run of beats, and both end the chain rather than bridging it:
     *
     * - `precededByGap`. The beat after a gap is not one interval after its predecessor, it is
     *   several. Differencing across it invents a huge interval and a huge RMSSD.
     * - An interval outside [MIN_RR_MS] to [MAX_RR_MS]. The Apple Watch reads pulse from light, not
     *   voltage, and drops beats; a measured 4 second interval is a miss, not a heart that stopped.
     *
     * @return null when the series holds fewer than [MIN_SUCCESSIVE_DIFFS] usable differences.
     *   Null means no data and the caller must keep it as absence. A zero would read as a real
     *   measurement of a body under total stress.
     */
    fun rmssdMs(beats: List<Heartbeat>): Double? {
        var sumSquares = 0.0
        var count = 0
        var previousOffset: Double? = null
        var previousRr: Double? = null

        for (beat in beats) {
            val lastOffset = previousOffset
            previousOffset = beat.offsetSeconds
            if (beat.precededByGap || lastOffset == null) {
                previousRr = null
                continue
            }
            val rr = (beat.offsetSeconds - lastOffset) * 1000.0
            if (rr < MIN_RR_MS || rr > MAX_RR_MS) {
                previousRr = null
                continue
            }
            val earlierRr = previousRr
            if (earlierRr != null) {
                val difference = rr - earlierRr
                sumSquares += difference * difference
                count++
            }
            previousRr = rr
        }

        if (count < MIN_SUCCESSIVE_DIFFS) return null
        return sqrt(sumSquares / count)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MORNING RESTING HEART RATE WINDOWS
    // ═══════════════════════════════════════════════════════════════════════

    /** Local hour the morning window opens, as on Android. */
    const val MORNING_WINDOW_START_HOUR = 4

    /** Local hour the morning window closes, as on Android. */
    const val MORNING_WINDOW_END_HOUR = 10

    /** One local morning, in wall clock milliseconds. */
    data class MorningWindow(
        val startMs: Long,
        val endMs: Long,
    )

    /**
     * The local morning windows the resting heart rate is read over.
     *
     * Copies `AIMIPhysioDataRepositoryMTR.fetchMorningRHR`: walk back [daysBack] whole days from
     * [nowMs], take the local date of each step, and cut 04:00 to 10:00 local out of it. A window
     * that has not opened yet is skipped, so the current day only counts after 04:00.
     *
     * The local date is taken per day rather than by subtracting calendar days, which is what the
     * Android code does, so a daylight saving change moves both the same way.
     */
    fun morningWindows(nowMs: Long, daysBack: Int, zone: TimeZone): List<MorningWindow> {
        val windows = mutableListOf<MorningWindow>()
        for (index in 0 until daysBack) {
            val dayStart = Instant.fromEpochMilliseconds(nowMs - index * 24L * 60L * 60L * 1000L)
            val localDate = dayStart.toLocalDateTime(zone).date
            val start = LocalDateTime(localDate, LocalTime(MORNING_WINDOW_START_HOUR, 0))
                .toInstant(zone)
                .toEpochMilliseconds()
            if (start > nowMs) continue
            val end = LocalDateTime(localDate, LocalTime(MORNING_WINDOW_END_HOUR, 0))
                .toInstant(zone)
                .toEpochMilliseconds()
            windows += MorningWindow(start, end)
        }
        return windows
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WRIST TEMPERATURE
    // ═══════════════════════════════════════════════════════════════════════

    /** Below this many nights the personal baseline is a guess, so no thermal data is reported. */
    const val MIN_BASELINE_SAMPLES = 5

    /** One `appleSleepingWristTemperature` sample: an absolute temperature, not a deviation. */
    data class WristTemperatureSample(
        val timestampMs: Long,
        val celsius: Double,
        val dataOrigin: String,
    )

    /**
     * Turns absolute wrist temperatures into the deviations the shared code expects.
     *
     * This is the one place where the two platforms hand over different raw material.
     * `SkinTemperatureRecord` on Health Connect already carries the writer's own baseline, so the
     * Android side only subtracts it. HealthKit publishes the plain temperature and keeps its
     * baseline private, so the baseline is worked out here: the median of every sample in
     * [baselineSamples], which the repository reads over a much longer stretch than the window it
     * reports. A median over several weeks of nights is the same idea as the writer's baseline,
     * and `ThermalBeliefEngine` only ever looks at differences from a baseline and at slopes, so a
     * constant offset between the two methods cannot change a verdict.
     *
     * Under [MIN_BASELINE_SAMPLES] nights there is no honest baseline, so the answer is empty and
     * the caller falls through to its next thermal source.
     *
     * @param baselineSamples every wrist temperature over the long baseline stretch.
     * @param windowStartMs only samples at or after this are reported back.
     */
    fun wristTemperatureDeltas(
        baselineSamples: List<WristTemperatureSample>,
        windowStartMs: Long,
    ): List<ThermalSampleMTR> {
        if (baselineSamples.size < MIN_BASELINE_SAMPLES) return emptyList()
        val sorted = baselineSamples.map { it.celsius }.sorted()
        val baseline = sorted[sorted.size / 2]
        return baselineSamples
            .filter { it.timestampMs >= windowStartMs }
            .sortedBy { it.timestampMs }
            .map { sample ->
                ThermalSampleMTR(
                    timestampMs = sample.timestampMs,
                    deltaCelsius = sample.celsius - baseline,
                    // Apple only writes this type from the watch on the wrist, so it is never
                    // anything else. Health Connect has to ask the record.
                    measurementLocation = "WRIST",
                    dataOrigin = sample.dataOrigin,
                )
            }
    }
}
