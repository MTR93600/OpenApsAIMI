package app.aaps.plugins.aps.openAPSAIMI.advisor.tuning

import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.protection.ExportPasswordDataStore
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiKeyValueCache
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class TuningContextApplySupportTest : TestBase() {

    @Mock lateinit var importExportPrefs: ImportExportPrefs
    @Mock lateinit var exportPasswordDataStore: ExportPasswordDataStore
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var keyValueCache: AimiKeyValueCache

    @Test
    fun exportIsSkippedWhenThePasswordStoreIsDisabled() {
        whenever(exportPasswordDataStore.exportPasswordStoreEnabled()).thenReturn(false)

        val result = TuningContextApplySupport.tryExportSettings(importExportPrefs, exportPasswordDataStore)

        assertThat(result).isEqualTo(TuningExportStatus.SKIPPED_DISABLED)
        verifyNoInteractions(importExportPrefs)
    }

    @Test
    fun exportIsSkippedWhenThereIsNoPassword() {
        whenever(exportPasswordDataStore.exportPasswordStoreEnabled()).thenReturn(true)
        whenever(exportPasswordDataStore.getPasswordFromDataStore()).thenReturn(Triple("", false, false))

        val result = TuningContextApplySupport.tryExportSettings(importExportPrefs, exportPasswordDataStore)

        assertThat(result).isEqualTo(TuningExportStatus.SKIPPED_NO_PASSWORD)
        verifyNoInteractions(importExportPrefs)
    }

    @Test
    fun exportIsSkippedWhenThePasswordHasExpired() {
        whenever(exportPasswordDataStore.exportPasswordStoreEnabled()).thenReturn(true)
        whenever(exportPasswordDataStore.getPasswordFromDataStore()).thenReturn(Triple("secret", true, false))

        val result = TuningContextApplySupport.tryExportSettings(importExportPrefs, exportPasswordDataStore)

        assertThat(result).isEqualTo(TuningExportStatus.SKIPPED_PASSWORD_EXPIRED)
        verifyNoInteractions(importExportPrefs)
    }

    @Test
    fun exportReportsSuccess() {
        whenever(exportPasswordDataStore.exportPasswordStoreEnabled()).thenReturn(true)
        whenever(exportPasswordDataStore.getPasswordFromDataStore()).thenReturn(Triple("secret", false, false))
        whenever(importExportPrefs.exportSharedPreferencesNonInteractive("secret")).thenReturn(true)

        val result = TuningContextApplySupport.tryExportSettings(importExportPrefs, exportPasswordDataStore)

        assertThat(result).isEqualTo(TuningExportStatus.SUCCESS)
    }

    @Test
    fun exportReportsFailure() {
        whenever(exportPasswordDataStore.exportPasswordStoreEnabled()).thenReturn(true)
        whenever(exportPasswordDataStore.getPasswordFromDataStore()).thenReturn(Triple("secret", false, false))
        whenever(importExportPrefs.exportSharedPreferencesNonInteractive("secret")).thenReturn(false)

        val result = TuningContextApplySupport.tryExportSettings(importExportPrefs, exportPasswordDataStore)

        assertThat(result).isEqualTo(TuningExportStatus.FAILED)
    }

    @Test
    fun aBlockedPlanDoesNotChangePreferencesOrHistory() {
        val plan = TuningPlan(
            requestedContext = AimiTuningContext.MEAL_RISE,
            effectiveContext = AimiTuningContext.MEAL_RISE,
            dominantTier = TuningStepTier.MICRO,
            changes = emptyList(),
            blockedReason = "Not enough data",
        )
        val historyRepo = AdvisorHistoryRepository(keyValueCache)

        val result = TuningContextApplySupport.applyTuningPlan(plan, preferences, historyRepo)

        assertThat(result.appliedCount).isEqualTo(0)
        assertThat(result.exportStatus).isEqualTo(TuningExportStatus.SKIPPED_DISABLED)
        verifyNoInteractions(preferences, keyValueCache)
    }
}
