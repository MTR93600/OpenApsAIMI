package app.aaps.plugins.aps.openAPSAIMI.pkpd

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Plain publish/subscribe cache for the loop's most recent [AdvancedPredictionCurves], mirroring
 * [app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateRuntimeRepository]'s shape for consistency.
 * Published from exactly one point in `DetermineBasalAIMI2.kt` per tick that
 * reaches the late PKPD refine stage — ticks that return earlier (T3C brittle mode, meal-advisor gate, hard
 * brake, glucose abort) do not publish a fresh snapshot that cycle. This is a read-only diagnostic/dashboard
 * side-channel; nothing doses on it.
 */
object TrajectoryRuntimeRepository {

    private val latestRef = AtomicReference<AdvancedPredictionCurves?>(null)
    private val updatesFlow = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val updates: SharedFlow<Unit> = updatesFlow.asSharedFlow()

    fun publish(curves: AdvancedPredictionCurves) {
        latestRef.set(curves)
        updatesFlow.tryEmit(Unit)
    }

    fun getLatest(): AdvancedPredictionCurves? = latestRef.get()
}
