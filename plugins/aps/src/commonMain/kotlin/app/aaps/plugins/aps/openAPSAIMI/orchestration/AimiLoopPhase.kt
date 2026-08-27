package app.aaps.plugins.aps.openAPSAIMI.orchestration

/**
 * Coarse milestones inside one determine_basal pass (observe-only; order is semantic, not exhaustive).
 */
enum class AimiLoopPhase {
    /** Profile/meal inputs and caches ready. */
    BOOTSTRAP,

    /** Decision context and loop result shell initialized. */
    CONTEXT,

    /** IOB row, meal flags, trend/signal prep (pre-main branching). */
    SIGNAL_PREPARATION,

    /** Central IOB / SMB / basal decision tree (heavy section). */
    CORE_DECISION,

    /** Persistence / hormonitor export path. */
    EXPORT,
    ;

    /** Human-readable hint for safe-hold reason lines (dashboard / rT). */
    fun safeHoldHint(): String = when (this) {
        BOOTSTRAP -> "profile & meal inputs"
        CONTEXT -> "decision context init"
        SIGNAL_PREPARATION -> "IOB / trend / signal prep"
        CORE_DECISION -> "SMB & basal decision tree"
        EXPORT -> "result export / persistence"
    }
}
