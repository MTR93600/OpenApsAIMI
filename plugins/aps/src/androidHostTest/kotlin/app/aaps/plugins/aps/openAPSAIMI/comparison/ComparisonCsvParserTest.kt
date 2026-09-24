package app.aaps.plugins.aps.openAPSAIMI.comparison

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import app.aaps.plugins.aps.openAPSAIMI.utils.AndroidAimiStorage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import java.io.File

class ComparisonCsvParserTest {

    @Test
    fun testAnalyze(@TempDir dir: File) {
        // Create a temporary CSV file
        val tempFile = File(dir, "comparison_test.csv")
        tempFile.writeText("""
Timestamp,Date,BG,Delta,ShortAvgDelta,LongAvgDelta,IOB,COB,AIMI_Rate,AIMI_SMB,AIMI_Duration,AIMI_EventualBG,AIMI_TargetBG,SMB_Rate,SMB_SMB,SMB_Duration,SMB_EventualBG,SMB_TargetBG,Diff_Rate,Diff_SMB,Diff_EventualBG,MaxIOB,MaxBasal,MicroBolus_Allowed,AIMI_Insulin_30min,SMB_Insulin_30min,Cumul_Diff,AIMI_Active,SMB_Active,Both_Active,AIMI_UAM_Last,SMB_UAM_Last,Reason_AIMI,Reason_SMB
1630000000,2021-08-26 12:00:00,100,0,0,0,0,0,1.0,0,30,120,100,1.0,0,30,130,100,0,0,10,2,2,1,0.5,0.5,0,1,1,1,0,0,"Reason A","Reason B"
1630000300,2021-08-26 12:05:00,150,0,0,0,0,0,1.0,0,30,180,100,1.0,0,30,190,100,0,0,10,2,2,1,0.5,0.5,0,1,1,1,0,0,"Reason A","Reason B"
1630000600,2021-08-26 12:10:00,200,0,0,0,0,0,1.0,0,30,250,100,1.0,0,30,260,100,0,0,10,2,2,1,0.5,0.5,0,1,1,1,0,0,"Reason A","Reason B"
        """.trimIndent())

        val storage = AndroidAimiStorage(mock<AimiStorageHelper>())
        val parser = ComparisonCsvParser(storage)
        val entries = parser.parse(AimiPath(tempFile.absolutePath))
        val report = parser.analyze(entries)

        // Verify TIR (70-180)
        // BG: 100, 150, 200 -> 2/3 in range -> 66.66%
        assertEquals(66.66, report.tir.actualTir, 0.01)

        // AIMI Eventual BG: 120, 180, 250 -> 2/3 in range -> 66.66%
        assertEquals(66.66, report.tir.aimiPredictedTir, 0.01)

        // SMB Eventual BG: 130, 190, 260 -> 1/3 in range -> 33.33%
        assertEquals(33.33, report.tir.smbPredictedTir, 0.01)
    }
}
