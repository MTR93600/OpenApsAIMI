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

    /**
     * Sleep, HRV, resting heart rate and steps in one read.
     *
     * This is what [PhysioPipeline] fetches once per run. An implementation never throws for a
     * missing or refused reading: it answers with whatever it did get, and with
     * [RawPhysioDataMTR.EMPTY] when the whole read failed. A reading that is absent stays absent -
     * no implementation may put a stand-in number in its place.
     */
    suspend fun fetchAllData(daysBack: Int = 7): RawPhysioDataMTR

    /**
     * Whether the platform health store can be read on this device at all.
     *
     * It is a report, not a gate: the pipeline logs a warning and carries on, because the phone
     * database can still feed it. The call must not ask the person for anything.
     */
    suspend fun isAvailable(): Boolean

    /** Drops every cached answer, so the next fetch reads the platform health store again. */
    fun clearCache()
}
