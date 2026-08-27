package app.aaps.plugins.aps.openAPSAIMI.release

/**
 * Dynamic hyper severity (relative to target and scenario projection).
 * Not tied to absolute BG thresholds such as 200 mg/dL.
 */
enum class HyperSeverityTier {
    OFF,
    ANTICIPATORY,
    EMERGING,
    ESTABLISHED,
    DEEP,
    ;

    val isReleaseEligible: Boolean
        get() = this != OFF
}
