package app.aaps.pump.omnipod.dashctl.pod.definition

sealed class AlertTrigger {
    class TimerTrigger(val offsetInMinutes: Short) : AlertTrigger()
    class ReservoirVolumeTrigger(val thresholdInMicroLiters: Short) : AlertTrigger()
}
