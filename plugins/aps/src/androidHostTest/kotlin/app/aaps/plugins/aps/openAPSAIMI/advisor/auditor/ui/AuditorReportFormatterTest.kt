package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui

import android.content.Context
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdictCache
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdict
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.BoundedAdjustments
import app.aaps.plugins.aps.openAPSAIMI.model.DecisionResult
import app.aaps.plugins.aps.openAPSAIMI.model.VerdictType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyVararg
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AuditorReportFormatterTest {

  private val context: Context = mock()

  @BeforeEach
  fun setUp() {
    AuditorVerdictCache.clear()
    whenever(context.getString(any<Int>())).thenAnswer { "s${it.arguments[0]}" }
    whenever(context.getString(any<Int>(), anyVararg())).thenAnswer { invocation ->
      invocation.arguments.drop(1).joinToString("|")
    }
  }

  @AfterEach
  fun tearDown() {
    AuditorVerdictCache.clear()
  }

  @Test
  fun formatVerdict_includesEvidenceAndAdjustments() {
    val verdict = AuditorVerdict(
      verdict = VerdictType.Soften,
      confidence = 0.82,
      degradedMode = true,
      riskFlags = listOf("hypo_risk"),
      evidence = listOf("rising delta", "IOB elevated"),
      boundedAdjustments = BoundedAdjustments(
        smbFactorClamp = 0.75,
        intervalAddMin = 3,
        preferTbr = true,
        tbrFactorClamp = 1.05,
      ),
      debugChecks = emptyList(),
    )

    val text = AuditorReportFormatter.formatVerdict(context, verdict, "OK_SOFTEN")

    assertTrue(text.contains("SOFTEN"))
    assertTrue(text.contains("82"))
    assertTrue(text.contains("OK_SOFTEN"))
    assertTrue(text.contains("rising delta"))
    assertTrue(text.contains("hypo_risk"))
    assertTrue(text.contains("0.75"))
    assertTrue(text.contains("3"))
  }

  @Test
  fun insightCount_isZeroWhenCacheEmpty() {
    assertEquals(0, AuditorReportFormatter.insightCount())
  }

  @Test
  fun displayableVerdict_isScopedToCurrentBg() {
    val verdict = AuditorVerdict(
      verdict = VerdictType.Confirm,
      confidence = 0.91,
      degradedMode = false,
      riskFlags = emptyList(),
      evidence = listOf("stable trajectory"),
      boundedAdjustments = BoundedAdjustments(
        smbFactorClamp = 1.0,
        intervalAddMin = 0,
        preferTbr = false,
        tbrFactorClamp = 1.0,
      ),
      debugChecks = emptyList(),
    )
    val result = DecisionResult.Applied(
      source = "test",
      reason = "ok",
    )

    AuditorVerdictCache.noteCurrentBg(1_000L)
    AuditorVerdictCache.update(verdict, result, 1_000L)

    assertNotNull(AuditorVerdictCache.getDisplayable(Long.MAX_VALUE))
    assertEquals(1, AuditorReportFormatter.insightCount(Long.MAX_VALUE))

    AuditorVerdictCache.noteCurrentBg(2_000L)

    assertNull(AuditorVerdictCache.getDisplayable(Long.MAX_VALUE))
    assertNotNull(AuditorVerdictCache.get(Long.MAX_VALUE))
    assertEquals(1, AuditorReportFormatter.insightCount(Long.MAX_VALUE))
    assertNotNull(AuditorVerdictCache.resolveForDisplay(Long.MAX_VALUE)?.cached)
  }
}
