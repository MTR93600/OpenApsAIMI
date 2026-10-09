package app.aaps.plugins.aimitestkit

/**
 * Renders a human-readable replay report.
 *
 * [outcomes] and [diffs] are zipped by index; a length mismatch is reported
 * instead of crashing, because a truncated replay is itself a finding.
 */
fun renderReport(outcomes: List<AimiReplayOutcome>, diffs: List<AimiTickDiff>): String =
    buildString {
        val passed = diffs.count { it.passed }
        val failed = diffs.size - passed
        appendLine("AIMI replay report: ${outcomes.size} tick(s), $passed passed, $failed failed")
        if (outcomes.size != diffs.size) {
            appendLine(
                "WARNING: ${outcomes.size} outcome(s) but ${diffs.size} diff(s); " +
                    "zipping by index up to the shorter list.",
            )
        }
        val zipped = outcomes.zip(diffs)
        for ((outcome, diff) in zipped) {
            if (diff.passed) {
                appendLine("[PASS] tick ${outcome.tickId}")
            } else {
                appendLine("[FAIL] tick ${outcome.tickId} (${diff.diffs.size} difference(s))")
                for (fieldDiff in diff.diffs) {
                    appendLine(
                        "  - ${fieldDiff.field}: " +
                            "expected=${fieldDiff.expected} actual=${fieldDiff.actual}",
                    )
                }
            }
        }
    }
