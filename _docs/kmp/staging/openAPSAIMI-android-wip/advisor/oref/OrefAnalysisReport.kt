package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

/**
 * Local OREF-style analysis (features + outcomes). Optional bundled ONNX models add risk scores on-device.
 */
enum class OrefDataSufficiency {
    /** Not enough CGM/APS or aligned rows for a reliable view. */
    INSUFFICIENT,
    /** Some signal; interpret trends cautiously. */
    LIMITED,
    /** Enough aligned rows and labels for a stable window summary. */
    GOOD,
}

/** On-device personal MLP (Advisor) status — independent of bundled ONNX. */
enum class OrefPersonalMlStatus {
    OFF,
    INSUFFICIENT_DATA,
    TRAINED_AND_USED,
    TRAIN_FAILED,
}

data class OrefAnalysisReport(
    val windowDays: Int,
    val mergedRowCount: Int,
    val validOutcomeRows: Int,
    /** % time BG < 70 on merged CGM timeline */
    val timeBelow70Pct: Double?,
    /** % time BG > 180 */
    val timeAbove180Pct: Double?,
    /** % time 70–180 */
    val timeInRange70180Pct: Double?,
    /** Fraction of 4h windows with any BG < 70 (labelled rows only) */
    val actualHypo4hPct: Double?,
    /** Fraction of 4h windows with any BG > 180 */
    val actualHyper4hPct: Double?,
    val priority: OrefGlycemicPriority,
    val mlStatus: OrefMlStatus,
    /** Feature name → % missing (0–100) on merged rows */
    val featureMissingPct: Map<String, Double>,
    val hints: List<String>,
    /** Mean raw hypo model output (0–100% probability scale) over aligned rows, if ONNX ran */
    val meanRawHypoRiskPct: Double? = null,
    /** Mean decile-calibrated hypo risk (0–100%), if calibration fit */
    val meanCalHypoRiskPct: Double? = null,
    val meanRawHyperRiskPct: Double? = null,
    val meanCalHyperRiskPct: Double? = null,
    /** Mean predicted BG change (model units, typically mg/dL / horizon used in training) */
    val meanBgChangePred: Double? = null,
    /** Extra detail for [OrefMlStatus.LOAD_FAILED] */
    val mlErrorDetail: String? = null,
    val dataSufficiency: OrefDataSufficiency = OrefDataSufficiency.GOOD,
    val personalMlStatus: OrefPersonalMlStatus = OrefPersonalMlStatus.OFF,
    /**
     * Mean personal hypo head output, averaged over aligned rows. **Not a probability and not calibrated**: the
     * head is trained on 0/1 labels against its raw output and read back through a sigmoid, so the value is
     * squashed into roughly 50..73 and never falls below 50. Informational only — decisions read it through
     * [OrefPersonalSignalGate], and it must not be shown to the user as a percentage.
     */
    val personalMeanHypoSignalPct: Double? = null,
    /** Same as [personalMeanHypoSignalPct] for the hyper head: uncalibrated, informational only. */
    val personalMeanHyperSignalPct: Double? = null,
    val personalMlDetail: String? = null,
) {

    fun toPromptSection(): String = buildString {
        append("--- OREF-STYLE LOCAL ANALYSIS (on-device, no Nightscout) ---\n")
        append("Window: ${windowDays}d | APS-CGM aligned rows: $mergedRowCount | Valid4h outcomes: $validOutcomeRows\n")
        append("Data sufficiency: ${dataSufficiency.name}\n")
        timeInRange70180Pct?.let { append("CGM TIR 70-180 (merged timeline): ${"%.1f".format(it)}%\n") }
        timeBelow70Pct?.let { append("CGM TBR <70: ${"%.1f".format(it)}%\n") }
        timeAbove180Pct?.let { append("CGM TAR >180: ${"%.1f".format(it)}%\n") }
        actualHypo4hPct?.let { append("4h hypo exposure (labelled windows): ${"%.1f".format(it)}%\n") }
        actualHyper4hPct?.let { append("4h hyper exposure (labelled windows): ${"%.1f".format(it)}%\n") }
        append("Priority heuristic: ${priority.name}\n")
        append("ML: ${mlStatus.userMessage}\n")
        mlErrorDetail?.let { append("ML detail: $it\n") }
        if (meanRawHypoRiskPct != null || meanCalHypoRiskPct != null) {
            meanRawHypoRiskPct?.let { append("ONNX hypo risk (raw mean): ${"%.1f".format(it)}%\n") }
            meanCalHypoRiskPct?.let { append("ONNX hypo risk (calibrated mean): ${"%.1f".format(it)}%\n") }
        }
        if (meanRawHyperRiskPct != null || meanCalHyperRiskPct != null) {
            meanRawHyperRiskPct?.let { append("ONNX hyper risk (raw mean): ${"%.1f".format(it)}%\n") }
            meanCalHyperRiskPct?.let { append("ONNX hyper risk (calibrated mean): ${"%.1f".format(it)}%\n") }
        }
        meanBgChangePred?.let { append("ONNX mean predicted BG change: ${"%.2f".format(it)}\n") }
        append("Personal MLP (Advisor): ${personalMlStatus.name}\n")
        // Kept for debugging, and labelled so it cannot be mistaken for a probability. The head trains on 0/1
        // labels against its raw output and is read back through a sigmoid, so this number is compressed into
        // roughly 50..73 and never drops below 50. The "%" sign is gone on purpose: it is not a percentage.
        // This block is also shown in the technical section of the Advisor screen, so the label has to hold up
        // both for a reader and for the AI Coach prompt.
        personalMeanHypoSignalPct?.let { append("Personal hypo score (uncalibrated internal number, NOT a probability, do not report as a risk): ${"%.1f".format(it)}\n") }
        personalMeanHyperSignalPct?.let { append("Personal hyper score (uncalibrated internal number, NOT a probability, do not report as a risk): ${"%.1f".format(it)}\n") }
        personalMlDetail?.let { append("Personal MLP detail: $it\n") }
        if (hints.isNotEmpty()) {
            append("Hints:\n")
            hints.forEach { append("- $it\n") }
        }
        val worstMissing = featureMissingPct.entries.filter { it.value > 30.0 }.sortedByDescending { it.value }.take(5)
        if (worstMissing.isNotEmpty()) {
            append("Sparse features (>30% missing): ")
            append(worstMissing.joinToString { "${it.key}=${"%.0f".format(it.value)}%" })
            append("\n")
        }
        if (mlStatus == OrefMlStatus.NOT_BUNDLED) {
            append("NOTE: Add hypo_lgbm.onnx, hyper_lgbm.onnx, bg_change_lgbm.onnx under assets/oref/ for local risk scores.\n")
        }
    }

    /**
     * Compact facts for the AI Coach — no new numbers vs [toPromptSection]; reframed for plain-language coaching.
     */
    fun toCoachUserInsightsSection(): String = buildString {
        append("--- USER INSIGHTS (structured, on-device) ---\n")
        append("Analysis window: ${windowDays} days.\n")
        append("Data quality: ${dataSufficiency.name} (aligned loop+CGM rows: $mergedRowCount, labelled 4h outcomes: $validOutcomeRows).\n")
        timeInRange70180Pct?.let { append("Time in range 70–180 mg/dL: ${"%.0f".format(it)}%.\n") }
        timeBelow70Pct?.let { append("Time below 70 mg/dL: ${"%.0f".format(it)}%.\n") }
        timeAbove180Pct?.let { append("Time above 180 mg/dL: ${"%.0f".format(it)}%.\n") }
        append("Heuristic focus: ${priority.name}.\n")
        when (personalMlStatus) {
            // No number on purpose: the coach text is read by the user, and the personal score is not a
            // calibrated risk percentage. See `OrefPersonalSignalGate`.
            OrefPersonalMlStatus.TRAINED_AND_USED ->
                append("Personal on-device model: trained, but its score is an uncalibrated internal number. Do not quote it and do not treat it as a risk.\n")
            OrefPersonalMlStatus.INSUFFICIENT_DATA -> append("Personal on-device model: not enough labelled history to train yet.\n")
            OrefPersonalMlStatus.TRAIN_FAILED -> append("Personal on-device model: last training or inference failed (see technical block).\n")
            OrefPersonalMlStatus.OFF -> append("Personal on-device model: disabled in settings (bundled ONNX may still run).\n")
        }
        append("Safety: Do not invent percentages; do not prescribe insulin changes—suggest discussing with a care team.\n")
    }
}

enum class OrefGlycemicPriority {
    HYPO,
    HYPER,
    BOTH,
    BALANCED,
    WELL_CONTROLLED,
}

enum class OrefMlStatus(val userMessage: String) {
    NOT_BUNDLED("Risk scores: ONNX models not in assets (optional)."),
    LOAD_FAILED("Risk scores: ONNX load/inference failed."),
    OK("Risk scores: on-device ONNX OK."),
}
