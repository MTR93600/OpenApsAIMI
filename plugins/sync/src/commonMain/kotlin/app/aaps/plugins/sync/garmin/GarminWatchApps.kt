package app.aaps.plugins.sync.garmin

/**
 * The watch apps Android pushes to (`GarminPlugin.glucoseAppIds`), in the same order.
 *
 * The id is the Connect IQ application id, 32 hex characters, no dashes. The iPhone SDK wants the
 * same id as a UUID, which is that hex with dashes. The name is only for logs: the watch matches
 * the id.
 */
internal object GarminWatchApps {

    val byId: Map<String, String> = linkedMapOf(
        "C9E90EE7E6924829A8B45E7DAFFF5CB4" to "GlucoseWatch_Dev",
        "1107CA6C2D5644B998D4BCB3793F2B7C" to "GlucoseDataField_Dev",
        "928FE19A4D3A4259B50CB6F9DDAF0F4A" to "GlucoseWidget_Dev",
        "662DFCF7F5A147DE8BD37F09574ADB11" to "GlucoseWatch",
        "815C7328C21248C493AD9AC4682FE6B3" to "GlucoseDataField",
        "4BDDCC1740084A1FAB83A3B2E2FCF55B" to "GlucoseWidget",
    )

    /** `C9E90EE7E6924829A8B45E7DAFFF5CB4` → `C9E90EE7-E692-4829-A8B4-5E7DAFFF5CB4`. */
    fun dashedUuid(hex: String): String {
        val clean = hex.replace("-", "").uppercase()
        require(clean.length == 32) { "Connect IQ app id must be 32 hex characters, got '$hex'" }
        return clean.substring(0, 8) + "-" +
            clean.substring(8, 12) + "-" +
            clean.substring(12, 16) + "-" +
            clean.substring(16, 20) + "-" +
            clean.substring(20)
    }
}
