package app.aaps.plugins.aps.openAPSAIMI.physio

/**
 * The one thing that may start a [PhysioPipeline] run from the health context refresh.
 *
 * [HealthContextRepository] refreshes on every loop tick on both platforms, so it is the only
 * regular beat that shared code has. On iOS that beat is what runs the pipeline. On Android it must
 * not, because WorkManager already runs it: `AIMIPhysioManagerMTR` owns the schedule there.
 *
 * ### Why this is an interface and not a call
 *
 * The baseline history is keyed by the time of the run. Two owners feeding it would add the same
 * readings twice, which moves the mean and the spread that every person-relative reading is taken
 * against, and those readings reach the dose. One binding per platform makes "who feeds it" a
 * choice that has to be written down, instead of something that can be added by accident:
 *
 * - androidMain binds `AndroidPhysioPipelineFeed`, which does nothing.
 * - iosMain binds `IosPhysioPipelineFeed`, which runs the pipeline.
 *
 * A platform with no binding does not build, which is the right failure: it asks the question
 * rather than quietly picking an answer.
 *
 * Doing nothing is safe here, unlike most empty implementations in this code base, because the
 * Android half is not missing a capability - it has a better one. The run still happens, on
 * WorkManager's schedule.
 */
interface PhysioPipelineFeed {

    /**
     * Called on every health context refresh.
     *
     * Must return at once. An implementation that runs the pipeline starts it in the background and
     * never waits for it, because the caller is on the loop's path.
     */
    fun onHealthContextRefresh()
}
