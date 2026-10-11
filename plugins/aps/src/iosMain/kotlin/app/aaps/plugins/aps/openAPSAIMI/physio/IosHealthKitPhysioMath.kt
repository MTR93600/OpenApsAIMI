package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalSampleMTR
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

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
    // HRV - SDNN, THE ONLY HRV TYPE HEALTHKIT HAS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * One `heartRateVariabilitySDNN` sample, already turned into milliseconds.
     *
     * HealthKit stores this quantity in seconds, so the repository converts it before it gets
     * here. Milliseconds is what both platforms carry in [HRVDataMTR].
     */
    data class SdnnSample(
        val timestampMs: Long,
        val milliseconds: Double,
        val sourceName: String,
    )

    /**
     * Turns HealthKit SDNN samples into shared [HRVDataMTR] points.
     *
     * HealthKit defines exactly one HRV type, `heartRateVariabilitySDNN`. Health Connect defines
     * an RMSSD record instead. The same wearable apps - Garmin, Oura, Samsung - fill whichever one
     * their phone offers, so the two platforms genuinely hand over different metrics and neither
     * can be converted into the other.
     *
     * So the metric is carried along rather than hidden: the value goes in the `sdnn` field and
     * the sample is tagged [HrvMetric.SDNN]. The `rmssd` field is left at zero on purpose, so code
     * that reads it by name can never mistake an SDNN for an RMSSD. Consumers compare the reading
     * against the person's own rolling baseline, which is what makes the metric identity stop
     * mattering: "low" means low for this person.
     *
     * A reading that is zero, negative or not a number is dropped rather than passed on. It is not
     * a measurement, and a zero would read as a body under total stress. An empty answer means no
     * HRV data, which the dose path already handles.
     */
    fun hrvFrom(samples: List<SdnnSample>): List<HRVDataMTR> =
        samples
            .filter { it.milliseconds.isFinite() && it.milliseconds > 0.0 }
            .sortedBy { it.timestampMs }
            .map { sample ->
                HRVDataMTR(
                    timestamp = sample.timestampMs,
                    sdnn = sample.milliseconds,
                    metric = HrvMetric.SDNN,
                    source = "HealthKit(${sample.sourceName})",
                )
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
