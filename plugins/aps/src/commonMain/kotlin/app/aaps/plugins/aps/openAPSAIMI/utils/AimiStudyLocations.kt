package app.aaps.plugins.aps.openAPSAIMI.utils

/**
 * Where AIMI's study files live, most preferred first.
 *
 * [AimiStorage] answers "open this file"; this answers "which folders may hold it". The two are not
 * the same question. `AimiStorage` resolves **one** directory with a write policy behind it - on
 * Android it walks three tiers and keeps the first it can write to - and that is right for a learner
 * that must put a row somewhere. A reader of the study export has the opposite problem: the files it
 * wants were written by an older build, or by a build whose write policy picked a different tier, so
 * it has to look in more than one place and take the first copy it finds.
 *
 * This is a separate interface rather than a method on [AimiStorage] on purpose. `AimiStorage` is
 * deliberately unimplemented on iOS, because a silent no-op write would leave AIMI's learning loops
 * looking alive while they persisted nothing. Reading a location has no such hazard and does have an
 * honest iOS answer today, so it lives apart and is implemented on both platforms.
 *
 * ## The order is behaviour, not a detail
 *
 * Callers take the **first** directory that holds the file they want. Existing Android installs have
 * study files in the shared `Documents/AAPS` folder, so that entry stays first and stays exactly as
 * it was built before this interface existed. Reordering it, or adding a new entry before it, would
 * make the app read a stale or empty copy on a device that has real data.
 *
 * ## What the list promises, and what it does not
 *
 * - Entries are in order of preference and may be empty.
 * - A directory in the list **may not exist**, and may exist but not be readable. The Android list is
 *   built without touching the file system on purpose: a folder that is currently unreadable still
 *   has to be named, because the permission can come back. Callers check with
 *   [AimiStorage.exists] / [AimiStorage.canRead] and move on to the next entry.
 * - An implementation may create its own directory when doing so costs nothing and the user cannot
 *   see it - that is true of an app's private container, and is not true of shared storage.
 */
interface AimiStudyLocations {

    /** The candidate directories, most preferred first. Never `null`, possibly empty. */
    fun studyDirectories(): List<AimiPath>
}
