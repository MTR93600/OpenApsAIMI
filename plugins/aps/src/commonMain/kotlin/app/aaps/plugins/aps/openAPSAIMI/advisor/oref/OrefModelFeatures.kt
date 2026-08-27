package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

/**
 * Feature vector aligned with OREF-INV `models/model_meta.json` (v5 analysis).
 * Order must match training export for future ONNX / bundled inference.
 */
object OrefModelFeatures {

    val NAMES: List<String> = listOf(
        "cgm_mgdl",
        "sug_current_target",
        "sug_ISF",
        "sug_CR",
        "sug_sensitivityRatio",
        "sug_rate",
        "sug_duration",
        "sug_insulinReq",
        "sug_eventualBG",
        "sug_threshold",
        "sug_expectedDelta",
        "sug_minDelta",
        "iob_iob",
        "iob_basaliob",
        "iob_bolusiob",
        "iob_activity",
        "iob_netbasalinsulin",
        "sug_COB",
        "reason_Dev",
        "reason_BGI",
        "reason_minGuardBG",
        "direction_num",
        "hour",
        "has_dynisf",
        "has_smb",
        "has_uam",
        "bg_above_target",
        "isf_ratio",
        "iob_pct_max",
        "sr_deviation",
        "dynisf_x_sr",
        "dynisf_x_isf_ratio",
        "maxSMBBasalMinutes",
        "maxUAMSMBBasalMinutes",
        "sug_smb_units",
    )

    val COUNT: Int = NAMES.size

    fun indexOf(name: String): Int = NAMES.indexOf(name).also { require(it >= 0) { "Unknown feature $name" } }
}
