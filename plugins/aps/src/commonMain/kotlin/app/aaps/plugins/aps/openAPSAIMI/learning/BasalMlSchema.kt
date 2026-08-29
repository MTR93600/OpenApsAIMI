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
