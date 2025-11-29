package app.aaps.plugins.aps.openAPSAIMI.comparison

import android.content.Context
import android.os.Environment
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
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.plugins.aps.openAPSSMB.DetermineBasalSMB
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AimiSmbComparator @Inject constructor(
    private val determineBasalSMB: DetermineBasalSMB,
    private val iobCobCalculator: IobCobCalculator,  // ⭐ NOUVEAU - Pour calculer IOB comme SMB
    private val context: Context,
    private val constraintsChecker: ConstraintsChecker,
    private val profileFunction: ProfileFunction,
    private val aapsLogger: AAPSLogger
) {
    // 📊 Track cumulative insulin difference over time
    private var cumulativeDiff = 0.0

    private val logFile by lazy {
        val externalDir = File(
            Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS"
        )
        File(externalDir, "comparison_aimi_smb.csv").apply {
            parentFile?.mkdirs()
            if (!exists()) {
                // Ajout des colonnes UAM
                writeText(
                    "Timestamp,Date,BG,Delta,ShortAvgDelta,LongAvgDelta,IOB,COB," +
                        "AIMI_Rate,AIMI_SMB,AIMI_Duration,AIMI_EventualBG,AIMI_TargetBG," +
                        "SMB_Rate,SMB_SMB,SMB_Duration,SMB_EventualBG,SMB_TargetBG," +
                        "Diff_Rate,Diff_SMB,Diff_EventualBG," +
                        "MaxIOB,MaxBasal,MicroBolus_Allowed," +
                        "AIMI_Insulin_30min,SMB_Insulin_30min,Cumul_Diff," +
                        "AIMI_Active,SMB_Active,Both_Active," +
                        "AIMI_UAM_Last,SMB_UAM_Last," +
                        "Reason_AIMI,Reason_SMB\n"
                )
            }
        }
    }

    fun compare(
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
        try {
            // 🔧 FIX: Calculate IOB array specifically for SMB (not AIMI's IOB)
            // This is CRITICAL - SMB uses different IOB calculation that affects all decisions
            val smbIobArray = iobCobCalculator.calculateIobArrayForSMB(
                autosens,
                profileAimi.exercise_mode,
                profileAimi.half_basal_exercise_target,
                profileAimi.temptargetSet
            )
            
            aapsLogger.debug(
                LTag.APS,
                "SMB Comparator - AIMI IOB: ${iobData.firstOrNull()?.iob}, " +
                "SMB IOB: ${smbIobArray.firstOrNull()?.iob}, " +
                "maxIOB=${profileAimi.max_iob}, maxBasal=${profileAimi.max_basal}"
            )

            // 🔧 FIX: Convert GlucoseStatusAIMI to GlucoseStatus (different types)
            val smbGlucoseStatus = convertToSMBGlucoseStatus(glucoseStatus)

            // Map Profile directly (values are already constrained)
            val profileSmb = mapProfile(profileAimi)

            // ✅ Run SMB with SMB-specific parameters (not AIMI's)
            val smbResult = determineBasalSMB.determine_basal(
                glucose_status = smbGlucoseStatus,  // ✅ Correct type
                currenttemp = currentTemp,
                iob_data_array = smbIobArray,  // ✅ SMB-specific IOB calculation
                profile = profileSmb,
                autosens_data = autosens,
                meal_data = mealData,
                microBolusAllowed = microBolusAllowed,
                currentTime = currentTime,
                flatBGsDetected = flatBGsDetected,
                dynIsfMode = dynIsfMode
            )

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
     * ✅ Uses values directly from profileAimi (already constrained by AIMI plugin)
     * ❌ Does NOT re-apply constraints to ensure fair comparison
     */
    private fun mapProfile(p: OapsProfileAimi): OapsProfile {
        return OapsProfile(
            dia = p.dia,
            min_5m_carbimpact = p.min_5m_carbimpact,
            // ✅ Use values from profileAimi directly (already constrained)
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
    private fun convertToSMBGlucoseStatus(aimiStatus: GlucoseStatusAIMI): GlucoseStatus {
        // Create anonymous object implementing GlucoseStatus interface
        return object : GlucoseStatus {
            override val glucose = aimiStatus.glucose
            override val noise = aimiStatus.noise
            override val delta = aimiStatus.delta
            override val shortAvgDelta = aimiStatus.shortAvgDelta
            override val longAvgDelta = aimiStatus.longAvgDelta
            override val date = aimiStatus.date
        }
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
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        // On loggue la vraie date de la décision
        val date = sdf.format(Date(currentTime))
        val timestamp = currentTime

        // 📊 AIMI Data
        val aimiRate = aimi.rate ?: 0.0
        val aimiSmb = aimi.units ?: 0.0
        val aimiDuration = aimi.duration ?: 0
        val aimiEventualBG = aimi.eventualBG ?: glucoseStatus.glucose
        val aimiTargetBG = aimi.targetBG ?: 100.0

        // 📊 SMB Data
        val smbRate = smb.rate ?: 0.0
        val smbSmb = smb.units ?: 0.0
        val smbDuration = smb.duration ?: 0
        val smbEventualBG = smb.eventualBG ?: glucoseStatus.glucose
        val smbTargetBG = smb.targetBG ?: 100.0

        // 📊 UAM predictions (dernier point)
        val aimiUamLast = aimi.predBGs?.UAM?.lastOrNull()?.toDouble()
        val smbUamLast = smb.predBGs?.UAM?.lastOrNull()?.toDouble()

        // 📊 Differences
        val diffRate = aimiRate - smbRate
        val diffSmb = aimiSmb - smbSmb
        val diffEventualBG = aimiEventualBG - smbEventualBG

        // 📊 Insuline 30 min
        val aimiInsulin30min = (aimiRate * 0.5) + aimiSmb
        val smbInsulin30min = (smbRate * 0.5) + smbSmb
        cumulativeDiff += (aimiInsulin30min - smbInsulin30min)

        // Flags d’activité
        val aimiActive = (aimiRate != 0.0 && aimiDuration > 0) || aimiSmb > 0.0
        val smbActive = (smbRate != 0.0 && smbDuration > 0) || smbSmb > 0.0
        val bothActive = aimiActive && smbActive

        // Sanitize raisons
        val aimiReason = aimi.reason.toString()
            .replace("\n", " | ")
            .replace(",", ";")
            .replace("\"", "'")
        val smbReason = smb.reason.toString()
            .replace("\n", " | ")
            .replace(",", ";")
            .replace("\"", "'")

        val line = listOf(
            timestamp,
            date,
            "%.1f".format(Locale.US, glucoseStatus.glucose),
            "%.2f".format(Locale.US, glucoseStatus.delta),
            "%.2f".format(Locale.US, glucoseStatus.shortAvgDelta),
            "%.2f".format(Locale.US, glucoseStatus.longAvgDelta),
            "%.2f".format(Locale.US, iob),
            "%.1f".format(Locale.US, cob),
            // AIMI
            "%.2f".format(Locale.US, aimiRate),
            "%.3f".format(Locale.US, aimiSmb),
            aimiDuration,
            "%.1f".format(Locale.US, aimiEventualBG),
            "%.1f".format(Locale.US, aimiTargetBG),
            // SMB
            "%.2f".format(Locale.US, smbRate),
            "%.3f".format(Locale.US, smbSmb),
            smbDuration,
            "%.1f".format(Locale.US, smbEventualBG),
            "%.1f".format(Locale.US, smbTargetBG),
            // Diff
            "%.2f".format(Locale.US, diffRate),
            "%.3f".format(Locale.US, diffSmb),
            "%.1f".format(Locale.US, diffEventualBG),
            // Contraintes
            "%.1f".format(Locale.US, maxIOB),
            "%.2f".format(Locale.US, maxBasal),
            if (microBolusAllowed) "1" else "0",
            // Insuline
            "%.3f".format(Locale.US, aimiInsulin30min),
            "%.3f".format(Locale.US, smbInsulin30min),
            "%.3f".format(Locale.US, cumulativeDiff),
            // Activity flags
            if (aimiActive) "1" else "0",
            if (smbActive) "1" else "0",
            if (bothActive) "1" else "0",
            // UAM
            aimiUamLast?.let { "%.1f".format(Locale.US, it) } ?: "",
            smbUamLast?.let { "%.1f".format(Locale.US, it) } ?: "",
            // Raisons
            "\"$aimiReason\"",
            "\"$smbReason\""
        ).joinToString(",") + "\n"

        try {
            FileWriter(logFile, true).use { it.append(line) }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "SMB Comparator log error: ${e.message}", e)
            e.printStackTrace()
        }
    }
}
