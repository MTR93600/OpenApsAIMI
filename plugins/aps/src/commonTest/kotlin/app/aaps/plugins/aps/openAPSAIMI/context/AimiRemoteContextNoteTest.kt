package app.aaps.plugins.aps.openAPSAIMI.context

import app.aaps.plugins.aps.openAPSAIMI.NoOpAapsLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The master side of AIMI remote control: an `AIMI_CONTEXT:` note from Nightscout is only applied
 * with the right PIN. Same rules as the dev_OAPSAIMI `NsIncomingDataProcessor`.
 */
class AimiRemoteContextNoteTest {

    private val json = """{"type":"Activity","act":"CARDIO","int":"HIGH","dur":3600000,"start":1700000000000,"conf":0.9}"""
    private val masterPin = "4242"

    /** What `ContextManager.injectContextFromNS` decides, without the rest of the manager. */
    private class RecordingInjector(private val configuredPin: String) {

        val applied = mutableListOf<Pair<String, ContextIntent>>()

        fun inject(intentId: String, intent: ContextIntent, pin: String?): Boolean {
            if (!isRemotePinAccepted(configuredPin, pin)) return false
            applied += intentId to intent
            return true
        }
    }

    private fun apply(note: String, injector: RecordingInjector): Boolean =
        applyAimiContextNote(note, NoOpAapsLogger, injector::inject)

    @Test
    fun noteWithPinIsSplitIntoItsParts() {
        val parsed = AimiContextNote.parse("AIMI_CONTEXT:CTX_1_2:PIN:4242:$json")
        assertEquals(AimiContextNote("CTX_1_2", "4242", json), parsed)
    }

    @Test
    fun legacyNoteHasNoPin() {
        val parsed = AimiContextNote.parse("AIMI_CONTEXT:CTX_1_2:$json")
        assertEquals(AimiContextNote("CTX_1_2", null, json), parsed)
    }

    @Test
    fun textWithoutBothPartsIsNotANote() {
        assertNull(AimiContextNote.parse("AIMI_CONTEXT:onlyid"))
        assertNull(AimiContextNote.parse("lunch 60"))
    }

    @Test
    fun rightPinIsApplied() {
        val injector = RecordingInjector(masterPin)
        assertTrue(apply("AIMI_CONTEXT:CTX_9:PIN:4242:$json", injector))
        assertEquals("CTX_9", injector.applied.single().first)
        val intent = assertIs<ContextIntent.Activity>(injector.applied.single().second)
        assertEquals(ContextIntent.Activity.ActivityType.CARDIO, intent.activityType)
        assertEquals(3_600_000L, intent.durationMs)
    }

    @Test
    fun pinWithSpacesStillMatches() {
        val injector = RecordingInjector(" 4242 ")
        assertTrue(apply("AIMI_CONTEXT:CTX_9:PIN: 4242 :$json", injector))
    }

    @Test
    fun wrongPinIsRejected() {
        val injector = RecordingInjector(masterPin)
        assertFalse(apply("AIMI_CONTEXT:CTX_9:PIN:1111:$json", injector))
        assertTrue(injector.applied.isEmpty())
    }

    @Test
    fun legacyNoteWithoutPinIsRejected() {
        val injector = RecordingInjector(masterPin)
        assertFalse(apply("AIMI_CONTEXT:CTX_9:$json", injector))
        assertTrue(injector.applied.isEmpty())
    }

    @Test
    fun nothingIsAcceptedWhenNoPinIsSetOnTheMaster() {
        val injector = RecordingInjector("")
        assertFalse(apply("AIMI_CONTEXT:CTX_9:PIN::$json", injector))
        assertFalse(apply("AIMI_CONTEXT:CTX_9:PIN:4242:$json", injector))
        assertTrue(injector.applied.isEmpty())
    }

    @Test
    fun brokenJsonIsIgnored() {
        val injector = RecordingInjector(masterPin)
        assertFalse(apply("AIMI_CONTEXT:CTX_9:PIN:4242:{not json", injector))
        assertTrue(injector.applied.isEmpty())
    }

    @Test
    fun otherNotesAreIgnored() {
        val injector = RecordingInjector(masterPin)
        assertFalse(apply("lunch 60", injector))
        assertFalse(apply("AIMI:4242 mode lunch 60", injector))
        assertTrue(injector.applied.isEmpty())
    }
}
