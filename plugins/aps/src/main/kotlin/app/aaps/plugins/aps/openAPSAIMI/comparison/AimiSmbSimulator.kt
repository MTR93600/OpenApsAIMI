package app.aaps.plugins.aps.openAPSAIMI.comparison

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSSMB.DetermineBasalSMB
import app.aaps.plugins.aps.openAPSAIMI.DetermineBasalaimiSMB2
import app.aaps.core.interfaces.ui.UiInteraction
import javax.inject.Inject

/**
 * Data structure representing a single point in history to be replayed.
 */
data class SimulationInput(
    val timestamp: Long,
    val glucoseStatus: GlucoseStatusAIMI,
    val currentTemp: CurrentTemp,
    val iobData: Array<IobTotal>,
    val profile: OapsProfileAimi,
    val autosens: AutosensResult,
    val mealData: MealData,
    val microscopicBolusAllowed: Boolean,
    val dynIsfMode: Boolean = true
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SimulationInput

        if (timestamp != other.timestamp) return false
        if (glucoseStatus != other.glucoseStatus) return false
        if (currentTemp != other.currentTemp) return false
        if (!iobData.contentEquals(other.iobData)) return false
        if (profile != other.profile) return false
        if (autosens != other.autosens) return false
        if (mealData != other.mealData) return false
        if (microscopicBolusAllowed != other.microscopicBolusAllowed) return false
        if (dynIsfMode != other.dynIsfMode) return false

        return true
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + glucoseStatus.hashCode()
        result = 31 * result + currentTemp.hashCode()
        result = 31 * result + iobData.contentHashCode()
        result = 31 * result + profile.hashCode()
        result = 31 * result + autosens.hashCode()
        result = 31 * result + mealData.hashCode()
        result = 31 * result + microscopicBolusAllowed.hashCode()
        result = 31 * result + dynIsfMode.hashCode()
        return result
    }
}

/**
 * Simulator to run AIMI vs SMB on historical data.
 */
class AimiSmbSimulator @Inject constructor(
    private val aimiLogic: DetermineBasalaimiSMB2,
    private val smbLogic: DetermineBasalSMB,
    private val comparator: AimiSmbComparator, // Used for converting profiles/Logging if needed
    private val uiInteraction: UiInteraction
) {

    fun runSimulation(inputs: List<SimulationInput>): FullComparisonReport {
        val entries = mutableListOf<ComparisonEntry>()

        inputs.forEach { input ->
            try {
                // Run AIMI
                val aimiResult = aimiLogic.determine_basal(
                    glucose_status = input.glucoseStatus,
                    currenttemp = input.currentTemp,
                    iob_data_array = input.iobData,
                    profile = input.profile,
                    autosens_data = input.autosens,
                    mealData = input.mealData, // Corrected parameter name
                    microBolusAllowed = input.microscopicBolusAllowed,
                    currentTime = input.timestamp,
                    flatBGsDetected = false, // Assuming detection logic handles this upstream or we can pass it
                    dynIsfMode = input.dynIsfMode,
                    uiInteraction = uiInteraction
                )

                // Mock SMB Result for now (in real scenario, mapped inputs would run smbLogic)
                // val smbResult = smbLogic.determine_basal(...)

                // Convert to ComparisonEntry
                val entry = createEntryFromAimi(input, aimiResult)
                entries.add(entry)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Parse metrics from the collected entries (in a real implementation, we'd collect them)
        return ComparisonCsvParser().analyze(entries)
    }

    private fun createEntryFromAimi(input: SimulationInput, aimiResult: RT): ComparisonEntry {
        // Essential fields
        val aimiRate = aimiResult.rate ?: 0.0
        val aimiSmb = aimiResult.units ?: 0.0

        // Note: Missing SMB side data in this stub
        return ComparisonEntry(
            timestamp = input.timestamp,
            date = java.time.Instant.ofEpochMilli(input.timestamp).toString(),
            bg = input.glucoseStatus.glucose,
            delta = input.glucoseStatus.delta,
            shortAvgDelta = input.glucoseStatus.shortAvgDelta,
            longAvgDelta = input.glucoseStatus.longAvgDelta,
            iob = input.iobData.firstOrNull()?.iob ?: 0.0,
            cob = input.mealData.mealCOB,
            aimiRate = aimiRate,
            aimiSmb = aimiSmb,
            aimiDuration = aimiResult.duration ?: 0,
            aimiEventualBg = aimiResult.eventualBG,
            aimiTargetBg = aimiResult.targetBG,
            smbRate = 0.0, // Placeholder
            smbSmb = 0.0, // Placeholder
            smbDuration = 0,
            smbEventualBg = 0.0,
            smbTargetBg = 0.0,
            diffRate = 0.0,
            diffSmb = 0.0,
            diffEventualBg = 0.0,
            maxIob = input.profile.max_iob,
            maxBasal = input.profile.max_basal,
            microBolusAllowed = input.microscopicBolusAllowed,
            aimiInsulin30 = null,
            smbInsulin30 = null,
            cumulativeDiff = 0.0,
            aimiActive = false,
            smbActive = false,
            bothActive = false,
            aimiUamLast = null,
            smbUamLast = null,
            reasonAimi = aimiResult.reason ?: "",
            reasonSmb = "Not Simulated"
        )
    }
}
