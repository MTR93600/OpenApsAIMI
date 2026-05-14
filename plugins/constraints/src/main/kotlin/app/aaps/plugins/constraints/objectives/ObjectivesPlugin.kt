package app.aaps.plugins.constraints.objectives

import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.constraints.objectives.objectives.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ObjectivesPlugin @Inject constructor(
    val preferences: Preferences,
    val rh: ResourceHelper,
    val dateUtil: DateUtil
) {
    val objectives = listOf(
        Objective0(preferences, rh, dateUtil),
        Objective1(preferences, rh, dateUtil),
        Objective2(preferences, rh, dateUtil),
        Objective3(preferences, rh, dateUtil),
        Objective4(preferences, rh, dateUtil),
        Objective5(preferences, rh, dateUtil),
        Objective6(preferences, rh, dateUtil),
        Objective7(preferences, rh, dateUtil),
        Objective8(preferences, rh, dateUtil),
        Objective9(preferences, rh, dateUtil)
    )

    // BYPASSED: Always returns true to allow starting any objective in any order
    fun allPriorAccomplished(position: Int): Boolean {
        return true
    }

    fun isObjectiveAccomplished(position: Int): Boolean {
        return objectives[position].isAccomplished
    }

    fun isObjectiveStarted(position: Int): Boolean {
        return objectives[position].isStarted
    }
}
