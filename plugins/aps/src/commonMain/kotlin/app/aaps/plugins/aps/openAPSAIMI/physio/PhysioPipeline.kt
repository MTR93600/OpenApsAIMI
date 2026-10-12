package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.keys.BooleanKey
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.steps.UnifiedActivityProviderMTR
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The physiological analysis run, from raw readings to a stored context.
 *
 * One run is five steps:
 *
 * 1. fetch the raw readings, and enrich them from the phone database when the platform health
 *    store gave nothing;
 * 2. extract features;
 * 3. update the person's own rolling baseline;
 * 4. analyse the context, and ask the language model for a narrative when that is switched on;
 * 5. store the context.
 *
 * Step 4 reads what step 3 returned, so the baseline feed cannot be lifted out of the chain. That
 * is why the whole run lives here, in shared code, and both phones run these same lines.
 *
 * ### This class is the only thing that feeds the baseline
 *
 * [AIMIPhysioBaselineModelMTR.updateBaseline] is called from [run] and from nowhere else in the
 * repository. The history is keyed by the time of the run, so a second feed would add the same
 * readings twice and move the mean and the spread that every person-relative reading is taken
 * against - and those readings reach the dose. So exactly one owner starts a run on each platform:
 *
 * - **Android**: `AIMIPhysioManagerMTR`, from its WorkManager jobs. That class only exists in
 *   androidMain, so it cannot start a run anywhere else.
 * - **iOS**: `IosPhysioPipelineFeed`, from the health context refresh. See [PhysioPipelineFeed],
 *   whose Android half does nothing on purpose.
 *
 * ### Scheduling is not here
 *
 * No timer, no cadence, no platform type. Who runs this, and how often, belongs to the owner above.
 *
 * @author MTR & Lyra AI - AIMI Physiological Intelligence
 */
