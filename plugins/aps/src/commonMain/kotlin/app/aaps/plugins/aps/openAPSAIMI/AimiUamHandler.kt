package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.resources.TextResolver

/**
 * UAM (unannounced-meal) inference entry point for the engine.
 *
 * The pure state — live runtime confidence and the persisted-confidence supplier —
 * lives here in common code. Model inference itself is platform-specific, so
 * [predictSmbUam] delegates to an installed [AimiUamPredictor]:
 * - Android installs its TFLite interpreter.
 * - iOS has [IosUamTflite][app.aaps.plugins.aps.openAPSAIMI.ml.IosUamTflite] for raw
 *   inference, not yet wired — until then the model-absent path applies.
 *
 * With no predictor installed, [predictSmbUam] returns 0f (model absent). The
 * engine already handles that: the SMB falls back to the rule-based dose.
 */
object AimiUamHandler {

    /** Platform inference backend. Null = model absent. */
    fun interface AimiUamPredictor {
        /** Returns the raw SMB dose in units, >= 0. */
        fun predict(features: FloatArray, reason: StringBuilder?, rh: TextResolver): Float
    }

    @Volatile private var predictor: AimiUamPredictor? = null
    @Volatile private var runtimeConfidence: Double? = null
    @Volatile private var confidenceSupplier: (() -> Double?)? = null

    /** Installed once by the platform shell that owns the model. */
    fun installPredictor(predictor: AimiUamPredictor?) {
        this.predictor = predictor
    }

    /** Called by the UAM detector to push a live confidence (0..1). */
    fun updateRuntimeConfidence(value: Double?) {
        runtimeConfidence = value?.coerceIn(0.0, 1.0)
    }

    /** Installed by the plugin (which owns Preferences) to read a persisted confidence. */
    fun installConfidenceSupplier(supplier: (() -> Double?)?) {
        confidenceSupplier = supplier
    }

    /** Safe read with no context/DI dependency. Order: runtime -> supplier -> 0.0. */
    fun confidenceOrZero(): Double {
        runtimeConfidence?.let { return it.coerceIn(0.0, 1.0) }
        val fromSupplier = try { confidenceSupplier?.invoke() } catch (_: Throwable) { null }
        return (fromSupplier ?: 0.0).coerceIn(0.0, 1.0)
    }

    // Kept for callers written against the old Android singleton.
    fun getInstance(): AimiUamHandler = this

    /**
     * Runs the UAM model on [features].
     *
     * @param features ready FloatArray (UAM size/order)
     * @param reason optional StringBuilder for visible logs
     * @param rh text resolver for reason lines
     * @return raw SMB (>= 0); 0f when no model is installed. Callers coerce as needed.
     */
    fun predictSmbUam(
        features: FloatArray,
        reason: StringBuilder? = null,
        rh: TextResolver,
    ): Float {
        val predictor = predictor ?: return 0f
        return try {
            predictor.predict(features, reason, rh).coerceAtLeast(0f)
        } catch (_: Throwable) {
            0f
        }
    }
}
