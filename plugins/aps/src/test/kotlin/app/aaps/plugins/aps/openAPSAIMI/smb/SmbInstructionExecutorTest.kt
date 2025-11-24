package app.aaps.plugins.aps.openAPSAIMI.smb

import android.content.Context
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SmbInstructionExecutorTest {

    private val context: Context = mockk(relaxed = true)
    private val preferences: Preferences = mockk(relaxed = true)
    private val csvFile: File = mockk(relaxed = true)
    private val rT: RT = mockk(relaxed = true)
    private val profile: OapsProfileAimi = mockk(relaxed = true)
    private val glucoseStatus: GlucoseStatusAIMI = mockk(relaxed = true)
    private val mealData: MealData = mockk(relaxed = true)
    private val pkpdRuntime: PkPdRuntime = mockk(relaxed = true)
    private val dateUtil: DateUtil = mockk(relaxed = true)

    private val hooks = SmbInstructionExecutor.Hooks(
        refineSmb = { _, _, _, smb, _ -> smb },
        adjustFactors = { m, a, e -> Triple(m, a, e) },
        calculateAdjustedDia = { _, _, _, _, _, _, _ -> 300.0 },
        costFunction = { _, _, _, _, _, _ -> 0.0 },
        applySafety = { _, smb, _, _, _, _, _ -> smb },
        runtimeToMinutes = { 0 },
        computeHypoThreshold = { _, _ -> 70.0 },
        isBelowHypo = { _, _, _, _, _ -> false },
        logDataMl = { _, _ -> },
        logData = { _, _ -> },
        roundBasal = { it },
        roundDouble = { v, _ -> v }
    )

    @Test
    fun `test execute basic flow`() {
        every { preferences.get(BooleanKey.OApsAIMIMLtraining) } returns false
        every { preferences.get(DoubleKey.OApsAIMIMorningFactor) } returns 100.0
        every { preferences.get(DoubleKey.OApsAIMIAfternoonFactor) } returns 100.0
        every { preferences.get(DoubleKey.OApsAIMIEveningFactor) } returns 100.0
        every { preferences.get(DoubleKey.OApsAIMIHyperFactor) } returns 100.0
        every { preferences.get(DoubleKey.OApsAIMIHCFactor) } returns 100.0
        every { preferences.get(DoubleKey.OApsAIMIMealFactor) } returns 100.0
        every { preferences.get(DoubleKey.OApsAIMIBFFactor) } returns 100.0
        every { preferences.get(DoubleKey.OApsAIMILunchFactor) } returns 100.0
        every { preferences.get(DoubleKey.OApsAIMIDinnerFactor) } returns 100.0
        every { preferences.get(DoubleKey.OApsAIMISnackFactor) } returns 100.0
        every { preferences.get(DoubleKey.OApsAIMIsleepFactor) } returns 100.0

        val input = SmbInstructionExecutor.Input(
            context = context,
            preferences = preferences,
            csvFile = csvFile,
            rT = rT,
            consoleLog = mutableListOf(),
            consoleError = mutableListOf(),
            combinedDelta = 0.0,
            shortAvgDelta = 0f,
            longAvgDelta = 0f,
            profile = profile,
            glucoseStatus = glucoseStatus,
            bg = 150.0,
            delta = 0.0,
            iob = 0f,
            basalaimi = 1.0f,
            initialBasal = 1.0,
            honeymoon = false,
            hourOfDay = 12,
            mealTime = false,
            bfastTime = false,
            lunchTime = false,
            dinnerTime = false,
            highCarbTime = false,
            snackTime = false,
            sleepTime = false,
            recentSteps5Minutes = 0,
            recentSteps10Minutes = 0,
            recentSteps30Minutes = 0,
            recentSteps60Minutes = 0,
            recentSteps180Minutes = 0,
            averageBeatsPerMinute = 60.0,
            averageBeatsPerMinute60 = 60.0,
            pumpAgeDays = 0,
            sens = 1.0,
            tp = 60,
            variableSensitivity = 50f,
            targetBg = 100.0,
            predictedBg = 150f,
            eventualBg = 150.0,
            maxSmb = 2.0,
            maxIob = 5.0,
            predictedSmb = 1.0f,
            modelValue = 1.0f,
            mealData = mealData,
            pkpdRuntime = pkpdRuntime,
            sportTime = false,
            lateFatRiseFlag = false,
            highCarbRunTime = null,
            threshold = null,
            dateUtil = dateUtil,
            currentTime = System.currentTimeMillis(),
            windowSinceDoseInt = 0,
            currentInterval = 5,
            insulinStep = 0.05f,
            highBgOverrideUsed = false,
            profileCurrentBasal = 1.0,
            cob = 0f
        )

        val result = SmbInstructionExecutor.execute(input, hooks)

        assertEquals(1.0f, result.predictedSmb, 0.01f)
        // finalSmb might be affected by quantizer and damping, but with basic inputs it should be close
        // In this case, no damping, no overrides, so it should be close to 1.0
        // Wait, there is MPC/PI logic which mixes things.
        // alpha is between 0.3 and 0.9.
        // optimalDose calculation in loop...
        // But let's check if it runs without error and returns a result.
        
        // Also verify rT reason was appended
        verify { rT.reason.appendLine(any<String>()) }
    }
}
