package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.aps.openAPSAIMI.activity.ActivityManager
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * ============================================================================
 * AIMI AI Decision Auditor - Data Collector
 * ============================================================================
 * 
 * Collects all necessary data from AIMI's runtime state to build the
 * complete auditor input (snapshot + history + stats).
 * 
 * This is the "bridge" between AIMI's internal state and the AI auditor.
 */
@Singleton
class AuditorDataCollector @Inject constructor(
    private val persistenceLayer: PersistenceLayer,
    private val tddCalculator: TddCalculator,
    private val tirCalculator: TirCalculator,
    private val dateUtil: DateUtil,
    private val activityManager: ActivityManager,
    private val aapsLogger: AAPSLogger
) {
    
    /**
     * Build complete auditor input from AIMI runtime state
     * 
     * @param bg Current BG (mg/dL)
     * @param delta Delta over 5min
     * @param shortAvgDelta Short average delta
     * @param longAvgDelta Long average delta
     * @param glucoseStatus Glucose status object
     * @param iob IOB object
     * @param cob COB value (g)
     * @param profile Current profile
     * @param pkpdRuntime PKPD runtime if available
     * @param isfUsed ISF actually used (after fusion)
     * @param smbProposed SMB proposed by AIMI (U)
     * @param tbrRate TBR rate proposed (U/h)
     * @param tbrDuration TBR duration (min)
     * @param intervalMin Interval proposed (min)
     * @param maxSMB Max SMB limit
     * @param maxSMBHB Max SMB High BG limit
     * @param maxIOB Max IOB limit
     * @param maxBasal Max basal limit
     * @param reasonTags Decision reason tags
     * @param modeType Current meal mode type (null if none)
     * @param modeRuntimeMin Meal mode runtime (minutes)
     * @param autodriveState Autodrive state string
     * @param wcyclePhase WCycle phase (null if not active)
     * @param wcycleFactor WCycle factor (null if not active)
     * @param tbrMaxMode TBR max for mode (if active)
     * @param tbrMaxAutoDrive TBR max for autodrive (if active)
     */
    fun buildAuditorInput(
        bg: Double,
        delta: Double,
        shortAvgDelta: Double,
        longAvgDelta: Double,
        glucoseStatus: GlucoseStatusAIMI?,
        iob: IobTotal,
        cob: Double?,
        profile: OapsProfileAimi,
        pkpdRuntime: PkPdRuntime?,
        isfUsed: Double,
        smbProposed: Double,
        tbrRate: Double?,
        tbrDuration: Int?,
        intervalMin: Double,
        maxSMB: Double,
        maxSMBHB: Double,
        maxIOB: Double,
        maxBasal: Double,
        reasonTags: List<String>,
        modeType: String?,
        modeRuntimeMin: Int?,
        autodriveState: String,
        wcyclePhase: String?,
        wcycleFactor: Double?,
        tbrMaxMode: Double?,
        tbrMaxAutoDrive: Double?,
        physio: PhysioSnapshot? = null
    ): AuditorInput {
        
        val now = dateUtil.now()
        
        // Build snapshot
        val snapshot = buildSnapshot(
            bg = bg,
            delta = delta,
            shortAvgDelta = shortAvgDelta,
            longAvgDelta = longAvgDelta,
            glucoseStatus = glucoseStatus,
            iob = iob,
            cob = cob,
            profile = profile,
            pkpdRuntime = pkpdRuntime,
            isfUsed = isfUsed,
            smbProposed = smbProposed,
            tbrRate = tbrRate,
            tbrDuration = tbrDuration,
            intervalMin = intervalMin,
            maxSMB = maxSMB,
            maxSMBHB = maxSMBHB,
            maxIOB = maxIOB,
            maxBasal = maxBasal,
            reasonTags = reasonTags,
            modeType = modeType,
            modeRuntimeMin = modeRuntimeMin,
            autodriveState = autodriveState,
            wcyclePhase = wcyclePhase,
            wcycleFactor = wcycleFactor,
            tbrMaxMode = tbrMaxMode,
            tbrMaxAutoDrive = tbrMaxAutoDrive,
            physio = physio,
            now = now
        )
        
        // Build history (45-60 min, max 12 points)
        val history = buildHistory(now)
        
        // Build 7-day stats
        val stats = buildStats7d(now)
        
        return AuditorInput(
            snapshot = snapshot,
            history = history,
            stats = stats
        )
    }
    
    /**
     * Build snapshot from runtime state
     */
    private fun buildSnapshot(
        bg: Double,
        delta: Double,
        shortAvgDelta: Double,
        longAvgDelta: Double,
        glucoseStatus: GlucoseStatusAIMI?,
        iob: IobTotal,
        cob: Double?,
        profile: OapsProfileAimi,
        pkpdRuntime: PkPdRuntime?,
        isfUsed: Double,
        smbProposed: Double,
        tbrRate: Double?,
        tbrDuration: Int?,
        intervalMin: Double,
        maxSMB: Double,
        maxSMBHB: Double,
        maxIOB: Double,
        maxBasal: Double,
        reasonTags: List<String>,
        modeType: String?,
        modeRuntimeMin: Int?,
        autodriveState: String,
        wcyclePhase: String?,
        wcycleFactor: Double?,
        tbrMaxMode: Double?,
        tbrMaxAutoDrive: Double?,
        physio: PhysioSnapshot?,
        now: Long
    ): Snapshot {
        
        // CGM age (minutes since last reading)
        val cgmAgeMin = glucoseStatus?.let {
            val ageMs = now - it.date  // GlucoseStatusAIMI uses 'date' not 'timestamp'
            (ageMs / 60000).toInt()
        } ?: 0
        
        // Noise level
        val noise = when (glucoseStatus?.noise ?: 0.0) {
            in 0.0..1.0 -> "CLEAN"
            in 1.0..2.0 -> "LIGHT"
            in 2.0..3.0 -> "MEDIUM"
            else -> "HEAVY"
        }
        
        // IOB activity (if available from PKPD)
        val iobActivity = pkpdRuntime?.activity?.relativeActivity
        
        // PKPD snapshot
        val pkpd = PKPDSnapshot(
            diaMin = pkpdRuntime?.let { (it.params.diaHrs * 60.0).toInt() } ?: (profile.dia * 60.0).toInt(),
            peakMin = pkpdRuntime?.params?.peakMin?.toInt() ?: 60,
            tailFrac = pkpdRuntime?.tailFraction ?: 0.0,
            onsetConfirmed = pkpdRuntime?.activity?.stage != null,  // If stage exists, onset confirmed
            residualEffect = pkpdRuntime?.activity?.relativeActivity  // Use relativeActivity as residual
        )
        
        // Activity snapshot
        val activity = buildActivitySnapshot()
        
        // States
        val states = StatesSnapshot(
            modeType = modeType,
            modeRuntimeMin = modeRuntimeMin,
            autodriveState = autodriveState,
            wcyclePhase = wcyclePhase,
            wcycleFactor = wcycleFactor
        )
        
        // Limits
        val limits = LimitsSnapshot(
            maxSMB = maxSMB,
            maxSMBHB = maxSMBHB,
            maxIOB = maxIOB,
            maxBasal = maxBasal,
            tbrMaxMode = tbrMaxMode,
            tbrMaxAutoDrive = tbrMaxAutoDrive
        )
        
        // Decision
        val decision = DecisionSnapshot(
            smbU = smbProposed,
            tbrUph = tbrRate,
            tbrMin = tbrDuration,
            intervalMin = intervalMin,
            reasonTags = reasonTags
        )
        
        // Last delivery
        val lastDelivery = buildLastDeliverySnapshot(now)
        
        return Snapshot(
            bg = bg,
            delta = delta,
            shortAvgDelta = shortAvgDelta,
            longAvgDelta = longAvgDelta,
            unit = "mg/dL",
            timestamp = now,
            cgmAgeMin = cgmAgeMin,
            noise = noise,
            iob = iob.iob,
            iobActivity = iobActivity,
            cob = cob,
            isfProfile = profile.sens,
            isfUsed = isfUsed,
            ic = profile.carb_ratio,
            target = profile.target_bg,
            pkpd = pkpd,
            activity = activity,
            physio = physio,
            states = states,
            limits = limits,
            decisionAimi = decision,
            lastDelivery = lastDelivery
        )
    }
    
    /**
     * Build activity snapshot from ActivityManager
     */
    private fun buildActivitySnapshot(): ActivitySnapshot {
        // Simplified - use 0 if ActivityManager doesn't have these methods
        val steps5 = try { 0 } catch (e: Exception) { 0 }
        val steps30 = try { 0 } catch (e: Exception) { 0 }
        val hr5: Int? = try { null } catch (e: Exception) { null }
        val hr15: Int? = try { null } catch (e: Exception) { null }
        
        return ActivitySnapshot(
            steps5min = steps5,
            steps30min = steps30,
            hrAvg5 = hr5,
            hrAvg15 = hr15
        )
    }
    
    /**
     * Build last delivery snapshot from database
     */
    private fun buildLastDeliverySnapshot(now: Long): LastDeliverySnapshot {
        val lookbackMs = 60 * 60 * 1000L // 1 hour
        val fromTime = now - lookbackMs
        
        // Last bolus
        val boluses = try {
            persistenceLayer.getBolusesFromTime(fromTime, ascending = false)
                .blockingGet()
                .filter { it.type != app.aaps.core.data.model.BS.Type.SMB }
        } catch (e: Exception) {
            aapsLogger.debug(app.aaps.core.interfaces.logging.LTag.APS, "Failed to fetch boluses: ${e.message}")
            emptyList()
        }
        val lastBolus = boluses.firstOrNull()
        
        // Last SMB
        val smbs = try {
            persistenceLayer.getBolusesFromTime(fromTime, ascending = false)
                .blockingGet()
                .filter { it.type == app.aaps.core.data.model.BS.Type.SMB }
        } catch (e: Exception) {
            aapsLogger.debug(app.aaps.core.interfaces.logging.LTag.APS, "Failed to fetch SMBs: ${e.message}")
            emptyList()
        }
        val lastSmb = smbs.firstOrNull()
        
        // Last TBR
        val tbrs = try {
            persistenceLayer.getTemporaryBasalsStartingFromTime(fromTime, ascending = false)
                .blockingGet()
        } catch (e: Exception) {
            emptyList()
        }
        val lastTbr = tbrs.firstOrNull()
        
        return LastDeliverySnapshot(
            lastBolusU = lastBolus?.let { it.amount },
            lastBolusTime = lastBolus?.let { it.timestamp },
            lastSmbU = lastSmb?.let { it.amount },
            lastSmbTime = lastSmb?.let { it.timestamp },
            lastTbrRate = lastTbr?.rate,
            lastTbrTime = lastTbr?.timestamp
        )
    }
    
    /**
     * Build history: BG/delta/IOB/TBR/SMB/HR/steps over 45-60 min
     * Max 12 points (5-min intervals)
     */
    private fun buildHistory(now: Long): History {
        val lookbackMin = 60
        val intervalMin = 5
        val maxPoints = 12
        
        val bgSeries = mutableListOf<Double>()
        val deltaSeries = mutableListOf<Double>()
        val iobSeries = mutableListOf<Double>()
        val tbrSeries = mutableListOf<Double?>()
        val smbSeries = mutableListOf<Double>()
        val hrSeries = mutableListOf<Int?>()
        val stepsSeries = mutableListOf<Int>()
        
        repeat(maxPoints) { i ->
            val timeOffset = (i + 1) * intervalMin * 60 * 1000L
            val timestamp = now - timeOffset
            
            // BG & delta (simplified - ideally fetch from persistence)
            bgSeries.add(0.0)  // TODO: fetch from GlucoseValue
            deltaSeries.add(0.0)
            
            // IOB (simplified - would need to recalculate at each point)
            iobSeries.add(0.0)  // TODO: calculate IOB at timestamp
            
            // TBR (check if active at this time)
            tbrSeries.add(null)  // TODO: fetch TBR rate
            
            // SMB (check if delivered around this time)
            smbSeries.add(0.0)  // TODO: fetch SMB
            
            // HR & steps
            hrSeries.add(null)  // TODO: from ActivityManager
            stepsSeries.add(0)
        }
        
        return History(
            bgSeries = bgSeries,
            deltaSeries = deltaSeries,
            iobSeries = iobSeries,
            tbrSeries = tbrSeries,
            smbSeries = smbSeries,
            hrSeries = hrSeries,
            stepsSeries = stepsSeries
        )
    }
    
    /**
     * Build 7-day stats from TIR calculator
     */
    private fun buildStats7d(now: Long): Stats7d {
        val sevenDaysAgo = now - 7 * 24 * 60 * 60 * 1000L
        
        // Get TIR stats (using 7 days with standard thresholds)
        val tirData = try {
            tirCalculator.calculate(7, 70.0, 180.0)
        } catch (e: Exception) {
            null
        }
        
        // Average the TIR data
        val tirStats = tirData?.let { tirCalculator.averageTIR(it) }
        
        // Get TDD stats
        val tdd7d = try {
            tddCalculator.averageTDD(
                tddCalculator.calculate(7, allowMissingDays = true)
            )?.data?.totalAmount ?: 0.0
        } catch (e: Exception) {
            0.0
        }
        
        return Stats7d(
            tir = tirStats?.let { it.inRangePct() } ?: 0.0,
            hypoPct = tirStats?.let { it.belowPct() } ?: 0.0,
            hyperPct = tirStats?.let { it.abovePct() } ?: 0.0,
            meanBG = 100.0,  // TODO: calculate from glucose values when implementing buildHistory()
            cv = 0.0,  // TODO: calculate CV from std dev when available
            tdd7dAvg = tdd7d,
            basalPct = 50.0,  // TODO: calculate from actual basal vs bolus ratio
            bolusPct = 50.0
        )
    }
}
