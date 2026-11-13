package app.aaps.pump.omnipod.dashctl.pod.definition

data class SoftwareVersion(
    val major: Short,
    val minor: Short,
    val interim: Short
) {

    override fun toString(): String {
        return "$major.$minor.$interim"
    }
}
