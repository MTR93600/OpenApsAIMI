package app.aaps.plugins.aps.openAPSAIMI.learning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * What the iOS training job asks the system for.
 *
 * This exists because of a real mistake rather than a theory. The first version of this scheduler
 * asked for a run every 6 hours and only while the phone was on a charger, and its KDoc said it
 * was copying Android. Android had already removed exactly that pair: its own comment records that
 * "charging and idle" almost never happened together on a real phone, so the worker never ran and
 * the model weights were never produced.
 *
 * Nothing reports a training run that did not happen, so this failure is silent on both platforms.
 * The point of pinning the request is that putting the condition back has to be a deliberate edit
 * to this file, with a reason.
 */
class IosMlTrainingScheduleRequestTest {

    @Test
    fun `training does not wait for a charger`() {
        assertFalse(
            IosMlTrainingScheduler.newTrainingRequest().requiresExternalPower,
            "asking for external power is what stopped Android from ever training"
        )
    }

    @Test
    fun `training does not wait for a network`() {
        assertFalse(
            IosMlTrainingScheduler.newTrainingRequest().requiresNetworkConnectivity,
            "training is fully on-device so a network condition would only delay it"
        )
    }

    @Test
    fun `the cadence is the hour Android asks for`() {
        assertEquals(60.0 * 60.0, IosMlTrainingScheduler.TRAIN_INTERVAL_SECONDS)
    }

    @Test
    fun `the task identifier matches the one declared in Info plist`() {
        // ios/app/Shared/Info.plist lists this under BGTaskSchedulerPermittedIdentifiers. A name
        // that does not match there means the system never launches the task at all.
        assertEquals("app.aaps.aimi.ml-training", IosMlTrainingScheduler.TASK_IDENTIFIER)
    }
}
