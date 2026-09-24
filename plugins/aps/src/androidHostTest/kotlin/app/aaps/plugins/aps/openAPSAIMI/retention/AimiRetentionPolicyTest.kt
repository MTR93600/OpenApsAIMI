package app.aaps.plugins.aps.openAPSAIMI.retention

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AimiRetentionPolicyTest {

    @Test
    fun everyFileAppearsOnce() {
        val names = AimiRetentionPolicy.RULES.map { it.fileName }

        assertThat(names).containsNoDuplicates()
    }

    @Test
    fun everyTimeTrimmedFileDeclaresItsTimestampKey() {
        val missing = AimiRetentionPolicy.RULES
            .filter { it.op == AimiRetentionOp.TRIM_TIME }
            .filter { it.timestampKey.isNullOrBlank() || it.hotDays <= 0 }

        assertThat(missing).isEmpty()
    }

    @Test
    fun everyLineTrimmedFileDeclaresAPositiveLineBudget() {
        val missing = AimiRetentionPolicy.RULES
            .filter { it.op == AimiRetentionOp.TRIM_LINES }
            .filter { it.hotLines <= 0 }

        assertThat(missing).isEmpty()
    }

    @Test
    fun theBlackboxUsesWallMsNotTimestamp() {
        val rule = AimiRetentionPolicy.ruleFor("AIMI_HORMONITOR_loop_blackbox_v1.jsonl")

        assertThat(rule).isNotNull()
        assertThat(rule!!.timestampKey).isEqualTo("wall_ms")
    }

    @Test
    fun theDecisionFileKeepsSevenDaysAndHasTheLargestHardCap() {
        val rule = AimiRetentionPolicy.ruleFor("AIMI_Decisions.jsonl")!!

        assertThat(rule.op).isEqualTo(AimiRetentionOp.TRIM_TIME)
        assertThat(rule.hotDays).isEqualTo(7)
        assertThat(rule.hardCapBytes).isEqualTo(1024L * 1024 * 1024)
        assertThat(AimiRetentionPolicy.RULES.map { it.hardCapBytes }.max()).isEqualTo(rule.hardCapBytes)
    }

    @Test
    fun thePkpdCsvHasNoHeaderButTheOtherCsvFilesDo() {
        assertThat(AimiRetentionPolicy.ruleFor("oapsaimi_pkpd_records.csv")!!.hasHeader).isFalse()
        assertThat(AimiRetentionPolicy.ruleFor("oapsaimi_wcycle.csv")!!.hasHeader).isTrue()
        assertThat(AimiRetentionPolicy.ruleFor("aimi_reactivity_analysis.csv")!!.hasHeader).isTrue()
        assertThat(AimiRetentionPolicy.ruleFor("comparison_aimi_smb.csv")!!.hasHeader).isTrue()
        assertThat(AimiRetentionPolicy.ruleFor("oapsaimi2_records.csv")!!.hasHeader).isTrue()
    }

    @Test
    fun theMlCorporaAreNotManagedHere() {
        assertThat(AimiRetentionPolicy.ruleFor("oapsaimiML2_records.csv")).isNull()
        assertThat(AimiRetentionPolicy.ruleFor("basal_adaptive_records.csv")).isNull()
        assertThat(AimiRetentionPolicy.ruleFor("autodrive_dataset.csv")).isNull()
    }

    @Test
    fun theBackupGlobMatchesOnlyGeneratedBackups() {
        val glob = AimiRetentionPolicy.DROP_GLOBS.single()

        assertThat(glob.matches("backup_20260919_120000.csv")).isTrue()
        assertThat(glob.matches("oapsaimiML2_records.csv")).isFalse()
    }
}
