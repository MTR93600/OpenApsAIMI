package app.aaps.plugins.aps.openAPSAIMI.steps

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Instant

/**
 * Health Connect is Android-only. This stub always reports unavailable so
 * [AIMICompositeStepsProviderMTR] falls through to the phone/database providers.
 *
 * Lives in appleJvmMain so it covers both jvmMain and iosMain (both depend on it).
 */
@SingleIn(AppScope::class)
actual class AIMIHealthConnectStepsProviderMTR @Inject constructor() : AIMIStepsProviderMTR {

    override fun getStepsDelta(windowMinutes: Int, now: Instant): Int = 0

    override fun getLastUpdateMillis(): Long = 0L

    override fun isAvailable(): Boolean = false

    override fun sourceName(): String = "HealthConnect"

    override fun priority(): Int = 3
}
