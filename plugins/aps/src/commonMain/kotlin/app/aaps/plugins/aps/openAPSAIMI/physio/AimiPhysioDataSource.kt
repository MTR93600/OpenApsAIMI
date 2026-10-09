package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalDataWindowMTR

/**
 * Platform source for physiological signals (sleep, HRV, resting heart rate, thermal).
 *
 * Extracted from `AIMIPhysioDataRepositoryMTR` (androidMain, Health Connect) so that
 * `HealthContextRepository` (commonMain) depends on this contract instead of the
 * Android-only implementation. The Android implementation is bound via
 * `@ContributesBinding`; on platforms without Health Connect the repository is
 * simply absent from the graph.
 */
interface AimiPhysioDataSource {

    suspend fun fetchSleepData(): SleepDataMTR?

    suspend fun fetchHRVData(daysBack: Int = 7): List<HRVDataMTR>

    suspend fun fetchMorningRHR(daysBack: Int = 7): List<RHRDataMTR>

    suspend fun fetchThermalWindow(daysBack: Int = 3): ThermalDataWindowMTR
}
