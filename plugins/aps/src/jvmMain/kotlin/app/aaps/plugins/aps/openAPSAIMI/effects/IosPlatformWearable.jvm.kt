package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot

internal actual fun iosPlatformWearable(log: MutableList<String>): HealthContextSnapshot =
    iosNeutralEmptyWearable(log)
