package app.aaps.plugins.aps.openAPSAIMI.comparison

import android.content.Context
import android.os.Environment
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.constraints.ConstraintsChecker
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
    private val context: Context,
    private val constraintsChecker: ConstraintsChecker,
    private val profileFunction: ProfileFunction,
    private val aapsLogger: AAPSLogger
) {
    private val logFile by lazy {
        val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS")
        File(externalDir, "comparison_aimi_smb.csv").apply {
            parentFile?.mkdirs()
            if (!exists()) {
                writeText("Timestamp,Date,BG,IOB,COB,AIMI_Rate,AIMI_SMB,AIMI_Duration,SMB_Rate,SMB_SMB,SMB_Duration,Diff_Rate,Diff_SMB,SMB_MaxIOB_Constrained,SMB_MaxBasal_Constrained,SMB_MicroBolus_Allowed,Reason_AIMI,Reason_SMB\n")
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
            // ✅ IMPORTANT: profileAimi already contains constrained values from AIMI plugin
            // DO NOT re-apply constraints here to avoid double-constraint bias
            // This ensures fair comparison: both plugins use the same constraint values
            
            aapsLogger.debug(
                LTag.APS,
                "SMB Comparator using pre-constrained values: maxIOB=${profileAimi.max_iob}, " +
                "maxBasal=${profileAimi.max_basal}, microBolus=$microBolusAllowed"
            )

            // Map Profile directly (values are already constrained)
            val profileSmb = mapProfile(profileAimi)

            // Run SMB (Shadow Mode) with same parameters as AIMI
            val smbResult = determineBasalSMB.determine_basal(
                glucose_status = glucoseStatus,
                currenttemp = currentTemp,
                iob_data_array = iobData,
                profile = profileSmb,
                autosens_data = autosens,
                meal_data = mealData,
                microBolusAllowed = microBolusAllowed,  // ✅ Use same value as AIMI
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
                profileAimi.max_iob,      // ✅ Log actual values used
                profileAimi.max_basal,    // ✅ Log actual values used
                microBolusAllowed         // ✅ Log actual value used
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

    private fun logComparison(
        aimi: RT,
        smb: RT,
        glucoseStatus: GlucoseStatusAIMI,
        iob: Double,
        cob: Double,
        constrainedMaxIOB: Double,
        constrainedMaxBasal: Double,
        constrainedMicroBolus: Boolean
    ) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val date = sdf.format(Date())
        val timestamp = System.currentTimeMillis()

        // AIMI Data
        val aimiRate = aimi.rate ?: 0.0
        val aimiSmb = aimi.units ?: 0.0
        val aimiDuration = aimi.duration ?: 0

        // SMB Data
        val smbRate = smb.rate ?: 0.0
        val smbSmb = smb.units ?: 0.0
        val smbDuration = smb.duration ?: 0

        // Diff
        val diffRate = aimiRate - smbRate
        val diffSmb = aimiSmb - smbSmb

        // Sanitize Reasons (remove newlines and commas)
        val aimiReason = aimi.reason.toString().replace("\n", " | ").replace(",", ";")
        val smbReason = smb.reason.toString().replace("\n", " | ").replace(",", ";")

        // Include constraint data in CSV for analysis
        val microBolusFlag = if (constrainedMicroBolus) 1 else 0
        val line = "$timestamp,$date,${glucoseStatus.glucose},$iob,$cob,$aimiRate,$aimiSmb,$aimiDuration,$smbRate,$smbSmb,$smbDuration,$diffRate,$diffSmb,$constrainedMaxIOB,$constrainedMaxBasal,$microBolusFlag,\"$aimiReason\",\"$smbReason\"\n"

        try {
            FileWriter(logFile, true).use { it.append(line) }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "SMB Comparator log error: ${e.message}", e)
            e.printStackTrace()
        }
    }
}
