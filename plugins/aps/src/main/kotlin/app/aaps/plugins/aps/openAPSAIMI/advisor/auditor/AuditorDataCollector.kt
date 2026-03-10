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
import kotlin.math.abs

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
    private val aapsLogger: AAPSLogger,
    private val trajectoryHistoryProvider: app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryHistoryProvider,
    private val trajectoryGuard: app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryGuard // 🌀 Injected
) {
    
    // ... (buildAuditorInput remains mostly same, just checking call sites)

    /**
     * Build complete auditor input from AIMI runtime state
     */
    fun buildAuditorInput(
        // ... params ...
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
        
        // Retrieve robust history from TrajectoryHistoryProvider
        val trajectoryHistory = trajectoryHistoryProvider.buildHistory(
            nowMillis = now,
            historyMinutes = 90, // Increased to 90 to match Guard requirements
            currentBg = bg,
            currentDelta = delta,
            currentAccel = delta - shortAvgDelta,
            insulinActivityNow = pkpdRuntime?.activity?.relativeActivity ?: (iob.iob / 3.0),
            iobNow = iob.iob,
            pkpdStage = when(pkpdRuntime?.activity?.stage?.name) {
                "RISING", "PRE_ONSET" -> app.aaps.plugins.aps.openAPSAIMI.pkpd.ActivityStage.RISING
                "PEAK" -> app.aaps.plugins.aps.openAPSAIMI.pkpd.ActivityStage.PEAK
                "FALLING" -> app.aaps.plugins.aps.openAPSAIMI.pkpd.ActivityStage.FALLING
                else -> app.aaps.plugins.aps.openAPSAIMI.pkpd.ActivityStage.TAIL
            },
            timeSinceLastBolus = 0, 
            cobNow = cob ?: 0.0
        )

        // Build history object
        val history = buildHistoryFromTrajectory(trajectoryHistory, now)
        
        // Build 7-day stats
        val stats = buildStats7d(now)
        
        // Build Trajectory Snapshot (AIMI 2.1)
        // Try to reuse last analysis if valid strings match, otherwise re-analyze
        val trajectory = buildTrajectorySnapshot(
            trajectoryHistory,
            profile.target_bg
        )

        return AuditorInput(
            snapshot = snapshot,
            history = history,
            stats = stats,
            trajectory = trajectory
        )
    }

    private fun buildTrajectorySnapshot(
        history: List<app.aaps.plugins.aps.openAPSAIMI.trajectory.PhaseSpaceState>,
        target: Double
    ): TrajectorySnapshot? {
        try {
            // Re-run analysis logic to ensure we get the EXACT same classification
            val stableOrbit = app.aaps.plugins.aps.openAPSAIMI.trajectory.StableOrbit.fromProfile(targetBg = target, basalRate = 1.0)
            
            // Call the Guard to analyze (it's robust and stateless-ish)
            val analysis = trajectoryGuard.analyzeTrajectory(history, stableOrbit) ?: return null
            
            val modString = if (analysis.modulation.isSignificant()) {
                val parts = mutableListOf<String>()
                if (abs(analysis.modulation.smbDamping - 1.0) > 0.05) parts.add("SMBx%.2f".format(analysis.modulation.smbDamping))
                if (abs(analysis.modulation.intervalStretch - 1.0) > 0.05) parts.add("Intx%.2f".format(analysis.modulation.intervalStretch))
                if (analysis.modulation.basalPreference > 0.6) parts.add("PreferBasal")
                "${parts.joinToString(", ")} (${analysis.modulation.reason})"
            } else null

            return TrajectorySnapshot(
                type = analysis.classification.name,
                curvature = analysis.metrics.curvature,
                convergence = analysis.metrics.convergenceVelocity,
                coherence = analysis.metrics.coherence,
                energyBalance = analysis.metrics.energyBalance,
                modulation = modString
            )
        } catch(e: Exception) { 
            aapsLogger.error(app.aaps.core.interfaces.logging.LTag.APS, "Auditor Trajectory snapshot failed", e)
            return null 
        }
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
                .filter { it.duration.toInt() != 0 } // Filter out CANCELs
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
     * Build history object from PhaseSpace states
     */
    private fun buildHistoryFromTrajectory(
        states: List<app.aaps.plugins.aps.openAPSAIMI.trajectory.PhaseSpaceState>, 
        now: Long
    ): History {
        // Take last 12 points (60 min), reversed for JSON array [newest...oldest] or [oldest...newest]
        // Usually charts expect chronological order. Let's provide newest first as typical history lookback?
        // Actually, for stats libraries, usually chronological. 
        // Let's stick to chronological [oldest -> newest] for alignment, logic dictates we filter by time.
        
        val maxPoints = 12
        val recentStates = states.sortedBy { it.timestamp }.takeLast(maxPoints)
        
        val bgSeries = recentStates.map { it.bg }.toMutableList()
        val deltaSeries = recentStates.map { it.bgDelta }.toMutableList()
        val iobSeries = recentStates.map { it.iob }.toMutableList()
        
        // Pad with zeros if not enough points to maintain array structure consistency? 
        // No, list length can vary, but for "Series" often fixed length expected.
        // Let's rely on map.
        
        // For other series (smb, tbr) we don't have them in phase space state.
        // We will fill with zeros/nulls for now as they are less critical for trajectory physics
        // but important for AI context.
        // Ideally we should fetch them similarly from persistence if Critical.
        // Given Phase 1 fix, let's keep them zero-filled but correctly sized.
        
        val tbrSeries = MutableList<Double?>(recentStates.size) { null }
        val smbSeries = MutableList<Double>(recentStates.size) { 0.0 }
        val hrSeries = MutableList<Int?>(recentStates.size) { null }
        val stepsSeries = MutableList<Int>(recentStates.size) { 0 }
        
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
