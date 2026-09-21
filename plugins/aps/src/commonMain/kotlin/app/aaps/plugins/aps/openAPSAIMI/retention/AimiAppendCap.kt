package app.aaps.plugins.aps.openAPSAIMI.retention

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath

/**
 * Keeps an AIMI telemetry file under its hard cap, for a writer that lives in shared code.
 *
 * AIMI's telemetry files grow by a line every loop tick and are never truncated by their writers;
 * the decisions journal has been measured at 2.10 GB. Retention is what bounds them: a daily janitor
 * archives old lines, and between janitor runs this cap moves a file aside the moment it passes its
 * limit, so the next pass can archive it.
 *
 * It is a port rather than a direct call because one of the writers, `AuditorJsonlExport`, is shared
 * code and names files as [AimiPath], while the guard that does the work is built on `java.io.File`
 * and a non-blocking file lock. Without this seam that writer would have no way to reach the guard,
 * and the decisions journal would lose its cap.
 *
 * Call it just before appending. It never throws and never blocks: it runs on the loop thread, and a
 * busy or unreadable file makes it skip the check rather than stall a dosing tick.
 *
 * The guarantee is the guard's own, and it is weaker than it sounds: **one cap per file between
 * janitor runs, not an unlimited bound**. If the janitor stops running, a file is moved aside once and
 * then grows again.
 *
 * **There is deliberately no iOS implementation.** `AimiStorage` has none either, so on iOS these
 * files are never written at all: there is genuinely nothing to cap, which is a different thing from a
 * stub that quietly does nothing. Without a binding, any future iOS graph fails at wiring time.
 */
interface AimiAppendCap {

    /**
     * Call before appending [bytes] to [path]. When [path] is a managed telemetry file past its hard
     * cap, it is moved aside for the next retention pass to archive. A file that retention does not
     * manage is left alone.
     */
    fun beforeAppend(path: AimiPath, bytes: Int)
}
