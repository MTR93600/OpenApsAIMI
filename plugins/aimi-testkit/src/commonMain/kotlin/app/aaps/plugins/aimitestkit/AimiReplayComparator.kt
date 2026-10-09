package app.aaps.plugins.aimitestkit

import app.aaps.plugins.aimicontracts.AimiTherapyCommand
import app.aaps.plugins.aimicontracts.AimiTickResult
import kotlin.math.abs

/** Absolute tolerance for Double comparison in replayed commands. */
const val AimiReplayDoubleEpsilon = 1e-9

/** One field that differs between the expected and the actual tick result. */
data class AimiFieldDiff(
    val field: String,
    val expected: String,
    val actual: String,
)

/** Structured diff of one tick. Empty [diffs] means the tick passed. */
data class AimiTickDiff(
    val diffs: List<AimiFieldDiff>,
) {
    val passed: Boolean get() = diffs.isEmpty()
}

private fun doublesEqual(a: Double, b: Double, epsilon: Double = AimiReplayDoubleEpsilon): Boolean =
    a == b || abs(a - b) <= epsilon

private fun commandTypeName(command: AimiTherapyCommand): String =
    when (command) {
        is AimiTherapyCommand.Hold -> "Hold"
        is AimiTherapyCommand.Smb -> "Smb"
        is AimiTherapyCommand.TempBasal -> "TempBasal"
    }

/**
 * Compares two therapy commands field by field.
 *
 * [prefix] scopes the field names (for example `"command"` or `"pairedCommand"`).
 * Double fields use [AimiReplayDoubleEpsilon]; everything else is exact.
 */
private fun compareCommands(
    prefix: String,
    expected: AimiTherapyCommand,
    actual: AimiTherapyCommand,
    diffs: MutableList<AimiFieldDiff>,
) {
    val expectedType = commandTypeName(expected)
    val actualType = commandTypeName(actual)
    if (expectedType != actualType) {
        diffs += AimiFieldDiff("$prefix.type", expectedType, actualType)
        return
    }
    when {
        expected is AimiTherapyCommand.Hold && actual is AimiTherapyCommand.Hold -> {
            if (expected.reasonCode != actual.reasonCode) {
                diffs += AimiFieldDiff("$prefix.reasonCode", expected.reasonCode, actual.reasonCode)
            }
        }
        expected is AimiTherapyCommand.Smb && actual is AimiTherapyCommand.Smb -> {
            if (!doublesEqual(expected.insulinU, actual.insulinU)) {
                diffs += AimiFieldDiff(
                    "$prefix.insulinU",
                    expected.insulinU.toString(),
                    actual.insulinU.toString(),
                )
            }
        }
        expected is AimiTherapyCommand.TempBasal && actual is AimiTherapyCommand.TempBasal -> {
            if (!doublesEqual(expected.rateUPerHour, actual.rateUPerHour)) {
                diffs += AimiFieldDiff(
                    "$prefix.rateUPerHour",
                    expected.rateUPerHour.toString(),
                    actual.rateUPerHour.toString(),
                )
            }
            if (expected.durationMs != actual.durationMs) {
                diffs += AimiFieldDiff(
                    "$prefix.durationMs",
                    expected.durationMs.toString(),
                    actual.durationMs.toString(),
                )
            }
        }
    }
}

/**
 * Compares the recorded [expected] tick result with the replayed [actual] result.
 *
 * Compared, in this order: command (type and values), `nextState.generation`,
 * training events (size then content), persistence events (size then content),
 * `telemetry.reasonCode`, `safety.holdReasonCode`, and the paired command.
 * Trace text and rationale are intentionally NOT compared: identical decisions
 * with different wording are accepted (blueprint severity C4).
 */
fun compare(expected: AimiTickResult, actual: AimiTickResult): AimiTickDiff {
    val diffs = mutableListOf<AimiFieldDiff>()

    compareCommands("command", expected.command, actual.command, diffs)

    if (expected.nextState.generation != actual.nextState.generation) {
        diffs += AimiFieldDiff(
            "nextState.generation",
            expected.nextState.generation.toString(),
            actual.nextState.generation.toString(),
        )
    }

    if (expected.trainingEvents.size != actual.trainingEvents.size) {
        diffs += AimiFieldDiff(
            "trainingEvents.size",
            expected.trainingEvents.size.toString(),
            actual.trainingEvents.size.toString(),
        )
    } else if (expected.trainingEvents != actual.trainingEvents) {
        diffs += AimiFieldDiff(
            "trainingEvents",
            expected.trainingEvents.toString(),
            actual.trainingEvents.toString(),
        )
    }

    if (expected.persistenceEvents.size != actual.persistenceEvents.size) {
        diffs += AimiFieldDiff(
            "persistenceEvents.size",
            expected.persistenceEvents.size.toString(),
            actual.persistenceEvents.size.toString(),
        )
    } else if (expected.persistenceEvents != actual.persistenceEvents) {
        diffs += AimiFieldDiff(
            "persistenceEvents",
            expected.persistenceEvents.toString(),
            actual.persistenceEvents.toString(),
        )
    }

    if (expected.telemetry.reasonCode != actual.telemetry.reasonCode) {
        diffs += AimiFieldDiff(
            "telemetry.reasonCode",
            expected.telemetry.reasonCode,
            actual.telemetry.reasonCode,
        )
    }

    if (expected.safety.holdReasonCode != actual.safety.holdReasonCode) {
        diffs += AimiFieldDiff(
            "safety.holdReasonCode",
            expected.safety.holdReasonCode.toString(),
            actual.safety.holdReasonCode.toString(),
        )
    }

    val expectedPaired = expected.pairedCommand
    val actualPaired = actual.pairedCommand
    if ((expectedPaired == null) != (actualPaired == null)) {
        diffs += AimiFieldDiff(
            "pairedCommand",
            expectedPaired?.let { commandTypeName(it) }.toString(),
            actualPaired?.let { commandTypeName(it) }.toString(),
        )
    } else if (expectedPaired != null && actualPaired != null) {
        compareCommands("pairedCommand", expectedPaired, actualPaired, diffs)
    }

    return AimiTickDiff(diffs)
}
