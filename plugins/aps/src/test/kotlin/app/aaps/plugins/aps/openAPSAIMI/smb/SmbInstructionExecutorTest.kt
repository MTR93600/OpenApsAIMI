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
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SmbInstructionExecutorTest {

    private val context = mockk<Context>(relaxed = true)
    private val preferences = mockk<Preferences>(relaxed = true)
    private val rT = mockk<RT>(relaxed = true)
    private val glucoseStatus = mockk<GlucoseStatusAIMI>(relaxed = true)
    private val profile = mockk<OapsProfileAimi>(relaxed = true)
    private val dateUtil = mockk<DateUtil>(relaxed = true)

    @Test
    fun `execute - should apply ML refinement when training is enabled`() {
        // Arrange
        every { preferences.get(BooleanKey.OApsAIMIMLtraining) } returns true
        every { preferences.get(DoubleKey.ApsSmbMaxIob) } returns 5.0
        every { profile.dia } returns 5.0
        every { profile.sens } returns 45.0
        
        val input = SmbInstructionExecutor.Input(
            context = context,
            preferences = preferences,
            csvFile = File("fake.csv"),
            rT = rT,
            consoleLog = mutableListOf(),
            consoleError = mutableListOf(),
            combinedDelta = 2.0,
            shortAvgDelta = 2.0f,
            longAvgDelta = 1.0f,
            profile = profile,
            glucoseStatus = glucoseStatus,
            bg = 150.0,
            delta = 2.0,
            iob = 1.0f,
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
            averageBeatsPerMinute = 80.0,
            averageBeatsPerMinute60 = 80.0,
            pumpAgeDays = 1,
            sens = 45.0,
            tp = 75,
            variableSensitivity = 45.0f,
            targetBg = 100.0,
            predictedBg = 110.0f,
            eventualBg = 120.0,
            maxSmb = 2.0,
            maxIob = 5.0,
            predictedSmb = 0.5f,
            modelValue = 0.5f,
            mealData = mockk<MealData>(relaxed = true),
            pkpdRuntime = mockk<PkPdRuntime>(relaxed = true),
            sportTime = false,
            lateFatRiseFlag = false,
            highCarbRunTime = null,
            threshold = 70.0,
            dateUtil = dateUtil,
            currentTime = System.currentTimeMillis(),
            windowSinceDoseInt = 5,
            currentInterval = null,
            insulinStep = 0.05f,
            highBgOverrideUsed = false,
            profileCurrentBasal = 1.0,
            cob = 0.0f,
            globalReactivityFactor = 1.0
        )

        val hooks = SmbInstructionExecutor.Hooks(
            refineSmb = { _, _, _, pred, _ -> pred * 1.2f }, // Mock ML refinement: +20%
            calculateAdjustedDia = { dia, _, _, _, _, _, _ -> dia.toDouble() },
            costFunction = { _, _, _, _, _, _ -> 0.0 },
            applySafety = { _, smb, _, _, _, _, _, _ -> smb },
            runtimeToMinutes = { 0 },
            computeHypoThreshold = { _, _ -> 70.0 },
            isBelowHypo = { _, _, _, _, _ -> false },
            logDataMl = { _, _ -> },
            logData = { _, _ -> },
            roundBasal = { it },
            roundDouble = { v, _ -> v }
        )

        // Act
        val result = SmbInstructionExecutor.execute(input, hooks)

        // Assert
        // predictedSmb 0.5 * 1.2 = 0.6
        // reactive factor 1.0 -> 0.6
        assertEquals(0.6f, result.finalSmb, 0.05f)
    }
}
