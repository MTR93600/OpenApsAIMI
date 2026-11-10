package app.aaps.core.ui.views

import android.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GlucoseRingColorComputerTest {

    private val green = Color.GREEN
    private val yellow = Color.YELLOW
    private val orange = 0xFFFF9800.toInt()
    private val red = Color.RED

    @Test
    fun `null bg returns gray`() {
        assertEquals(
            Color.GRAY,
            GlucoseRingColorComputer.compute(
                bgMgdl = null,
                hypoMaxFromProfile = 70f,
                severeHypoMaxMgdl = 54f,
                hypoMaxMgdlAttr = 70f,
                useSteppedColors = true,
                step1MaxMgdl = 100f,
                step2MaxMgdl = 160f,
                step3MaxMgdl = 220f,
                stepColor1 = green,
                stepColor2 = yellow,
                stepColor3 = orange,
                stepColor4 = red,
            )
        )
    }

    @Test
    fun `stepped severe hypo uses step4`() {
        assertEquals(
            red,
            GlucoseRingColorComputer.compute(
                bgMgdl = 50,
                hypoMaxFromProfile = 70f,
                severeHypoMaxMgdl = 54f,
                hypoMaxMgdlAttr = 70f,
                useSteppedColors = true,
                step1MaxMgdl = 100f,
                step2MaxMgdl = 160f,
                step3MaxMgdl = 220f,
                stepColor1 = green,
                stepColor2 = yellow,
                stepColor3 = orange,
                stepColor4 = red,
            )
        )
    }

    @Test
    fun `stepped mild hypo uses step3`() {
        assertEquals(
            orange,
            GlucoseRingColorComputer.compute(
                bgMgdl = 65,
                hypoMaxFromProfile = 70f,
                severeHypoMaxMgdl = 54f,
                hypoMaxMgdlAttr = 70f,
                useSteppedColors = true,
                step1MaxMgdl = 100f,
                step2MaxMgdl = 160f,
                step3MaxMgdl = 220f,
                stepColor1 = green,
                stepColor2 = yellow,
                stepColor3 = orange,
                stepColor4 = red,
            )
        )
    }

    @Test
    fun `stepped in range low side uses step1`() {
        assertEquals(
            green,
            GlucoseRingColorComputer.compute(
                bgMgdl = 90,
                hypoMaxFromProfile = 70f,
                severeHypoMaxMgdl = 54f,
                hypoMaxMgdlAttr = 70f,
                useSteppedColors = true,
                step1MaxMgdl = 100f,
                step2MaxMgdl = 160f,
                step3MaxMgdl = 220f,
                stepColor1 = green,
                stepColor2 = yellow,
                stepColor3 = orange,
                stepColor4 = red,
            )
        )
    }

    @Test
    fun `profile target low widens hypo band`() {
        assertEquals(
            orange,
            GlucoseRingColorComputer.compute(
                bgMgdl = 78,
                hypoMaxFromProfile = 80f,
                severeHypoMaxMgdl = 54f,
                hypoMaxMgdlAttr = 70f,
                useSteppedColors = true,
                step1MaxMgdl = 100f,
                step2MaxMgdl = 160f,
                step3MaxMgdl = 220f,
                stepColor1 = green,
                stepColor2 = yellow,
                stepColor3 = orange,
                stepColor4 = red,
            )
        )
    }
}
