package app.aaps.plugins.aps.openAPSAIMI.learning

/**
 * Model input width, in the one place both halves of the basal ML path can see it.
 *
 * `BasalMlTrainingCoordinator` (androidMain) trains the two heads and `BasalNeuralLearner`
 * (commonMain) loads and applies them. They must agree on the input width or the loader refuses every
 * model the trainer publishes. The number used to live only in the coordinator's companion, which
 * shared code cannot see, so it lives here now. The coordinator keeps its own `INPUT_SIZE` as an alias
 * so its 800 lines are untouched.
 */
internal object BasalMlSchema {

    /**
     * 6 glucose-dynamics base features + 10 physiological-context features (mirror of the SMB schema:
     * 4 latent + 3 patient-mode + 3 causal). Keep in sync with `BasalNeuralLearner.modelInput` and the
     * dataset parser.
     */
    const val INPUT_SIZE = 16

    /**
     * Value of model input slot 4 (`duraAvg`) at run time AND in the training rows. It is always 0, on
     * purpose.
     *
     * Until 2026-11 the loop never filled `duraISFaverage`: it was 0 on every tick. So every basal and
     * T3C model in the field was trained with 0 in this slot. A column that is constant in training keeps
     * mean 0 / std 1 in the network normalisation, so the weights of this slot were never trained. The
     * real value is a BG average in mg/dL (close to the current BG), and sending it would push a large
     * number into those untrained weights and change the dose in a way nobody has measured.
     *
     * So `BasalNeuralLearner.modelInput` and `BasalMlDatasetParser` both write this constant in slot 4,
     * whatever value the caller or the CSV holds. The CSV still logs the real value, so a future model
     * can learn from it. Remove this constant, and use the real value, only together with a schema change
     * that retrains both heads from zero.
     */
    const val MODEL_DURA_AVG = 0.0
}

/**
 * Column names shared by the CSV writer (`BasalNeuralLearner.logRecord`) and `BasalMlDatasetParser`.
 *
 * These two columns were added so a label window that also received insulin or carbs can be dropped.
 * Rows written before they existed simply do not have them; the parser reads by name and treats a
 * missing column as "unknown", never as zero.
 */
internal object BasalCsvSchema {
    /** Insulin delivered outside basal at this tick (SMB + manual bolus), in units. */
    const val COL_BOLUS_U = "bolusU"

    /** Carbs on board at this tick, in grams. */
    const val COL_COB_G = "cobG"

    val causalColumns: List<String> = listOf(COL_BOLUS_U, COL_COB_G)
}
