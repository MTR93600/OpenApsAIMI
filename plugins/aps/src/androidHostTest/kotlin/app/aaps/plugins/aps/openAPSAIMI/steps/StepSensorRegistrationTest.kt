package app.aaps.plugins.aps.openAPSAIMI.steps

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StepSensorRegistrationTest {

    @Test
    fun `sensor present and permission granted registers`() {
        assertThat(planStepSensorRegistration(hasStepCounter = true, permissionGranted = true))
            .isEqualTo(StepSensorPlan.REGISTER)
    }

    @Test
    fun `sensor present and permission missing asks for permission`() {
        assertThat(planStepSensorRegistration(hasStepCounter = true, permissionGranted = false))
            .isEqualTo(StepSensorPlan.PERMISSION_MISSING)
    }

    @Test
    fun `no sensor and permission granted reports no step counter`() {
        assertThat(planStepSensorRegistration(hasStepCounter = false, permissionGranted = true))
            .isEqualTo(StepSensorPlan.NO_STEP_COUNTER)
    }

    @Test
    fun `no sensor wins over missing permission`() {
        assertThat(planStepSensorRegistration(hasStepCounter = false, permissionGranted = false))
            .isEqualTo(StepSensorPlan.NO_STEP_COUNTER)
    }

    @Test
    fun `every failure state says not registered in its text`() {
        listOf(StepSensorState.NO_STEP_COUNTER, StepSensorState.PERMISSION_MISSING, StepSensorState.REGISTRATION_REFUSED)
            .forEach { assertThat(it.description).startsWith("NOT registered") }
    }
}
