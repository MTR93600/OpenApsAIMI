package app.aaps.plugins.aps.openAPSAIMI

/**
 * Puts the UAM model that ships with the app at `Documents/AAPS/ml/modelUAM.tflite`, where both
 * predictors read it ([AimiUamInference.MODEL_RELATIVE_PATH]).
 *
 * dev_OAPSAIMI does this in `MainApp` on every start. The KMP port lost that copy, so a phone that never
 * ran dev had no model at all and the UAM model answered 0 on every tick. A file put there by hand does
 * not help on Android either: with scoped storage the app may only read the files it wrote itself.
 *
 * The file is written only when it is missing or differs from the shipped one, so a start does not
 * rewrite it for nothing.
 */
object AimiUamModelInstall {

    enum class Outcome {
        /** The shipped model was written. */
        INSTALLED,

        /** The file is already the shipped model. Nothing was written. */
        ALREADY_CURRENT,

        /** This build ships no model (desktop). Nothing was written. */
        NOT_BUNDLED,

        /** The model could not be written. The UAM model stays unavailable, as before. */
        FAILED,
    }

    /**
     * @param bundled the model shipped with the app, null when there is none
     * @param current reads the installed file: null when there is none, and an exception when it cannot
     *   be read (for example a file another app put there)
     * @param write writes the whole file
     */
    fun install(bundled: ByteArray?, current: () -> ByteArray?, write: (ByteArray) -> Unit): Outcome {
        if (bundled == null || bundled.isEmpty()) return Outcome.NOT_BUNDLED
        val installed = try {
            current()
        } catch (_: Exception) {
            null // Unreadable: try to write over it
        }
        if (installed != null && installed.contentEquals(bundled)) return Outcome.ALREADY_CURRENT
        return try {
            write(bundled)
            Outcome.INSTALLED
        } catch (_: Exception) {
            Outcome.FAILED
        }
    }
}

/** Installs the shipped model on this platform. See [AimiUamModelInstall]. */
internal expect fun installBundledUamModel(): AimiUamModelInstall.Outcome
