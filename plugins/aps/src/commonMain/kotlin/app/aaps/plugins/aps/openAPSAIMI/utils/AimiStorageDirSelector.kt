package app.aaps.plugins.aps.openAPSAIMI.utils

/**
 * Which tier of the AIMI storage strategy won.
 *
 * 1. `DOCUMENTS_AAPS` — preferred, coherent with the AIMI design, user-visible.
 * 2. `APP_SCOPED_EXTERNAL` — fallback, no permissions required.
 * 3. `INTERNAL_ONLY` — last resort, always available.
 */
enum class AimiStorageTier {
    DOCUMENTS_AAPS,
    APP_SCOPED_EXTERNAL,
    INTERNAL_ONLY,
}

/** The winning directory plus why the others lost. */
data class AimiStorageSelection(
    val tier: AimiStorageTier,
    val path: String,
    /** Human-readable reason, for health logs. Null when the preferred tier won outright. */
    val reason: String?,
)

/**
 * The AIMI 3-tier storage directory strategy, platform-independent.
 *
 * The platform supplies the candidate directories and the filesystem probes; the *order* and the
 * *fallback rules* live here so Android and iOS resolve the same way. Probes are lambdas (not an
 * interface) to keep the call site a one-liner.
 *
 * Guarantee, same as the Android original: never throws, always returns a directory.
 */
object AimiStorageDirSelector {

    fun select(
        documentsAaps: String?,
        appExternal: String?,
        internalDir: String,
        exists: (String) -> Boolean,
        mkdirs: (String) -> Boolean,
        canWrite: (String) -> Boolean,
    ): AimiStorageSelection {
        var lastReason: String? = null

        // Tier 1: Documents/AAPS (preferred)
        if (documentsAaps != null) {
            try {
                if (!exists(documentsAaps)) {
                    mkdirs(documentsAaps)
                }
                if (exists(documentsAaps) && canWrite(documentsAaps)) {
                    return AimiStorageSelection(AimiStorageTier.DOCUMENTS_AAPS, documentsAaps, null)
                }
                lastReason = "Documents/AAPS not writable (permission issue?)"
            } catch (e: Exception) {
                lastReason = "Cannot access Documents/AAPS: ${e.message}"
            }
        } else {
            lastReason = "Documents/AAPS candidate unavailable"
        }

        // Tier 2: app-scoped external storage
        if (appExternal != null) {
            try {
                if (exists(appExternal) || mkdirs(appExternal)) {
                    return AimiStorageSelection(
                        AimiStorageTier.APP_SCOPED_EXTERNAL,
                        appExternal,
                        lastReason
                    )
                }
            } catch (e: Exception) {
                lastReason = "Cannot access external app storage: ${e.message}"
            }
        }

        // Tier 3: internal storage (always available)
        return AimiStorageSelection(AimiStorageTier.INTERNAL_ONLY, internalDir, lastReason)
    }
}
