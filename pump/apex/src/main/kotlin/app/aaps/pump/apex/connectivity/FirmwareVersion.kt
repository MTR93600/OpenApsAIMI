package app.aaps.pump.apex.connectivity

enum class FirmwareVersion(
    val major: Int,
    val minor: Int,
    val protocolVersion: ProtocolVersion,
    val engineeringModeOnly: Boolean = false,
) {
    AUTO(0, 0, ProtocolVersion.PROTO_4_10),

    FW_6_24(6, 24, ProtocolVersion.PROTO_4_9),

    // The versions below support Version() command.

    FW_6_25(6, 25, ProtocolVersion.PROTO_4_10, engineeringModeOnly = true),

    FW_6_27(6, 27, ProtocolVersion.PROTO_4_11, engineeringModeOnly = true),
    FW_6_28(6, 28, ProtocolVersion.PROTO_4_11, engineeringModeOnly = true);

    val displayName get() = "B1.1.0.${major}0${minor} (v${protocolVersion.major}.${protocolVersion.minor})"

    companion object {
        val realValues get() = entries.drop(1)
    }
}