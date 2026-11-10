package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Prérequis des branches `EXERCISE_LOCKOUT` / `T3C_EXERCISE_LOCKOUT` dans [DetermineBasalaimiSMB2] :
 * [Therapy] doit activer [Therapy.sportTime] pour une note « sport » encore dans la fenêtre de durée.
 */
class TherapySportDetectionTest {

    @Test
    fun `sport therapy note within duration sets sportTime`() {
        val now = System.currentTimeMillis()
        val persistenceLayer = mockk<PersistenceLayer>(relaxed = true)
        coEvery { persistenceLayer.getTherapyEventDataFromTime(any(), any()) } returns listOf(
            TE(
                timestamp = now - 60_000L,
                duration = 3_600_000L,
                type = TE.Type.NOTE,
                note = "Sport vélo",
                glucoseUnit = GlucoseUnit.MGDL
            )
        )
        val therapy = Therapy(persistenceLayer)
        therapy.updateStatesBasedOnTherapyEvents(forceRefresh = true)
        assertThat(therapy.sportTime).isTrue()
    }

    @Test
    fun `walking note does not set sportTime`() {
        val now = System.currentTimeMillis()
        val persistenceLayer = mockk<PersistenceLayer>(relaxed = true)
        coEvery { persistenceLayer.getTherapyEventDataFromTime(any(), any()) } returns listOf(
            TE(
                timestamp = now - 60_000L,
                duration = 3_600_000L,
                type = TE.Type.NOTE,
                note = "Marche promenade",
                glucoseUnit = GlucoseUnit.MGDL
            )
        )
        val therapy = Therapy(persistenceLayer)
        therapy.updateStatesBasedOnTherapyEvents(forceRefresh = true)
        assertThat(therapy.sportTime).isFalse()
    }
}