@SingleIn(AppScope::class)
class PhysioPipeline @Inject constructor(
    private val dataSource: AimiPhysioDataSource,
    private val featureExtractor: AIMIPhysioFeatureExtractorMTR,
    private val baselineModel: AIMIPhysioBaselineModelMTR,
    private val contextEngine: AIMIPhysioContextEngineMTR,
    private val contextStore: AIMIPhysioContextStoreMTR,
    private val llmAnalyzer: AIMILLMPhysioAnalyzerMTR, // Optional
    private val unifiedActivityProvider: UnifiedActivityProviderMTR,
    private val sp: KeyValueStore,
    private val aapsLogger: AAPSLogger,
) {

    /** Held for the length of one run. See [run]. */
    private val running = SingleFlightGate()


    companion object {

        /**
         * Kept as "PhysioManager", the tag this run has always logged under.
         *
         * The lines below are what a support package is read with. Renaming the tag would break
         * every saved search over older logs for no gain.
         */
        private const val TAG = "PhysioManager"

        /** Arms the per-day HRV baseline. Off by default - see `hrvPerDayBaseline`. */
        const val PREF_KEY_HRV_PER_DAY_BASELINE = "aimi_hrv_baseline_per_night"

        const val PREF_KEY_LAST_UPDATE = "aimi_physio_last_update_ms"

        /**
         * Which HRV metric a set of readings carries.
         *
         * The baseline keeps one metric at a time and drops its history when the metric changes
         * (see [AIMIPhysioBaselineModelMTR.updateBaseline]), so this answer has to come from the
         * readings themselves - an iPhone sends SDNN, an Android phone sends RMSSD.
         *
         * When no reading carried a value, [previous] is given back rather than a default. A tick
         * that simply found no HRV must not look like a metric change, or the history would be
         * dropped on an empty read and the person would start from cold for nothing.
         */
        fun hrvMetricOf(samples: List<HRVDataMTR>, previous: HrvMetric): HrvMetric =
            samples.lastOrNull { it.hasValidData() }?.metric ?: previous

        /**
         * Merges vitals from the phone database into a raw read that came back empty.
         *
         * Pure, so the rule can be read and tested on its own. Readings that are already there win:
         * this only fills a gap, it never replaces a platform reading. Both ambient values at zero
         * means there was nothing to merge, and the raw read is given back untouched.
         */
        fun enrichRaw(raw: RawPhysioDataMTR, ambientHeartRateBpm: Int, ambientSteps: Int): RawPhysioDataMTR {
            if (raw.hasAnyData()) return raw
            if (ambientHeartRateBpm <= 0 && ambientSteps <= 0) return raw
            return raw.copy(
                ambientHeartRateBpm = ambientHeartRateBpm,
                ambientStepsAggregated = ambientSteps,
            )
        }
    }

    /** Carried between runs, so the feature extractor can work out a trend. */
    private var previousFeatures: PhysioFeaturesMTR? = null

    /**
     * The metric the last run fed the baseline with.
     *
     * Starts at RMSSD, the same value [AIMIPhysioBaselineModelMTR] starts from, and both live only
     * in memory, so they come back from a restart in step with each other.
     */
    private var lastHrvMetric: HrvMetric = HrvMetric.RMSSD

    /** When the last successful run finished, in wall clock milliseconds. 0 means never. */
    var lastUpdateTimeMs: Long = 0
        private set

    /** Reads the stored time of the last run back into memory. Called when the owner starts. */
    fun restoreLastUpdateTime(): Long {
        lastUpdateTimeMs = sp.getLong(PREF_KEY_LAST_UPDATE, 0)
        return lastUpdateTimeMs
    }

    /** Whether the person switched the physio assistant on. The owner checks this before a run. */
    fun isPhysioAssistantEnabled(): Boolean =
        sp.getBoolean(BooleanKey.AimiPhysioAssistantEnable.key, false)

    private fun isLLMEnabled(): Boolean =
        sp.getBoolean(BooleanKey.AimiPhysioLLMAnalysisEnable.key, false)

    /**
     * Whether the HRV baseline buckets by day instead of by run. **Off by default.**
     *
     * Off, the baseline keeps one point per pipeline run and caps at 28 of them. Both platforms
     * run every 15 minutes, so that window is seven hours, not the seven days the class describes -
     * and `HrvDepression` divides by its spread, so a reading barely below normal reads as a large
     * deviation. On, a day contributes one value, its median, and the window is really seven days.
     *
     * It is a switch and not a straight fix because turning it on changes the mean and the spread,
     * which changes `hrvDepression`, which reaches a dose. It has to be armed deliberately, on a
     * phone whose owner is watching, not arrive with an update.
     */
    private fun hrvPerDayBaseline(): Boolean =
        sp.getBoolean(PREF_KEY_HRV_PER_DAY_BASELINE, false)

    /**
     * Runs the five steps once.
     *
     * Never throws: every failure is logged and answered with `false`, because this runs from a
     * background job and a crash there would be silent anyway.
     *
     * @param daysBack how far back the raw readings are asked for.
     * @param runLLM whether the optional language model step may run. It still only runs when the
     *   person switched it on and the features hold real data.
     * @return true when a context was stored.
     */
    /**
     * Runs the pipeline, unless it is already running.
     *
     * The guard is here rather than in either platform's trigger, because the thing it protects is
     * shared: step 3 feeds `AIMIPhysioBaselineModelMTR`, whose history is keyed by the time of the
     * run. Two overlapping runs therefore add two points for one reading, and the mean and spread
     * they produce are what `HrvDepression` reads - which reaches an insulin multiplier.
     *
     * Android had no such guard before this, and could overlap: five paths reach the run (the
     * realtime, metabolic and daily workers, the bootstrap worker and the watchdog), and the
     * 15-minute and 30-minute workers line up every half hour. iOS guarded its own trigger. One
     * guard in the shared code covers both, and neither platform can forget it.
     *
     * A skipped call answers `false`. The caller's next scheduled run comes round soon, and a
     * skipped duplicate is strictly better than a double-counted sample.
     */
    suspend fun run(daysBack: Int, runLLM: Boolean): Boolean {
        if (!running.tryEnter()) {
            aapsLogger.info(LTag.APS, "[$TAG] ⏭️ Pipeline already running - this call is skipped")
            return false
        }
        return try {
            runExclusive(daysBack, runLLM)
        } finally {
            running.exit()
        }
    }

    private suspend fun runExclusive(daysBack: Int, runLLM: Boolean): Boolean {
        val startTime = aimiWallClockMs()
        var fetchMs = 0L
        var extractMs = 0L
        var analyzeMs = 0L

        aapsLogger.info(LTag.APS, "[$TAG] 🔄 Pipeline Start (daysBack=$daysBack runLLM=$runLLM)")

        try {
            val hcReady = dataSource.isAvailable()
            if (!hcReady) {
                aapsLogger.warn(LTag.APS, "[$TAG] Health Connect SDK/client not fully available — HC reads may be empty; Wear/phone DB can still feed the pipeline")
            }

            // Step 1: Fetch raw data (HC) + optional enrichment from unified DB (Wear / HC sync rows)
            val t0 = aimiWallClockMs()
            val rawData = try {
                enrichRawFromUnifiedIfNeeded(dataSource.fetchAllData(daysBack = daysBack))
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "[$TAG] ❌ Fetch error", e)
                return false
            }
            fetchMs = aimiWallClockMs() - t0

            if (!rawData.hasAnyData()) {
                aapsLogger.warn(LTag.APS, "[$TAG] ⚠️ No physiological data available")
                // Don't wipe context immediately, maybe transient
                return false
            }

            // Step 2: Extract features
            val t1 = aimiWallClockMs()
            val features = featureExtractor.extractFeatures(rawData, previousFeatures)
            previousFeatures = features
            extractMs = aimiWallClockMs() - t1

            // Step 3: Update baseline. The metric comes from the readings this run fetched, so the
            // history is never a mix of RMSSD and SDNN.
            val hrvMetric = hrvMetricOf(rawData.hrv, lastHrvMetric)
            lastHrvMetric = hrvMetric
            val baseline = baselineModel.updateBaseline(features, hrvMetric, hrvPerDayBaseline())

            // Step 4: Analyze context
            val t2 = aimiWallClockMs()
            var context = contextEngine.analyze(features, baseline)
            analyzeMs = aimiWallClockMs() - t2

            // 🤖 Step 4b: Cognitive Analysis (LLM - Optional)
            if (runLLM && isLLMEnabled()) {
                // Only run LLM if Data is valid to avoid hallucination on empty data
                if (features.hasValidData) {
                    val narrative = llmAnalyzer.analyze(features, baseline, context)
                    if (narrative.isNotBlank()) {
                        context = context.copy(narrative = narrative)
                        aapsLogger.info(LTag.APS, "[$TAG] 🤖 LLM Insight: $narrative")
                    }
                }
            }

            // Step 5: Store
            contextStore.updateContext(context, baseline)

            lastUpdateTimeMs = aimiWallClockMs()
            sp.putLong(PREF_KEY_LAST_UPDATE, lastUpdateTimeMs)

            val totalMs = aimiWallClockMs() - startTime

            // STRUCTURED LOG (Production Level)
            aapsLogger.info(
                LTag.APS,
                "[$TAG] ✅ RUN COMPLETE | State: ${context.state} | Conf: ${(context.confidence * 100).toInt()}% | " +
                    "Qual: ${(features.dataQuality * 100).toInt()}% | " +
                    "Counts: Sleep=${if (rawData.sleep?.hasValidData() == true) "Yes" else "No"}, HRV=${rawData.hrv.size}, RHR=${rawData.rhr.size}, Steps=${if (rawData.steps > 0) "Yes" else "No"} | " +
                    "Timings: Fetch=${fetchMs}ms, Extr=${extractMs}ms, Analysis=${analyzeMs}ms (Total: ${totalMs}ms)"
            )

            return true
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] ❌ Pipeline CRASH", e)
            return false
        }
    }

    /**
     * Forgets everything this run keeps, and clears what it writes.
     *
     * Used when the person asks for a fresh start. The baseline history goes with it, which means
     * a few days of cold start before person-relative readings mean anything again.
     */
    fun reset() {
        aapsLogger.info(LTag.APS, "[$TAG] 🔄 Resetting Physiological Manager")
        contextStore.clear()
        baselineModel.clearHistory()
        dataSource.clearCache()
        previousFeatures = null
        lastHrvMetric = HrvMetric.RMSSD
        lastUpdateTimeMs = 0
        sp.putLong(PREF_KEY_LAST_UPDATE, 0)
    }

    /**
     * When the platform health store returns no usable series but Wear / phone / HC-sync rows exist
     * in the DB, merge those vitals so the pipeline and context store can still update
     * (Mar 23 regression fix).
     */
    private fun enrichRawFromUnifiedIfNeeded(raw: RawPhysioDataMTR): RawPhysioDataMTR {
        if (raw.hasAnyData()) return raw
        val now = aimiWallClockMs()
        val hr = unifiedActivityProvider.getLatestHeartRate(60 * 60 * 1000L)?.bpm?.toInt() ?: 0
        val stepsWin = unifiedActivityProvider.getStepsTotalSince(now - 24 * 60 * 60 * 1000L)?.steps ?: 0
        val enriched = enrichRaw(raw, hr, stepsWin)
        if (enriched !== raw) {
            aapsLogger.info(LTag.APS, "[$TAG] Enriched physio input from unified DB: ambientHr=$hr ambientSteps24hWindow=$stepsWin")
        }
        return enriched
    }
}
