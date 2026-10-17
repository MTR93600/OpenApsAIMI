package app.aaps.plugins.aps.openAPSAIMI.steps

/**
 * What to do with the phone step counter sensor. Pure logic, so a unit test can drive it.
 */
enum class StepSensorPlan {
    /** Sensor exists and the permission is granted: try to register the listener. */
    REGISTER,

    /** The phone has no `TYPE_STEP_COUNTER` sensor. Nothing can be done. */
    NO_STEP_COUNTER,

    /** The `ACTIVITY_RECOGNITION` permission is not granted. Ask the user. */
    PERMISSION_MISSING
}

/**
 * The real state of the phone step source. Used for the status log, so it never claims more than is true.
 */
enum class StepSensorState(val description: String) {
    NOT_STARTED("not started"),
    REGISTERED("registered, waiting for sensor events"),
    NO_STEP_COUNTER("NOT registered: this phone has no step counter sensor"),
    PERMISSION_MISSING("NOT registered: ACTIVITY_RECOGNITION permission is not granted"),
    REGISTRATION_REFUSED("NOT registered: the system refused the sensor registration")
}

/**
 * Decides what to do. The missing sensor is checked first: asking for a permission is pointless
 * on a phone that has no sensor.
 */
fun planStepSensorRegistration(hasStepCounter: Boolean, permissionGranted: Boolean): StepSensorPlan = when {
    !hasStepCounter    -> StepSensorPlan.NO_STEP_COUNTER
    !permissionGranted -> StepSensorPlan.PERMISSION_MISSING
    else               -> StepSensorPlan.REGISTER
}
