package app.aaps.pump.omnipod.dashctl.util

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.defs.determineCorrectBasalSize
import app.aaps.pump.omnipod.dashctl.pod.definition.BasalProgram
import kotlin.math.roundToInt

/**
 * The basal program the pod runs for [profile]. Port of Android
 * `app.aaps.pump.omnipod.common.util.mapProfileToBasalProgram`, line by line, with the same checks
 * and the same error texts: each basal value starts on a half hour (a pod slot), the first one at 0,
 * and each rate is rounded to what the pump takes (`determineCorrectBasalSize`).
 *
 * @throws IllegalArgumentException when the profile has no basal value, or a value does not start on
 *   a half hour inside the day, or the first one does not start at 0.
 */
fun mapProfileToBasalProgram(profile: Profile, pumpType: PumpType): BasalProgram {
    val basalValues = profile.getBasalValues()
    require(basalValues.isNotEmpty()) { "Basal values should contain values" }

    val entries = mutableListOf<BasalProgram.Segment>()
    var previousBasalValue: Profile.ProfileValue? = null

    for (basalValue in basalValues) {
        require(basalValue.timeAsSeconds < 86_400) { "Basal segment start time can not be greater than 86400" }
        require(basalValue.timeAsSeconds >= 0) { "Basal segment start time can not be less than 0" }
        require(basalValue.timeAsSeconds % 1_800 == 0) { "Basal segment time should be dividable by 30 minutes" }

        val startSlotIndex = (basalValue.timeAsSeconds / 1_800).toShort()
        previousBasalValue?.let { previous ->
            entries.add(
                BasalProgram.Segment(
                    (previous.timeAsSeconds / 1_800).toShort(),
                    startSlotIndex,
                    (pumpType.determineCorrectBasalSize(previous.value) * 100).roundToInt()
                )
            )
        }

        require(entries.isNotEmpty() || basalValue.timeAsSeconds == 0) { "First basal segment start time should be 0" }
        require(entries.isEmpty() || entries.last().endSlotIndex == startSlotIndex) {
            "Illegal start time for basal segment: does not match previous previous segment's end time"
        }
        previousBasalValue = basalValue
    }

    val lastBasalValue = requireNotNull(previousBasalValue)
    entries.add(
        BasalProgram.Segment(
            (lastBasalValue.timeAsSeconds / 1_800).toShort(),
            48,
            (pumpType.determineCorrectBasalSize(lastBasalValue.value) * 100).roundToInt()
        )
    )
    return BasalProgram(entries)
}
