package app.aaps.plugins.aps.openAPSAIMI

import com.google.common.truth.FailureMetadata
import com.google.common.truth.Subject
import com.google.common.truth.Subject.Factory
import com.google.common.truth.Truth.assertAbout
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.model.DecisionResult

/**
 * Custom Google Truth Subject for [AutoDriveState].
 */
class AutoDriveStateSubject(
    metadata: FailureMetadata,
    private val actual: AutoDriveState?
) : Subject(metadata, actual) {

    companion object {
        private val FACTORY: Factory<AutoDriveStateSubject, AutoDriveState> =
            Factory { metadata, actual -> AutoDriveStateSubject(metadata, actual) }

        fun assertThat(actual: AutoDriveState?): AutoDriveStateSubject =
            assertAbout(FACTORY).that(actual)
    }

    fun hasBg(expected: Double, tolerance: Double = 0.001) {
        check("bg").that(actual?.bg).isWithin(tolerance).of(expected)
    }

    fun hasVelocity(expected: Double, tolerance: Double = 0.001) {
        check("bgVelocity").that(actual?.bgVelocity).isWithin(tolerance).of(expected)
    }

    fun hasIob(expected: Double, tolerance: Double = 0.001) {
        check("iob").that(actual?.iob).isWithin(tolerance).of(expected)
    }

    fun isNight() {
        check("isNight").that(actual?.isNight).isTrue()
    }

    fun isDay() {
        check("isNight").that(actual?.isNight).isFalse()
    }
}

/**
 * Custom Google Truth Subject for [DecisionResult].
 */
class DecisionResultSubject(
    metadata: FailureMetadata,
    private val actual: DecisionResult?
) : Subject(metadata, actual) {

    companion object {
        private val FACTORY: Factory<DecisionResultSubject, DecisionResult> =
            Factory { metadata, actual -> DecisionResultSubject(metadata, actual) }

        fun assertThat(actual: DecisionResult?): DecisionResultSubject =
            assertAbout(FACTORY).that(actual)
    }

    fun isApplied() {
        if (actual !is DecisionResult.Applied) {
            failWithActual(com.google.common.truth.Fact.simpleFact("expected to be Applied"))
        }
    }

    fun isFallthrough() {
        if (actual !is DecisionResult.Fallthrough) {
            failWithActual(com.google.common.truth.Fact.simpleFact("expected to be Fallthrough"))
        }
    }

    fun hasReasonContaining(substring: String) {
        val reason = when (actual) {
            is DecisionResult.Applied -> actual.reason
            is DecisionResult.Fallthrough -> actual.reason
            else -> null
        }
        check("reason").that(reason).contains(substring)
    }
}

/**
 * Custom Google Truth Subject for [AutoDriveCommand].
 */
class AutoDriveCommandSubject(
    metadata: FailureMetadata,
    private val actual: AutoDriveCommand?
) : Subject(metadata, actual) {

    companion object {
        private val FACTORY: Factory<AutoDriveCommandSubject, AutoDriveCommand> =
            Factory { metadata, actual -> AutoDriveCommandSubject(metadata, actual) }

        fun assertThat(actual: AutoDriveCommand?): AutoDriveCommandSubject =
            assertAbout(FACTORY).that(actual)
    }

    fun hasReasonContaining(substring: String) {
        check("reason").that(actual?.reason).contains(substring)
    }

    fun hasSmb(expected: Double, tolerance: Double = 0.001) {
        check("scheduledMicroBolus").that(actual?.scheduledMicroBolus).isWithin(tolerance).of(expected)
    }

    fun hasTbr(expected: Double, tolerance: Double = 0.001) {
        check("temporaryBasalRate").that(actual?.temporaryBasalRate).isWithin(tolerance).of(expected)
    }
}

/**
 * Helper to start Truth chain for AIMI types.
 */
object AimiTruth {
    fun assertThat(actual: AutoDriveState?): AutoDriveStateSubject = AutoDriveStateSubject.assertThat(actual)
    fun assertThat(actual: DecisionResult?): DecisionResultSubject = DecisionResultSubject.assertThat(actual)
    fun assertThat(actual: AutoDriveCommand?): AutoDriveCommandSubject = AutoDriveCommandSubject.assertThat(actual)
}
