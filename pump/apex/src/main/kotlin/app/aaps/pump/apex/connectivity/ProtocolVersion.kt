package app.aaps.pump.apex.connectivity

enum class ProtocolVersion(
    val major: Int,
    val minor: Int,
) {
    /** This protocol comes with the 6.24 firmware version.
     * * GetValue(Version) doesn't exist yet. We need to set the firmware version manually.
     * * RequestHeartbeat() doesn't exist yet.
     **/
    PROTO_4_9(4, 9),

    /** The first officially supported protocol (6.25+)
     * * New R/O values: `Version`
     **/
    PROTO_4_10(4, 10),

    /** This protocol comes with the 6.27, 6.28 firmware versions.
     * * New commands: `GetLatestTemporaryBasals`, V2 versions of UpdateSettings, Status
     **/
    PROTO_4_11(4, 11),
}