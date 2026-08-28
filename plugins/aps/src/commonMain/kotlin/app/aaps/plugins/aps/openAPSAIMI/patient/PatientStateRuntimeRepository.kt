package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalBeliefDigest
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal data class PatientRuntimeSnapshot(
    val patientState: PatientStateSnapshot,
    val patientModeDecision: PatientModeOrchestrator.Decision,
    val updatedAtMs: Long = patientState.timestampMs,
    val physioLive: PhysioLiveDigest = PhysioLiveDigest(),
    val thermalBelief: ThermalBeliefDigest = ThermalBeliefDigest.EMPTY,
    val physiologicalTree: PhysiologicalTreeSnapshot? = null,
    val harmoniaDecision: HarmoniaDecision? = null,
    val refreshSource: PatientRefreshSource = PatientRefreshSource.LOOP_TICK,
)

@OptIn(ExperimentalAtomicApi::class)
internal object PatientStateRuntimeRepository {

    private val latestRef = AtomicReference<PatientRuntimeSnapshot?>(null)
    private val loopCacheRef = AtomicReference<PatientStateLoopCache?>(null)
    private val updatesFlow = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val updates: SharedFlow<Unit> = updatesFlow.asSharedFlow()

    fun publish(
        patientState: PatientStateSnapshot,
        patientModeDecision: PatientModeOrchestrator.Decision,
        updatedAtMs: Long = patientState.timestampMs,
        physioLive: PhysioLiveDigest = PhysioLiveDigest(),
        thermalBelief: ThermalBeliefDigest = ThermalBeliefDigest.EMPTY,
        physiologicalTree: PhysiologicalTreeSnapshot? = null,
        harmoniaDecision: HarmoniaDecision? = null,
        loopCache: PatientStateLoopCache? = null,
        refreshSource: PatientRefreshSource = PatientRefreshSource.LOOP_TICK,
    ) {
        publishRuntime(
            PatientRuntimeSnapshot(
                patientState = patientState,
                patientModeDecision = patientModeDecision,
                updatedAtMs = updatedAtMs,
                physioLive = physioLive,
                thermalBelief = thermalBelief,
                physiologicalTree = physiologicalTree,
                harmoniaDecision = harmoniaDecision,
                refreshSource = refreshSource,
            ),
            loopCache = loopCache,
        )
    }

    fun publishRuntime(
        runtimeSnapshot: PatientRuntimeSnapshot,
        loopCache: PatientStateLoopCache? = loopCacheRef.load(),
    ) {
        latestRef.store(runtimeSnapshot)
        if (loopCache != null) {
            loopCacheRef.store(loopCache)
        }
        updatesFlow.tryEmit(Unit)
    }

    fun getLatest(): PatientRuntimeSnapshot? = latestRef.load()

    fun getLoopCache(): PatientStateLoopCache? = loopCacheRef.load()

    fun clear() {
        latestRef.store(null)
        loopCacheRef.store(null)
        updatesFlow.tryEmit(Unit)
    }
}
