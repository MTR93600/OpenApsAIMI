package app.aaps.plugins.aps.openAPSAIMI.comparison

import android.content.Context
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSSMB.DetermineBasalSMB
import android.os.Environment
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
    private val context: Context
) {
    private val logFile by lazy {
        val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS")
        File(externalDir, "comparison_aimi_smb.csv").apply {
            parentFile?.mkdirs()
            if (!exists()) {
                writeText("Timestamp,Date,BG,IOB,COB,AIMI_Rate,AIMI_SMB,AIMI_Duration,SMB_Rate,SMB_SMB,SMB_Duration,Diff_Rate,Diff_SMB,Reason_AIMI,Reason_SMB\n")
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
            // Map Profile
            val profileSmb = mapProfile(profileAimi)

            // Run SMB (Shadow Mode)
            val smbResult = determineBasalSMB.determine_basal(
                glucose_status = glucoseStatus,
                currenttemp = currentTemp,
                iob_data_array = iobData,
                profile = profileSmb,
                autosens_data = autosens,
                meal_data = mealData,
                microBolusAllowed = microBolusAllowed,
                currentTime = currentTime,
                flatBGsDetected = flatBGsDetected,
                dynIsfMode = dynIsfMode
            )

            logComparison(aimiResult, smbResult, glucoseStatus, iobData.firstOrNull()?.iob ?: 0.0, mealData.mealCOB)

        } catch (e: Exception) {
            android.util.Log.e("AimiSmbComparator", "Error in compare", e)
            e.printStackTrace()
        }
    }

    private fun logComparison(aimi: RT, smb: RT, gs: GlucoseStatus, iob: Double, cob: Double) {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(aimi.timestamp!!))
        
        val aimiRate = aimi.rate
        // In AIMI, if duration is 0, it might be an SMB. But let's check insulinReq.
        // Usually insulinReq holds the SMB amount if it's an SMB decision.
        val aimiSmb = aimi.insulinReq
        
        val smbRate = smb.rate
        val smbSmb = smb.insulinReq

        val diffRate = aimiRate?.minus(smbRate!!)
        val diffSmb = aimiSmb?.minus(smbSmb!!)

        // Escape quotes in reasons
        val rAimi = aimi.reason.toString().replace("\"", "'")
        val rSmb = smb.reason.toString().replace("\"", "'")

        val row = "${aimi.timestamp},$date,${gs.glucose},$iob,$cob,$aimiRate,$aimiSmb,${aimi.duration},$smbRate,$smbSmb,${smb.duration},$diffRate,$diffSmb,\"$rAimi\",\"$rSmb\"\n"
        
        try {
            if (!logFile.exists()) {
                logFile.parentFile?.mkdirs()
                logFile.createNewFile()
                logFile.writeText("Timestamp,Date,BG,IOB,COB,AIMI_Rate,AIMI_SMB,AIMI_Duration,SMB_Rate,SMB_SMB,SMB_Duration,Diff_Rate,Diff_SMB,Reason_AIMI,Reason_SMB\n")
            }
            FileWriter(logFile, true).use { it.write(row) }
        } catch (e: Exception) {
            android.util.Log.e("AimiSmbComparator", "Error writing to CSV", e)
        }
    }

    private fun mapProfile(p: OapsProfileAimi): OapsProfile {
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
}
