package app.aaps.wear.interaction.utils

object Constants {

    const val SECOND_IN_MS: Long = 1000
    const val MINUTE_IN_MS: Long = 60000
    const val HOUR_IN_MS: Long = 3600000
    const val DAY_IN_MS: Long = 86400000
    const val STALE_MS = MINUTE_IN_MS * 12
    const val CPP_MIN_PERCENTAGE = 30
    const val CPP_MAX_PERCENTAGE = 300
    const val CPP_MIN_TIMESHIFT = -23
    const val CPP_MAX_TIMESHIFT = 23
    const val MAX_PROFILE_SWITCH_DURATION = (7 * 24 * 60).toDouble()// [min] ~ 7 days
}