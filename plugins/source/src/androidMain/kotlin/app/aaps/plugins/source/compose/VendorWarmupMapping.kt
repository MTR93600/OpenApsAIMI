package app.aaps.plugins.source.compose

import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState
import app.aaps.plugins.libre3.Libre3WarmupState

// Maps the two native driver warm-up states onto the shared CgmWarmupInfo. Both drivers are plain
// Android libraries, so they can only be named here. The mapping is one to one on every phase, so
// the screens see exactly what they saw before.

/** Dexcom ONE+ driver phase as the shared phase. */
fun OnePlusWarmupState.Phase.toCgmWarmupPhase(): CgmWarmupPhase = when (this) {
    OnePlusWarmupState.Phase.IDLE         -> CgmWarmupPhase.IDLE
    OnePlusWarmupState.Phase.PAIRING      -> CgmWarmupPhase.PAIRING
    OnePlusWarmupState.Phase.CONNECTING   -> CgmWarmupPhase.CONNECTING
    OnePlusWarmupState.Phase.RECONNECTING -> CgmWarmupPhase.RECONNECTING
    OnePlusWarmupState.Phase.WARMING      -> CgmWarmupPhase.WARMING
    OnePlusWarmupState.Phase.READY        -> CgmWarmupPhase.READY
    OnePlusWarmupState.Phase.FAILED       -> CgmWarmupPhase.FAILED
}

/** Dexcom ONE+ driver state as the three facts the countdown rules read. */
fun OnePlusWarmupState.toCgmWarmupInfo(): CgmWarmupInfo = CgmWarmupInfo(
    phase = phase.toCgmWarmupPhase(),
    remainingMs = remainingMs,
    endsAtEpochMs = endsAtEpochMs,
)

/** Libre 3 driver phase as the shared phase. */
fun Libre3WarmupState.Phase.toCgmWarmupPhase(): CgmWarmupPhase = when (this) {
    Libre3WarmupState.Phase.IDLE         -> CgmWarmupPhase.IDLE
    Libre3WarmupState.Phase.PAIRING      -> CgmWarmupPhase.PAIRING
    Libre3WarmupState.Phase.CONNECTING   -> CgmWarmupPhase.CONNECTING
    Libre3WarmupState.Phase.RECONNECTING -> CgmWarmupPhase.RECONNECTING
    Libre3WarmupState.Phase.WARMING      -> CgmWarmupPhase.WARMING
    Libre3WarmupState.Phase.READY        -> CgmWarmupPhase.READY
    Libre3WarmupState.Phase.FAILED       -> CgmWarmupPhase.FAILED
}

/** Libre 3 driver state as the three facts the countdown rules read. */
fun Libre3WarmupState.toCgmWarmupInfo(): CgmWarmupInfo = CgmWarmupInfo(
    phase = phase.toCgmWarmupPhase(),
    remainingMs = remainingMs,
    endsAtEpochMs = endsAtEpochMs,
)

/** Short form for the screens: a Dexcom ONE+ phase straight to what it means for the user. */
fun OnePlusWarmupState.Phase.toUiState(): CgmUiState = toCgmWarmupPhase().toUiState()

/** Short form for the screens: a Libre 3 phase straight to what it means for the user. */
fun Libre3WarmupState.Phase.toUiState(): CgmUiState = toCgmWarmupPhase().toUiState()
