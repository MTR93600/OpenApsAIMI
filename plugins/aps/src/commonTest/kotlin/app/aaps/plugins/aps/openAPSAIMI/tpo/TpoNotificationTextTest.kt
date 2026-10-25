package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.resources.formatTemplate
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.TextRef.Companion.withArgs
import app.aaps.plugins.aps.ApsStringsValues
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The words of the two TPO notifications.
 *
 * These live in `commonTest` because the wording itself is shared: Android and iOS both show what
 * [TpoNotificationText] returns, so checking it once checks both phones. This test also runs under
 * `iosSimulatorArm64Test`, which is where the shared code really executes on Apple.
 *
 * The resolver below reads the generated English table, the same one iOS reads at run time, so the
 * assertions are the real sentences a user sees and not a stub.
 */
class TpoNotificationTextTest {

    @Test
    fun aStartedSessionNamesThePackTheMinutesLeftAndHowManyPreferencesMoved() {
        val notification = TpoNotificationText.sessionStarted(EnglishApsText, session(), NOW_MS)

        assertEquals("AIMI temporary protection active", notification?.title)
        assertEquals("Post-hypo recovery · 45 min · 6 prefs adjusted", notification?.text)
    }

    @Test
    fun theLongFormStartsWithTheShortOneSoACollapsedNotificationShowsTheSameFirstLine() {
        val notification = TpoNotificationText.sessionStarted(EnglishApsText, session(), NOW_MS)!!

        assertTrue(notification.longText.startsWith(notification.text))
    }

    @Test
    fun theLongFormAddsTheTierAndSaysHowManyChangesItDidNotList() {
        val notification = TpoNotificationText.sessionStarted(EnglishApsText, session(), NOW_MS)!!

        val lines = notification.longText.split('\n')
        assertEquals("Tier: MODERATE", lines[1])
        // Six keys changed, four are previewed, so the last line accounts for the other two.
        assertEquals("+2 more adjustments", lines.last())
    }

    @Test
    fun aSessionThatIsNoLongerRunningHasNothingToAnnounce() {
        val expired = session().copy(status = TpoSessionStatus.EXPIRED)

        assertNull(TpoNotificationText.sessionStarted(EnglishApsText, expired, NOW_MS))
    }

    @Test
    fun theMinutesLeftAreRoundedUpSoAHalfMinuteNeverReadsAsZero() {
        val almostOver = session().copy(expiresAtMs = NOW_MS + 30_000L)

        val notification = TpoNotificationText.sessionStarted(EnglishApsText, almostOver, NOW_MS)

        assertEquals("Post-hypo recovery · 1 min · 6 prefs adjusted", notification?.text)
    }

    @Test
    fun eachEndReasonHasItsOwnSentenceUnderOneTitle() {
        val expired = TpoNotificationText.sessionEnded(EnglishApsText, TpoEndReason.EXPIRED)
        val manual = TpoNotificationText.sessionEnded(EnglishApsText, TpoEndReason.MANUAL_REVERT)
        val superseded = TpoNotificationText.sessionEnded(EnglishApsText, TpoEndReason.SUPERSEDED)

        assertEquals("AIMI temporary protection ended", expired.title)
        assertEquals("45-minute session finished — your preferences were restored.", expired.text)
        assertEquals("Protection reverted — your saved preferences were restored.", manual.text)
        assertEquals("Previous protection replaced by a higher-priority session.", superseded.text)
    }

    @Test
    fun anEndNotificationHasNothingLongerToExpandInto() {
        val ended = TpoNotificationText.sessionEnded(EnglishApsText, TpoEndReason.EXPIRED)

        assertEquals(ended.text, ended.longText)
    }

    @Test
    fun theTwoNotificationsCarryTheKindThatDecidesHowLoudTheyAre() {
        val started = TpoNotificationText.sessionStarted(EnglishApsText, session(), NOW_MS)
        val ended = TpoNotificationText.sessionEnded(EnglishApsText, TpoEndReason.EXPIRED)

        assertEquals(TpoNotificationKind.SESSION_STARTED, started?.kind)
        assertEquals(TpoNotificationKind.SESSION_ENDED, ended.kind)
    }

    /** Six changed keys, so the preview is capped at four and two are counted instead. */
    private fun session(): TpoSessionDocument {
        val keys = listOf(
            DoubleKey.OApsAIMIMaxSMB,
            DoubleKey.OApsAIMIHighBGMaxSMB,
            DoubleKey.OApsAIMIPriorityMaxIobFactor,
            DoubleKey.OApsAIMIPriorityMaxIobExtraU,
            DoubleKey.OApsAIMISmbTailDamping,
            DoubleKey.OApsAIMILunchFactor,
        )
        val baseline: Map<String, Any> = keys.associate { it.key to 1.0 }
        val overlay: Map<String, Any> = keys.associate { it.key to 0.5 }
        return TpoSessionDocument(
            sessionId = "tpo-test",
            packId = TpoPackId.POST_HYPO_RECOVERY,
            tier = TuningStepTier.MODERATE,
            status = TpoSessionStatus.ACTIVE,
            startedAtMs = NOW_MS,
            expiresAtMs = NOW_MS + 45L * 60_000L,
            triggerAlgoConfidence = 0.8,
            triggerReasonCodes = listOf("post_hypo"),
            baseline = baseline,
            overlay = overlay,
        )
    }

    private companion object {

        const val NOW_MS = 1_700_000_000_000L
    }

    /**
     * Resolves a [TextRef] from this module's generated English table.
     *
     * Not a stub that answers "text": the point of the test is which words come out and in which
     * order, so it reads the same map `GeneratedTextResolver` reads on iOS. The formatting goes
     * through [formatTemplate] for the same reason - that is what substitutes `%1$s` off Android.
     */
    private object EnglishApsText : TextResolver {

        override fun gs(ref: TextRef): String = when (ref) {
            is TextRef.Literal    -> ref.text
            is TextRef.Named      -> {
                val template = ApsStringsValues.textOf(ref.name, null) ?: ref.name
                if (ref.args.isEmpty()) template else formatTemplate(template, ref.args)
            }

            is TextRef.AndroidRes -> "res:${ref.id}"
        }

        override fun gs(ref: TextRef, vararg args: Any?): String = gs(ref.withArgs(*args))

        override fun gsNotLocalised(ref: TextRef): String = gs(ref)
    }
}
