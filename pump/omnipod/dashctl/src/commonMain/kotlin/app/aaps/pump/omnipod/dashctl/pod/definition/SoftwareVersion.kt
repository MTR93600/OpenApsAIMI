package app.aaps.pump.omnipod.dashctl.pod.definition

data class SoftwareVersion(
    private val major: Short,
    private val minor: Short,
    private val interim: Short
) {

    override fun toString(): String {
        return "$major.$minor.$interim"
    }
}
