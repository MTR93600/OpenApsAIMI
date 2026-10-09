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
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.plugins.aps.openAPSAIMI.aimiCsvTimestamp
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt3
import app.aaps.plugins.aps.openAPSSMB.DetermineBasalSMB
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiSmbComparison
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AimiSmbComparator @Inject constructor(
    private val determineBasalSMB: DetermineBasalSMB,
    private val iobCobCalculator: IobCobCalculator,  // To calculate IOB the way SMB sees it
    private val constraintsChecker: ConstraintsChecker,
    private val profileFunction: ProfileFunction,
    private val aapsLogger: AAPSLogger,
    private val virtualGlucoseEngine: VirtualGlucoseEngine, // Virtual glucose simulation
    private val dateUtil: DateUtil,          // Dependency for time
    private val storage: AimiStorage,
    private val preferences: Preferences
) : AimiSmbComparison {
    private companion object {
        const val CSV_SCHEMA_VERSION = "3"
        // CSV header written when the file is created. Byte-identical to the previous version.
        private const val HEADER = "SchemaVersion,Timestamp,Date,BG,Delta,ShortAvgDelta,LongAvgDelta,IOB,COB," +
            "AIMI_Rate,AIMI_SMB,AIMI_Duration,AIMI_EventualBG,AIMI_TargetBG," +
            "SMB_Rate,SMB_SMB,SMB_Duration,SMB_EventualBG,SMB_TargetBG," +
            "Diff_Rate,Diff_SMB,Diff_EventualBG," +
            "MaxIOB,MaxBasal,MicroBolus_Allowed," +
            "AIMI_Insulin_30min,SMB_Insulin_30min,Cumul_Diff," +
            "AIMI_Active,SMB_Active,Both_Active," +
            "AIMI_UAM_Last,SMB_UAM_Last," +
            "Verdict,Artifact_Flag,Diff_Sign," +
            "AIMI_Flag_MealPriority,AIMI_Flag_Refractory,AIMI_Flag_Throttle,AIMI_Flag_CBF," +
            "SMB_Flag_Refractory,SMB_Flag_Throttle,SMB_Flag_CBF," +
            "Context_MealRise,Context_COB_Active,Context_UAM_Bias,SMB_LastBolusAgeMin," +
            "Reason_AIMI,Reason_SMB\n"
    }
    // VIRTUAL PATIENT STATE (Lyra Reality System)
    // Allows SMB to run "Counter-Factually" (deciding based on its own past, not AIMI's)
    private val virtualReservoir = VirtualInsulinReservoir()
    // No longer passing activePlugin
    private val virtualIobCalculator = VirtualIobCalculator(virtualReservoir, dateUtil)

    // Track cumulative insulin difference over time
    private var cumulativeDiff = 0.0

    private val logFile: AimiPath by lazy {
        val path = storage.file("comparison_aimi_smb.csv")
        storage.createParentDirectories(path)
        if (!storage.exists(path)) {
            storage.writeText(path, HEADER)
        }
        aapsLogger.info(LTag.APS, "SMB Comparator CSV ready at ${storage.displayPath(path)}")
        aapsLogger.info(LTag.APS, "SMB Comparator storage: ${storage.healthReport()}")
        path
    }

    override fun compare(
        aimiResult: RT,
        glucoseStatus: GlucoseStatusAIMI,
        currentTemp: CurrentTemp,
        iobData: Array<IobTotal>,
        profileAimi: OapsProfileAimi,
        autosens: AutosensResult,
        mealData: MealData,
        microBolusAllowed: Boolean,
        currentTime: Long,
        flatBGsDetected: Boolean,
        dynIsfMode: Boolean
    ) {
        if (!preferences.get(BooleanKey.OApsAIMIAimiSmbComparatorEnabled)) {
            return
        }
        try {
            // Map Profile directly (values are already constrained)
            val profileSmb = mapProfile(profileAimi)

            // FIX: Calculate IOB array specifically for SMB (Counter-Factual IOB)
            // We use the Virtual Calculator which looks at what SMB *would have done*

            // 1. Maintain Virtual Reservoir (Prevent memory leak)
            // Keep 6 hours of history (DIA + buffers)
            virtualReservoir.pruneOldData(currentTime - 6 * 60 * 60 * 1000L)

            // 2. Calculate Virtual IOB AND Activity
            val now = currentTime
            val profileBasal = profileAimi.current_basal

            // Simulation logic: To be realistic, SMB must see the BG it *would* have caused.
            // We use the real BG as anchor and add the virtual deviation.
            val lastSimBg = virtualReservoir.virtualBg ?: glucoseStatus.glucose

            // Calculate activity for REAL insulin (that happened in the body)
            // Note: This is an approximation since we don't have access to the full history
            // of real treatments here in the same format. We use the real IOB data provided.
            val realTotalIob = iobData.firstOrNull() ?: IobTotal(now)

            // Calculate activity for SIMULATED insulin
            val simTotalIob = virtualIobCalculator.calculateIobTotalForTime(now, profileSmb)

            // VIRTUAL GLUCOSE EVOLUTION
            val virtualBg = virtualGlucoseEngine.calculateNextBg(
                realBg = glucoseStatus.glucose,
                lastSimBg = lastSimBg,
                realActivity = realTotalIob.activity,
                simActivity = simTotalIob.activity,
                isf = profileAimi.sens,
                tickMinutes = 5.0
            )

            val prevVirtualDelta = virtualReservoir.virtualDelta
            val virtualDelta = virtualBg - (virtualReservoir.virtualBg ?: virtualBg)
            val virtualShortAvgDelta = computeVirtualShortDelta(
                currentVirtualDelta = virtualDelta,
                previousVirtualDelta = prevVirtualDelta,
                realShortDelta = glucoseStatus.shortAvgDelta
            )
            val virtualLongAvgDelta = computeVirtualLongDelta(
                currentVirtualDelta = virtualDelta,
                virtualShortAvgDelta = virtualShortAvgDelta,
                realLongDelta = glucoseStatus.longAvgDelta
            )
            virtualReservoir.virtualBg = virtualBg
            virtualReservoir.virtualDelta = virtualDelta

            val smbIobArray = virtualIobCalculator.calculateIobArrayForSMB(
                profileSmb,
                autosens,
                profileAimi.exercise_mode,
                profileAimi.half_basal_exercise_target,
                profileAimi.high_temptarget_raises_sensitivity || profileAimi.low_temptarget_lowers_sensitivity
            )

            aapsLogger.debug(
                LTag.APS,
                "SMB Comparator - AIMI IOB: ${iobData.firstOrNull()?.iob}, " +
                "SMB IOB: ${smbIobArray.firstOrNull()?.iob}, " +
                "maxIOB=${profileAimi.max_iob}, maxBasal=${profileAimi.max_basal}"
            )

            // Run SMB with its own Virtual BG (not the real one)
            val virtualSmbGlucoseStatus = convertToSMBGlucoseStatus(
                aimiStatus = glucoseStatus,
                virtualBg = virtualBg,
                virtualDelta = virtualDelta,
                virtualShortAvgDelta = virtualShortAvgDelta,
                virtualLongAvgDelta = virtualLongAvgDelta
            )

            val smbResult = determineBasalSMB.determine_basal(
                glucose_status = virtualSmbGlucoseStatus,
                currenttemp = currentTemp,
                iob_data_array = smbIobArray,
                profile = profileSmb,
                autosens_data = autosens,
                meal_data = mealData,
                microBolusAllowed = microBolusAllowed,
                currentTime = currentTime,
                flatBGsDetected = flatBGsDetected,
                dynIsfMode = dynIsfMode
            )

            // UPDATE VIRTUAL STATE
            // Record what SMB decided so it remembers it next time (Counter-Factual History)
            if (smbResult != null) {
                virtualReservoir.addDecision(smbResult, currentTime)
            }

            logComparison(
                aimiResult,
                smbResult,
                glucoseStatus,
                iobData.firstOrNull()?.iob ?: 0.0,
                mealData.mealCOB,
                profileAimi.max_iob,
                profileAimi.max_basal,
                microBolusAllowed,
                currentTime
            )

        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "SMB Comparator error: ${e.message}", e)
            e.printStackTrace()
        }
    }

    /**
     * Maps OapsProfileAimi to OapsProfile for SMB plugin.
     * Uses values directly from profileAimi (already constrained by AIMI plugin)
     * Does NOT re-apply constraints to ensure fair comparison
     */
    private fun mapProfile(p: OapsProfileAimi): OapsProfile {
        return OapsProfile(
            dia = p.dia,
            min_5m_carbimpact = p.min_5m_carbimpact,
            // Use values from profileAimi directly (already constrained)
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
     * Converts GlucoseStatusAIMI to GlucoseStatus for SMB plugin.
     * SMB expects standard GlucoseStatus type, not AIMI-specific type.
     */
    private fun convertToSMBGlucoseStatus(
        aimiStatus: GlucoseStatusAIMI,
        virtualBg: Double,
        virtualDelta: Double,
        virtualShortAvgDelta: Double,
        virtualLongAvgDelta: Double
    ): GlucoseStatus {
        return object : GlucoseStatus {
            override val glucose = virtualBg
            override val noise = aimiStatus.noise
            override val delta = virtualDelta
            override val shortAvgDelta = virtualShortAvgDelta
            override val longAvgDelta = virtualLongAvgDelta
            override val date = aimiStatus.date
        }
    }

    private fun computeVirtualShortDelta(
        currentVirtualDelta: Double,
        previousVirtualDelta: Double?,
        realShortDelta: Double
    ): Double {
        val prev = previousVirtualDelta ?: currentVirtualDelta
        val blended = (0.65 * currentVirtualDelta) + (0.35 * prev)
        val anchored = (0.85 * blended) + (0.15 * realShortDelta)
        return anchored.coerceIn(-25.0, 25.0)
    }

    private fun computeVirtualLongDelta(
        currentVirtualDelta: Double,
        virtualShortAvgDelta: Double,
        realLongDelta: Double
    ): Double {
        val blended = (0.35 * currentVirtualDelta) + (0.45 * virtualShortAvgDelta) + (0.20 * realLongDelta)
        return blended.coerceIn(-20.0, 20.0)
    }

    private fun logComparison(
        aimi: RT,
        smb: RT,
        glucoseStatus: GlucoseStatusAIMI,
        iob: Double,
        cob: Double,
        maxIOB: Double,
        maxBasal: Double,
        microBolusAllowed: Boolean,
        currentTime: Long
    ) {
        // Log the real decision date
        val date = aimiCsvTimestamp(currentTime)
        val timestamp = currentTime

        // AIMI Data
        val aimiRate = aimi.rate ?: 0.0
        val aimiSmb = aimi.units ?: 0.0
        val aimiDuration = aimi.duration ?: 0
        val aimiEventualBG = aimi.eventualBG ?: glucoseStatus.glucose
        val aimiTargetBG = aimi.targetBG ?: 100.0

        // SMB Data
        val smbRate = smb.rate ?: 0.0
        val smbSmb = smb.units ?: 0.0
        val smbDuration = smb.duration ?: 0
        val smbEventualBG = smb.eventualBG ?: glucoseStatus.glucose
        val smbTargetBG = smb.targetBG ?: 100.0

        // UAM predictions (last point)
        val aimiUamLast = aimi.predBGs?.UAM?.lastOrNull()?.toDouble()
        val smbUamLast = smb.predBGs?.UAM?.lastOrNull()?.toDouble()

        // Differences
        val diffRate = aimiRate - smbRate
        val diffSmb = aimiSmb - smbSmb
        val diffEventualBG = aimiEventualBG - smbEventualBG

        // Instant insulin (5 min step = 1/12 h)
        // Assumes this rate applies for the next 5 minutes.
        // This is an approximation, but it is better than adding "30 min of basal" every 5 min.
        val stepHourFraction = 5.0 / 60.0
        val aimiInsulinStep = (aimiRate * stepHourFraction) + aimiSmb
        val smbInsulinStep = (smbRate * stepHourFraction) + smbSmb
        cumulativeDiff += (aimiInsulinStep - smbInsulinStep)

        // Activity flags
        val aimiActive = (aimiRate != 0.0 && aimiDuration > 0) || aimiSmb > 0.0
        val smbActive = (smbRate != 0.0 && smbDuration > 0) || smbSmb > 0.0
        val bothActive = aimiActive && smbActive

        // Sanitize reasons
        val aimiReason = aimi.reason.toString()
            .replace("\n", " | ")
            .replace(",", ";")
            .replace("\"", "'")
        val smbReason = smb.reason.toString()
            .replace("\n", " | ")
            .replace(",", ";")
            .replace("\"", "'")
        val smbLastBolusAgeMin = extractLastBolusAgeMinutes(smbReason)

        val contextMealRise =
            glucoseStatus.glucose >= 145.0 &&
                (glucoseStatus.delta >= 1.8 || glucoseStatus.shortAvgDelta >= 1.5)
        val contextCobActive = cob >= 6.0
        val contextUamBias =
            aimiReason.contains("UAM", ignoreCase = true) || smbReason.contains("UAM", ignoreCase = true)

        // Context flags to explain divergences across safety layers.
        val aimiFlagMealPriority = aimiReason.contains("MEAL_PRIORITY_CONTEXT", ignoreCase = true) ||
            aimiReason.contains("MEAL_PRIORITY_CHAIN", ignoreCase = true) ||
            aimiReason.contains("MEAL_PRIORITY_RELAX", ignoreCase = true)
        val aimiFlagRefractory = aimiReason.contains("REFRACTORY", ignoreCase = true)
        val aimiFlagThrottle = aimiReason.contains("PKPD_THROTTLE", ignoreCase = true)
        val aimiFlagCbf = aimiReason.contains("CBF", ignoreCase = true)
        val smbFlagRefractory = smbReason.contains("SMB interval=", ignoreCase = true) ||
            smbReason.contains("lastBolusAge=", ignoreCase = true)
        val smbFlagThrottle = smbReason.contains("THROTTLE", ignoreCase = true)
        val smbFlagCbf = smbReason.contains("CBF", ignoreCase = true)

        // INTERPRETATION LOGIC (Lyra Expert Analysis)

        val diffTotal = aimiInsulinStep - smbInsulinStep
        val absDiff = kotlin.math.abs(diffTotal)
        val diffSign = if (absDiff < 0.02) "=" else if (diffTotal > 0) "+" else "-"

        // 1. Verdict
        val verdict = when {
            absDiff < 0.05 -> "AGREEMENT"
            diffTotal > 0.0 -> "AIMI_AGGRESSIVE" // AIMI gives more (hypo risk?)
            else -> "AIMI_CONSERVATIVE" // AIMI gives less (lag?)
        }

        // 2. Artifact Detection ("Screaming Shadow")
        // If SMB asks > 3x AIMI while BG is high, it's likely just catching up on history
        // Condition: High BG (>140) AND Big Divergence (>0.5U difference) AND Ratio > 3
        val isHighBg = glucoseStatus.glucose > 140
        val isBigDiff = absDiff > 0.5
        val ratio = if (aimiInsulinStep > 0.05) smbInsulinStep / aimiInsulinStep else 100.0 // Avoid div/0

        val artifactFlag = if (
            verdict == "AIMI_CONSERVATIVE" &&
            isHighBg &&
            isBigDiff &&
            ratio > 2.0 &&
            !contextMealRise &&
            !contextCobActive &&
            !contextUamBias &&
            (smbLastBolusAgeMin == null || smbLastBolusAgeMin >= 18.0)
        ) {
            "SCREAMING_SHADOW"
        } else if (
            verdict == "AIMI_CONSERVATIVE" &&
            isHighBg &&
            (contextMealRise || contextCobActive || contextUamBias)
        ) {
            "MEAL_CATCHUP_VALID"
        } else if (verdict == "AIMI_AGGRESSIVE" && glucoseStatus.glucose < 80) {
            "SAFETY_RISK?"
        } else {
            "VALID"
        }

        val line = listOf(
            CSV_SCHEMA_VERSION,
            timestamp,
            date,
            aimiFmt1(glucoseStatus.glucose),
            aimiFmt2(glucoseStatus.delta),
            aimiFmt2(glucoseStatus.shortAvgDelta),
            aimiFmt2(glucoseStatus.longAvgDelta),
            aimiFmt2(iob),
            aimiFmt1(cob),
            // AIMI
            aimiFmt2(aimiRate),
            aimiFmt3(aimiSmb),
            aimiDuration,
            aimiFmt1(aimiEventualBG),
            aimiFmt1(aimiTargetBG),
            // SMB
            aimiFmt2(smbRate),
            aimiFmt3(smbSmb),
            smbDuration,
            aimiFmt1(smbEventualBG),
            aimiFmt1(smbTargetBG),
            // Diff
            aimiFmt2(diffRate),
            aimiFmt3(diffSmb),
            aimiFmt1(diffEventualBG),
            // Constraints
            aimiFmt1(maxIOB),
            aimiFmt2(maxBasal),
            if (microBolusAllowed) "1" else "0",
            // Insulin
            aimiFmt3(aimiInsulinStep),
            aimiFmt3(smbInsulinStep),
            aimiFmt3(cumulativeDiff),
            // Activity flags
            if (aimiActive) "1" else "0",
            if (smbActive) "1" else "0",
            if (bothActive) "1" else "0",
            // UAM
            aimiUamLast?.let { aimiFmt1(it) } ?: "",
            smbUamLast?.let { aimiFmt1(it) } ?: "",
            // Interpretation
            verdict,
            artifactFlag,
            diffSign,
            if (aimiFlagMealPriority) "1" else "0",
            if (aimiFlagRefractory) "1" else "0",
            if (aimiFlagThrottle) "1" else "0",
            if (aimiFlagCbf) "1" else "0",
            if (smbFlagRefractory) "1" else "0",
            if (smbFlagThrottle) "1" else "0",
            if (smbFlagCbf) "1" else "0",
            if (contextMealRise) "1" else "0",
            if (contextCobActive) "1" else "0",
            if (contextUamBias) "1" else "0",
            smbLastBolusAgeMin?.let { aimiFmt1(it) } ?: "",
            // Reasons
            "\"$aimiReason\"",
            "\"$smbReason\""
        ).joinToString(",") + "\n"

        try {
            if (!storage.appendText(logFile, line)) {
                aapsLogger.error(LTag.APS, "SMB Comparator log error: write returned false")
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "SMB Comparator log error: " + e.message)
            e.printStackTrace()
        }
    }

    private fun extractLastBolusAgeMinutes(reason: String): Double? {
        val match = Regex("lastBolusAge=([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE).find(reason)
            ?: return null
        return match.groupValues.getOrNull(1)?.toDoubleOrNull()
    }

}
