package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt0
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt3
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalLearner
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalNeuralLearner
import app.aaps.plugins.aps.openAPSAIMI.learning.UnifiedReactivityLearner
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage

/**
 * Byte lock for an empty store. `Storage: in memory` is the report of the shared test store.
 * A directory reports its own path; the factor, the multiplier and the governance line do not change.
 */
internal const val COLD_LEARNER_NIGHT_TRACE =
    "═══════════════════════════════\n" +
        "🛡️ AIMI LEARNERS HEALTH\n" +
        "Storage: in memory\n" +
        "UnifiedReactivity: factor=1.000\n" +
        "BasalLearner: multiplier=1.000\n" +
        "PkPdEstimator: runtime-only\n" +
        "═══════════════════════════════\n" +
        "🧭 BASAL_GOV[FINAL]: action=WARMUP conf=0.00 n=0 hypo=0.00 hypoG=0.00 hypoAdj=0.00 " +
        "ant=0.00 wMean=1.00 high=0.00 mae=0.0 latch=false floorB=- floorA=- " +
        "wBolus=0.00U wCob=0g reason=Warmup"

/**
 * Health block printed by [logLearnersHealth](app.aaps.plugins.aps.openAPSAIMI.DetermineBasalAIMI2).
 * The storage report is the platform's own string. The two factors are the learners'.
 */
internal fun learnerHealthLines(
    storageReport: String,
    reactivityFactor: Double,
    basalMultiplier: Double,
): List<String> = listOf(
    "═══════════════════════════════",
    "🛡️ AIMI LEARNERS HEALTH",
    "Storage: $storageReport",
    "UnifiedReactivity: factor=${aimiFmt3(reactivityFactor)}",
    "BasalLearner: multiplier=${aimiFmt3(basalMultiplier)}",
    "PkPdEstimator: runtime-only",
    "═══════════════════════════════",
)

/** The `BASAL_GOV` console line. Tag, bolus and COB stay arguments of the caller. */
internal fun basalGovLine(
    govTag: String,
    gov: BasalNeuralLearner.GovernanceSnapshot,
    windowBolusU: Double,
    windowCobG: Double,
): String =
    "🧭 BASAL_GOV[$govTag]: action=${gov.action} conf=${aimiFmt2(gov.confidence)} " +
        "n=${gov.sampleCount} hypo=${aimiFmt2(gov.hypoRate)} hypoG=${aimiFmt2(gov.hypoRateGovernance)} " +
        "hypoAdj=${aimiFmt2(gov.hypoGovernanceAdjusted)} ant=${aimiFmt2(gov.anticipationRelief)} " +
        "wMean=${aimiFmt2(gov.meanGovernanceWeight)} high=${aimiFmt2(gov.highRate)} " +
        "mae=${aimiFmt1(gov.meanAbsTargetError)} latch=${gov.hypoHoldLatched} " +
        "floorB=${gov.activeBasalFloor?.let { aimiFmt2(it) } ?: "-"} " +
        "floorA=${gov.activeAggressivenessFloor?.let { aimiFmt2(it) } ?: "-"} " +
        "wBolus=${if (windowBolusU.isFinite()) aimiFmt2(windowBolusU) else "?"}U " +
        "wCob=${if (windowCobG.isFinite()) aimiFmt0(windowCobG) else "?"}g " +
        "reason=${gov.reason}"

/**
 * Cold night lines: the three learners read their own files and have not been trained.
 *
 * Missing `aimi_basal_learner.json`, `aimi_unified_reactivity.json`, the two weight files and
 * `basal_adaptive_records.csv` leave the multipliers at 1.0 and governance at `WARMUP` / `Warmup`.
 * A night tick has no SMB and no carbs, so the window columns are 0. The tag is `FINAL`, the one
 * the Android shell prints on the path that also logs learner health.
 *
 * This does not call `process` or `updateLearning`. Those write a training row from a glucose
 * sample this scene does not have.
 */
internal fun coldLearnerNightLines(
    storage: AimiStorage,
    preferences: Preferences,
    persistence: PersistenceLayer,
    dateUtil: DateUtil,
    log: AAPSLogger,
): List<String> {
    val basal = BasalLearner(log, storage)
    val reactivity = UnifiedReactivityLearner(persistence, dateUtil, preferences, log, storage)
    val neural = BasalNeuralLearner(preferences, storage, log)
    return learnerHealthLines(
        storageReport = storage.healthReport(),
        reactivityFactor = reactivity.getCombinedFactor(),
        basalMultiplier = basal.getMultiplier(),
    ) + basalGovLine(
        govTag = "FINAL",
        gov = neural.getGovernanceSnapshot(),
        windowBolusU = 0.0,
        windowCobG = 0.0,
    )
}
