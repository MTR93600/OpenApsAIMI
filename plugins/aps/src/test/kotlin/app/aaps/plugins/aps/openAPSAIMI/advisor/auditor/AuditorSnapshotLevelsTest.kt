package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the auditor is really sent for ISF and target, and what its prompt says about it.
 *
 * Without the levels the snapshot and the prompt must stay exactly as they were, because the
 * verdict can change doses. With the levels the three old fields are replaced by the correct ones.
 */
class AuditorSnapshotLevelsTest {

    private val levels = SnapshotIsfTargetLevels(
        isfProfileStatic = 60.0,
        isfDynamic = 36.649,
        isfCommand = 60.0,
        isfCommandOverProfile = 1.0,
        isfOnProfileFloor = true,
        targetProfile = 100.0,
        targetWorking = 100.0,
    )

    @Test
    fun `without levels the snapshot keeps the three old fields`() {
        val json = dummyInput(levels = null).snapshot.toJSON()
        assertTrue(json.has("isfProfile"))
        assertTrue(json.has("isfUsed"))
        assertTrue(json.has("target"))
        assertTrue(json.has("ic"))
        assertFalse(json.has("isfProfileStatic"))
        assertFalse(json.has("isfDynamic"))
        assertFalse(json.has("isfCommand"))
        assertFalse(json.has("isfCommandOverProfile"))
        assertFalse(json.has("isfOnProfileFloor"))
        assertFalse(json.has("targetProfile"))
        assertFalse(json.has("targetWorking"))
    }

    @Test
    fun `with levels the snapshot drops the three old fields`() {
        val json = dummyInput(levels = levels).snapshot.toJSON()
        assertFalse(json.has("isfProfile"))
        assertFalse(json.has("isfUsed"))
        assertFalse(json.has("target"))

        assertEquals(60.0, json.getDouble("isfProfileStatic"), 1e-9)
        assertEquals(36.649, json.getDouble("isfDynamic"), 1e-9)
        assertEquals(60.0, json.getDouble("isfCommand"), 1e-9)
        assertEquals(1.0, json.getDouble("isfCommandOverProfile"), 1e-9)
        assertTrue(json.getBoolean("isfOnProfileFloor"))
        assertEquals(100.0, json.getDouble("targetProfile"), 1e-9)
        assertEquals(100.0, json.getDouble("targetWorking"), 1e-9)
        assertTrue(json.has("ic"))
    }

    @Test
    fun `an unknown profile isf is sent as json null, not dropped and not zero`() {
        val json = dummyInput(levels = levels.copy(isfProfileStatic = null, isfCommandOverProfile = null))
            .snapshot.toJSON()
        assertTrue(json.has("isfProfileStatic"))
        assertTrue(json.isNull("isfProfileStatic"))
        assertTrue(json.has("isfCommandOverProfile"))
        assertTrue(json.isNull("isfCommandOverProfile"))
        assertTrue(json.has("isfCommand"))
    }

    @Test
    fun `without levels the prompt has no level section`() {
        val prompt = AuditorPromptBuilder.buildPrompt(dummyInput(levels = null))
        assertFalse(prompt.contains("ISF AND TARGET LEVELS"))
        assertFalse(prompt.contains("isfCommandOverProfile"))
        assertFalse(prompt.contains("targetWorking"))
        assertTrue(prompt.contains("\"isfProfile\""))
    }

    @Test
    fun `with levels the prompt explains the levels and keeps the no profile change rule`() {
        val prompt = AuditorPromptBuilder.buildPrompt(dummyInput(levels = levels))
        assertTrue(prompt.contains("ISF AND TARGET LEVELS"))
        assertTrue(prompt.contains("isfProfileStatic"))
        assertTrue(prompt.contains("isfDynamic"))
        assertTrue(prompt.contains("isfCommand"))
        assertTrue(prompt.contains("isfOnProfileFloor"))
        assertTrue(prompt.contains("targetProfile"))
        assertTrue(prompt.contains("targetWorking"))
        // The verdict still may not ask for a profile change: no factor is applied yet.
        assertTrue(prompt.contains("Modification profil"))
        assertTrue(prompt.contains("must not propose any profile change"))
    }

    @Test
    fun `the level section is the only thing the levels add to the prompt`() {
        val withoutLevels = AuditorPromptBuilder.buildPrompt(dummyInput(levels = null))
        val withLevels = AuditorPromptBuilder.buildPrompt(dummyInput(levels = levels))
        // Everything before the input JSON must be the same text, so nothing else moved.
        val head = "# INPUT DATA"
        assertEquals(
            withoutLevels.substringBefore(head),
            withLevels.substringBefore(head).substringBefore("## ISF AND TARGET LEVELS"),
        )
    }

    private fun dummyInput(levels: SnapshotIsfTargetLevels?): AuditorInput = AuditorInput(
        snapshot = Snapshot(
            bg = 173.6,
            delta = 1.71,
            shortAvgDelta = 1.0,
            longAvgDelta = 1.0,
            unit = "mg/dl",
            timestamp = 1790021540129L,
            cgmAgeMin = 2,
            noise = "Clean",
            iob = 10.374,
            iobActivity = 0.1,
            cob = 0.0,
            isfProfile = 60.0,
            isfUsed = 36.649,
            ic = 10.0,
            target = 100.0,
            pkpd = PKPDSnapshot(300, 75, 0.2, true, 0.0),
            activity = ActivitySnapshot(0, 0, null, null),
            physio = null,
            states = StatesSnapshot("Normal", 10, "Idle", null, 1.0),
            limits = LimitsSnapshot(2.0, 3.0, 5.0, 2.0, null, null),
            decisionAimi = DecisionSnapshot(0.0, null, null, 5.0, emptyList()),
            lastDelivery = LastDeliverySnapshot(null, null, null, null, null, null),
            levels = levels,
        ),
        history = History(
            emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList()
        ),
        stats = Stats7d(80.0, 1.0, 19.0, 130.0, 30.0, 40.0, 50.0, 50.0),
        trajectory = null,
    )
}
