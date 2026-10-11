package app.aaps.plugins.aps.openAPSAIMI.physio

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The Android half of [PhysioPipelineFeed]: it does nothing, on purpose.
 *
 * [AIMIPhysioManagerMTR] already runs [PhysioPipeline] from WorkManager, on its own schedule
 * (15 minutes, 30 minutes and 24 hours). Running it from the health context refresh as well would
 * feed the rolling baseline twice from the same readings, which moves the mean and the spread the
 * dose path reads. So this half stands aside and the schedule stays the single owner.
 *
 * Do not give this class a body. If Android ever needs a second trigger, take the first one away in
 * the same change.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidPhysioPipelineFeed @Inject constructor() : PhysioPipelineFeed {

    override fun onHealthContextRefresh() = Unit
}
