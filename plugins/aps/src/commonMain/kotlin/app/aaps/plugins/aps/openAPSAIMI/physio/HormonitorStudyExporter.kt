package app.aaps.plugins.aps.openAPSAIMI.physio

/**
 * Where AIMI writes study telemetry, as shared code sees it.
 *
 * **There is deliberately no implementation outside Android.** This is study data collection, not a
 * dosing path: the caller holds a `HormonitorStudyExporter?` and every call site is `?.`, so absent
 * telemetry is a designed, already-live state (see the `runCatching` in
 * `AndroidHormonitorStudyExporterProvider`, which came from the loop itself). Nothing reads a value
 * back from this interface and no branch tests it for anything but null.
 *
 * **Why the Android half is not being ported, for the record.** It would need six platform seams, and
 * two of them are a study participant identity scheme: the device identifier (`Settings.Secure`
 * ANDROID_ID) and the hash (`MessageDigest`) that keeps the exported dataset de-identified. Deciding
 * how a second platform identifies a study participant is a privacy decision that deserves its own
 * review, not a passing port. The other four seams (external storage, uptime clock, date formatting,
 * an atomic counter) would be routine; these two are not.
 *
 * Same posture as [app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage]: no binding rather than a stub
 * that quietly records nothing.
 */
interface HormonitorStudyExporter {

    /** Marks that a loop tick started. A [tickId] of zero means "no tick id known". */
    fun recordLoopPulse(wallClockMs: Long, tickId: Long = 0L)

    /** Phase transition inside the current tick (observe-only). */
    fun recordLoopPhase(
        tickId: Long,
        phaseName: String,
        wallClockMs: Long,
        msSinceTickStart: Long? = null,
        msSincePrevPhase: Long? = null
    )

    /** A tick that ended on a throwable. Pairs with [recordLoopPulse]. */
    fun recordLoopTickAborted(
        tickId: Long,
        startedWallMs: Long,
        endedWallMs: Long,
        errorClass: String,
        errorMessage: String,
        lastPhaseName: String?
    )

    /** Emitted when a determine_basal pass completes; pairs with [recordLoopPulse]. */
    fun recordLoopTickEnd(
        tickId: Long,
        startedWallMs: Long,
        endedWallMs: Long,
        lastPhaseName: String? = null
    )

    /** One row in the event stream. */
    fun export(event: HormonitorDecisionEventMTR)

    /** One row in the shadow contribution stream. */
    fun exportShadowContributions(event: HormonitorDecisionEventMTR)

    /** Folds [event] into the day's counters and emits the daily row when the interval has passed. */
    fun exportDailyOutcomes(
        event: HormonitorDecisionEventMTR,
        tirLowPct: Double?,
        tirInRangePct: Double?,
        tirAbovePct: Double?,
        tdd24hTotalU: Double?,
        snapshotSource: String?,
        snapshotAgeSeconds: Long?,
        snapshotConfidence: Double?
    )
}

/**
 * Hands shared code the study exporter, or `null` when this platform has none.
 *
 * A provider rather than the exporter itself because building the Android exporter needs a platform
 * context and can fail, and neither of those may reach the loop. `null` is a normal answer: on a
 * platform with no implementation, and on Android when the exporter could not be built.
 */
interface HormonitorStudyExporterProvider {

    /** The exporter for this session, or `null` when telemetry is unavailable. */
    fun exporter(): HormonitorStudyExporter?
}
