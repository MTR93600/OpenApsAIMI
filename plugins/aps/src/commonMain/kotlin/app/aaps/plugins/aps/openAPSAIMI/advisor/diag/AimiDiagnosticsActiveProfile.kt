package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.profile.Profile
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2

/**
 * The `[ACTIVE PROFILE]` section of the AIMI support report.
 *
 * Behaviour from `origin/dev_OAPSAIMI` @ `02c90656b1`. Printed from the same accessors the loop
 * reads (`ProfileFunction.getProfile()`), in mg/dL per U, so a support package cannot confuse the
 * profile editor's `LocalProfile_*` keys with what actually ran.
 *
 * A null profile is stated as such instead of being left out: an absent section would read as
 * "no profile problem", which is the mistake this section exists to stop.
 */
internal object AimiDiagnosticsActiveProfile {

    data class Blocks(
        val name: String?,
        val units: GlucoseUnit,
        val percentage: Int,
        val timeshift: Int,
        val isf: List<Profile.ProfileValue>,
        val ic: List<Profile.ProfileValue>,
        val basal: List<Profile.ProfileValue>,
        val target: List<Profile.ProfileValue>,
    ) {
        companion object {
            fun from(profile: Profile, name: String?): Blocks =
                Blocks(
                    name = name,
                    units = profile.units,
                    percentage = profile.percentage,
                    timeshift = profile.timeshift,
                    isf = profile.getIsfsMgdlValues().toList(),
                    ic = profile.getIcsValues().toList(),
                    basal = profile.getBasalValues().toList(),
                    target = profile.getSingleTargetsMgdl().toList(),
                )
        }
    }

    fun writeSection(sb: StringBuilder, profile: Profile?, name: String?) {
        writeSection(sb, profile?.let { Blocks.from(it, name) })
    }

    fun writeSection(sb: StringBuilder, blocks: Blocks?) {
        sb.append("[ACTIVE PROFILE]\n")
        appendBody(sb, blocks)
        sb.append('\n')
    }

    fun writePreferencesPreamble(sb: StringBuilder) {
        sb.append("[AIMI PREFERENCES]\n")
        sb.append("Note: the LocalProfile_* keys below are the profile editor's content.\n")
        sb.append("They are not always what the loop runs. See [ACTIVE PROFILE] above.\n")
    }

    fun appendBody(sb: StringBuilder, blocks: Blocks?) {
        if (blocks == null) {
            sb.append("Not available when the report was built.\n")
            return
        }
        sb.append("Name: ").append(blocks.name ?: "unknown").append('\n')
        sb.append("Display units: ").append(blocks.units).append('\n')
        sb.append("Percentage: ").append(blocks.percentage).append("%\n")
        sb.append("Timeshift: ").append(blocks.timeshift).append(" h\n")
        appendBlocks(sb, "ISF (mg/dL per U)", blocks.isf)
        appendBlocks(sb, "IC (g per U)", blocks.ic)
        appendBlocks(sb, "Basal (U/h)", blocks.basal)
        appendBlocks(sb, "Target (mg/dL)", blocks.target)
    }

    /** One line per quantity: every block as `hh:mm value`, in the profile's own order. */
    fun appendBlocks(sb: StringBuilder, label: String, values: List<Profile.ProfileValue>) {
        sb.append(label).append(": ")
        if (values.isEmpty()) {
            sb.append("none\n")
            return
        }
        values.forEachIndexed { index, block ->
            if (index > 0) sb.append(", ")
            val hours = block.timeAsSeconds / 3600
            val minutes = (block.timeAsSeconds % 3600) / 60
            sb.append(hours.toString().padStart(2, '0'))
                .append(':')
                .append(minutes.toString().padStart(2, '0'))
                .append(' ')
                .append(aimiFmt2(block.value))
        }
        sb.append('\n')
    }
}
