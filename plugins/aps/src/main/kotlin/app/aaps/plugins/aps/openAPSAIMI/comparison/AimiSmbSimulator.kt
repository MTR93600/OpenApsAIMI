package app.aaps.plugins.aps.openAPSAIMI.comparison

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.plugins.aps.openAPSSMB.DetermineBasalSMB
import app.aaps.plugins.aps.openAPSAIMI.DetermineBasalaimiSMB2
import app.aaps.core.interfaces.utils.DateUtil
import javax.inject.Inject
import javax.inject.Singleton

/**
 * =============================================================================
 * DUAL ENGINE SIMULATOR
 * =============================================================================
 * 
 * Runs BOTH AIMI and OpenAPS SMB algorithms on the same input data,
 * collecting decisions for later KPI calculation and comparison.
 * 
 * Usage:
 *   val simulator = DualEngineSimulator(...)
 *   val results = simulator.runSimulation(inputs)
 *   val comparison = PerformanceScorer.compare(aimiKpi, smbKpi, "7 days")
 * =============================================================================
 */
@Singleton
class DualEngineSimulator @Inject constructor(
    private val aimiLogic: DetermineBasalaimiSMB2,
    private val smbLogic: DetermineBasalSMB,
    private val aapsLogger: AAPSLogger,
    private val uiInteraction: UiInteraction,
    private val dateUtil: DateUtil          // Dependency for time
) {
    // Virtual Patient State for SMB
    private val virtualReservoir = VirtualInsulinReservoir()
    private val virtualIobCalculator = VirtualIobCalculator(virtualReservoir, dateUtil)
    
    /**
     * Input for simulation - contains all data needed to run both engines.
     */
    data class SimulationTick(
        val timestamp: Long,
        val glucoseStatus: GlucoseStatusAIMI,
        val currentTemp: CurrentTemp,
        val iobData: Array<IobTotal>,
        val profileAimi: OapsProfileAimi,
        val autosens: AutosensResult,
        val mealData: MealData,
        val microBolusAllowed: Boolean,
        val dynIsfMode: Boolean = true
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as SimulationTick
            return timestamp == other.timestamp &&
                   glucoseStatus == other.glucoseStatus &&
                   iobData.contentEquals(other.iobData)
        }
        
        override fun hashCode(): Int {
            var result = timestamp.hashCode()
            result = 31 * result + glucoseStatus.hashCode()
            result = 31 * result + iobData.contentHashCode()
            return result
        }
    }
    
    /**
     * Result of simulation containing decisions from both algorithms.
     */
    data class SimulationResult(
        val aimiDecisions: List<ComparisonDecision>,
        val smbDecisions: List<ComparisonDecision>,
        val periodStart: Long,
        val periodEnd: Long,
        val ticksProcessed: Int,
        val errors: List<String>
    )
    
    /**
     * Run simulation on a list of input ticks.
     * Executes BOTH AIMI and SMB on each tick and collects decisions.
     */
    fun runSimulation(ticks: List<SimulationTick>): SimulationResult {
        val aimiDecisions = mutableListOf<ComparisonDecision>()
        val smbDecisions = mutableListOf<ComparisonDecision>()
        val errors = mutableListOf<String>()
        
        if (ticks.isEmpty()) {
            return SimulationResult(emptyList(), emptyList(), 0, 0, 0, listOf("No input ticks"))
        }
        
        val periodStart = ticks.first().timestamp
        val periodEnd = ticks.last().timestamp
        
        // Reset Virtual State at start of simulation
        virtualReservoir.clear()
        
        aapsLogger.info(LTag.APS, "DualEngineSimulator: Starting simulation with ${ticks.size} ticks")
        
        for (tick in ticks) {
            try {
                // === RUN AIMI ===
                val aimiResult = runAimi(tick)
                if (aimiResult != null) {
                    aimiDecisions.add(convertToDecision(tick, aimiResult, AlgorithmType.AIMI))
                }
                
                // === RUN SMB ===
                // === RUN SMB (Counter-Factual Mode) ===
                val smbResult = runSmb(tick)
                if (smbResult != null) {
                    val decision = convertToDecision(tick, smbResult, AlgorithmType.OPENAPS_SMB)
                    smbDecisions.add(decision)
                    
                    // UPDATE VIRTUAL STATE
                    // SMB thinks it delivered this. We must record it so next cycle reflects it.
                    virtualReservoir.addDecision(smbResult, tick.timestamp)
                }
                
            } catch (e: Exception) {
                errors.add("Error at ${tick.timestamp}: ${e.message}")
                aapsLogger.error(LTag.APS, "DualEngineSimulator error", e)
            }
        }
        
        aapsLogger.info(LTag.APS, "DualEngineSimulator: Completed. AIMI=${aimiDecisions.size}, SMB=${smbDecisions.size}")
        
        return SimulationResult(
            aimiDecisions = aimiDecisions,
            smbDecisions = smbDecisions,
            periodStart = periodStart,
            periodEnd = periodEnd,
            ticksProcessed = ticks.size,
            errors = errors
        )
    }
    
    /**
     * Run AIMI algorithm on a single tick.
     */
    private fun runAimi(tick: SimulationTick): RT? {
        return try {
            aimiLogic.determine_basal(
                glucose_status = tick.glucoseStatus,
                currenttemp = tick.currentTemp,
                iob_data_array = tick.iobData,
                profile = tick.profileAimi,
                autosens_data = tick.autosens,
                mealData = tick.mealData,
                microBolusAllowed = tick.microBolusAllowed,
                currentTime = tick.timestamp,
                flatBGsDetected = false,
                dynIsfMode = tick.dynIsfMode,
                uiInteraction = uiInteraction
            )
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "AIMI engine error: ${e.message}")
            null
        }
    }
    
    /**
     * Run OpenAPS SMB algorithm on a single tick.
     */
    private fun runSmb(tick: SimulationTick): RT? {
        return try {
            // Convert AIMI profile to SMB profile
            val smbProfile = mapProfileForSmb(tick.profileAimi)
            
            // Convert GlucoseStatusAIMI to GlucoseStatus
            val smbGlucoseStatus = convertGlucoseStatus(tick.glucoseStatus)
            
            // CALCULATE VIRTUAL IOB
            // Instead of using tick.iobData (Real IOB from AIMI), we ask our Virtual Calculator
            // what the IOB is based on SMB's past decisions.
            val virtualIob = virtualIobCalculator.calculateIobArrayForSMB(
                profile = smbProfile,
                lastAutosensResult = tick.autosens,
                exerciseMode = tick.profileAimi.exercise_mode, 
                halfBasalExerciseTarget = tick.profileAimi.half_basal_exercise_target,
                isTempTarget = tick.profileAimi.high_temptarget_raises_sensitivity || tick.profileAimi.low_temptarget_lowers_sensitivity
            )

            smbLogic.determine_basal(
                glucose_status = smbGlucoseStatus,
                currenttemp = tick.currentTemp,
                iob_data_array = virtualIob, // INJECT VIRTUAL IOB HERE (Critical Fix)
                profile = smbProfile,
                autosens_data = tick.autosens,
                meal_data = tick.mealData,
                microBolusAllowed = tick.microBolusAllowed,
                currentTime = tick.timestamp,
                flatBGsDetected = false,
                dynIsfMode = tick.dynIsfMode
            )
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "SMB engine error: ${e.message}")
            null
        }
    }
    
    /**
     * Convert RT result to ComparisonDecision.
     */
    private fun convertToDecision(tick: SimulationTick, result: RT, algo: AlgorithmType): ComparisonDecision {
        return ComparisonDecision(
            timestamp = tick.timestamp,
            algo = algo,
            bgMgdl = tick.glucoseStatus.glucose,
            smbU = result.units ?: 0.0,
            basalRateUph = result.rate ?: 0.0,
            tempBasalDurationMin = result.duration ?: 0,
            eventualBg = result.eventualBG,
            predictedBg = result.predBGs?.IOB?.lastOrNull()?.toDouble(),
            iob = tick.iobData.firstOrNull()?.iob ?: 0.0,
            cob = tick.mealData.mealCOB,
            reason = result.reason.toString().take(200) // Truncate for storage
        )
    }
    
    /**
     * Map OapsProfileAimi to OapsProfile for SMB plugin.
     */
    private fun mapProfileForSmb(p: OapsProfileAimi): OapsProfile {
        return OapsProfile(
            dia = p.dia,
            min_5m_carbimpact = p.min_5m_carbimpact,
            max_iob = p.max_iob,
            max_daily_basal = p.max_daily_basal,
            max_basal = p.max_basal,
            min_bg = p.min_bg,
            max_bg = p.max_bg,
            target_bg = p.target_bg,
            carb_ratio = p.carb_ratio,
            sens = p.sens,
            autosens_adjust_targets = p.autosens_adjust_targets,
            max_daily_safety_multiplier = p.max_daily_safety_multiplier,
            current_basal_safety_multiplier = p.current_basal_safety_multiplier,
            high_temptarget_raises_sensitivity = p.high_temptarget_raises_sensitivity,
            low_temptarget_lowers_sensitivity = p.low_temptarget_lowers_sensitivity,
            sensitivity_raises_target = p.sensitivity_raises_target,
            resistance_lowers_target = p.resistance_lowers_target,
            adv_target_adjustments = p.adv_target_adjustments,
            exercise_mode = p.exercise_mode,
            half_basal_exercise_target = p.half_basal_exercise_target,
            maxCOB = p.maxCOB,
            skip_neutral_temps = p.skip_neutral_temps,
            remainingCarbsCap = p.remainingCarbsCap,
            enableUAM = p.enableUAM,
            A52_risk_enable = p.A52_risk_enable,
            SMBInterval = p.SMBInterval,
            enableSMB_with_COB = p.enableSMB_with_COB,
            enableSMB_with_temptarget = p.enableSMB_with_temptarget,
            allowSMB_with_high_temptarget = p.allowSMB_with_high_temptarget,
            enableSMB_always = p.enableSMB_always,
            enableSMB_after_carbs = p.enableSMB_after_carbs,
            maxSMBBasalMinutes = p.maxSMBBasalMinutes,
            maxUAMSMBBasalMinutes = p.maxUAMSMBBasalMinutes,
            bolus_increment = p.bolus_increment,
            carbsReqThreshold = p.carbsReqThreshold,
            current_basal = p.current_basal,
            temptargetSet = p.temptargetSet,
            autosens_max = p.autosens_max,
            out_units = p.out_units,
            lgsThreshold = p.lgsThreshold,
            variable_sens = p.variable_sens,
            insulinDivisor = p.insulinDivisor,
            TDD = p.TDD
        )
    }
    
    /**
     * Convert GlucoseStatusAIMI to GlucoseStatus.
     */
    private fun convertGlucoseStatus(aimiStatus: GlucoseStatusAIMI): GlucoseStatus {
        return object : GlucoseStatus {
            override val glucose = aimiStatus.glucose
            override val noise = aimiStatus.noise
            override val delta = aimiStatus.delta
            override val shortAvgDelta = aimiStatus.shortAvgDelta
            override val longAvgDelta = aimiStatus.longAvgDelta
            override val date = aimiStatus.date
        }
    }
}
