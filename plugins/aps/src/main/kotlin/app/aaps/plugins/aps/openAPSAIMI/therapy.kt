package app.aaps.plugins.aps.openAPSAIMI

import android.annotation.SuppressLint
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import io.reactivex.rxjava3.core.Single
import java.util.Calendar
import java.util.concurrent.TimeUnit
class Therapy (private val persistenceLayer: PersistenceLayer){

    var sleepTime = false
    var sportTime = false
    var snackTime = false
    var lowCarbTime = false
    var highCarbTime = false
    var mealTime = false
    var bfastTime = false
    var lunchTime = false
    var dinnerTime = false
    var fastingTime = false
    var stopTime = false
    var calibartionTime = false

    @SuppressLint("CheckResult")
    fun updateStatesBasedOnTherapyEvents() {
        stopTime = findActivestopEvents(System.currentTimeMillis()).blockingGet()
        if (!stopTime) {
            sleepTime = findActiveSleepEvents(System.currentTimeMillis()).blockingGet()
            sportTime = findActiveSportEvents(System.currentTimeMillis()).blockingGet()
            snackTime = findActiveSnackEvents(System.currentTimeMillis()).blockingGet()
            lowCarbTime = findActiveLowCarbEvents(System.currentTimeMillis()).blockingGet()
            highCarbTime = findActiveHighCarbEvents(System.currentTimeMillis()).blockingGet()
            mealTime = findActiveMealEvents(System.currentTimeMillis()).blockingGet()
            bfastTime = findActivebfastEvents(System.currentTimeMillis()).blockingGet()
            lunchTime = findActiveLunchEvents(System.currentTimeMillis()).blockingGet()
            dinnerTime = findActiveDinnerEvents(System.currentTimeMillis()).blockingGet()
            fastingTime = findActiveFastingEvents(System.currentTimeMillis()).blockingGet()
            calibartionTime = isCalibrationEvent(System.currentTimeMillis()).blockingGet()
        } else {
            resetAllStates()
            clearActiveEvent("sleep")
            clearActiveEvent("sport")
            clearActiveEvent("snack")
            clearActiveEvent("lowcarb")
            clearActiveEvent("highcarb")
            clearActiveEvent("meal")
            clearActiveEvent("bfast")
            clearActiveEvent("lunch")
            clearActiveEvent("dinner")
            clearActiveEvent("fasting")
        }
    }
    private fun clearActiveEvent(noteKeyword: String) {
       persistenceLayer.deleteLastEventMatchingKeyword(noteKeyword)
    }

    // Implémenter la méthode


    private fun resetAllStates() {
        sleepTime = false;
        sportTime = false;
        snackTime = false;
        lowCarbTime = false;
        highCarbTime = false;
        mealTime = false;
        bfastTime = false;
        lunchTime = false;
        dinnerTime = false;
        fastingTime = false;
    }
    private fun findActiveSleepEvents(timestamp: Long): Single<Boolean> {
        val fromTime = timestamp - TimeUnit.DAYS.toMillis(1) // les dernières 24 heures
        // Utiliser la méthode getTherapyEventDataFromTime avec le timestamp et l'ordre de tri
        return persistenceLayer.getTherapyEventDataFromTime(fromTime, true)
            .map { events ->
                events.filter { it.type == TE.Type.NOTE } // Filtrer les événements par type
                    .any { event ->
                        event.note?.contains("sleep", ignoreCase = true) == true &&
                            System.currentTimeMillis() <= (event.timestamp + event.duration)
                    }
            }
    }

    private fun isCalibrationEvent(timestamp: Long): Single<Boolean> {
        val tenMinutesAgo = timestamp - TimeUnit.MINUTES.toMillis(15)
        return persistenceLayer.getTherapyEventDataFromTime(tenMinutesAgo, true)
            .map { events ->
                events.filter { it.type == TE.Type.FINGER_STICK_BG_VALUE }
                    .any { event ->
                        System.currentTimeMillis() <= (event.timestamp + event.duration)
                    }
            }
    }


    private fun findActiveSportEvents(timestamp: Long): Single<Boolean> {
        val fromTime = timestamp - TimeUnit.DAYS.toMillis(1) // les dernières 24 heures
        // Utiliser la méthode getTherapyEventDataFromTime avec le timestamp et l'ordre de tri
        return persistenceLayer.getTherapyEventDataFromTime(fromTime, true)
            .map { events ->
                events.filter { it.type == TE.Type.NOTE } // Filtrer les événements par type
                    .any { event ->
                        event.note?.contains("sport", ignoreCase = true) == true &&
                            System.currentTimeMillis() <= (event.timestamp + event.duration)
                    }
            }
    }

    private fun findActiveSnackEvents(timestamp: Long): Single<Boolean> {
        val fromTime = timestamp - TimeUnit.DAYS.toMillis(1) // les dernières 24 heures
        // Utiliser la méthode getTherapyEventDataFromTime avec le timestamp et l'ordre de tri
        return persistenceLayer.getTherapyEventDataFromTime(fromTime, true)
            .map { events ->
                events.filter { it.type == TE.Type.NOTE } // Filtrer les événements par type
                    .any { event ->
                        event.note?.contains("snack", ignoreCase = true) == true &&
                            System.currentTimeMillis() <= (event.timestamp + event.duration)
                    }
            }
    }

    private fun findActiveLowCarbEvents(timestamp: Long): Single<Boolean> {
        val fromTime = timestamp - TimeUnit.DAYS.toMillis(1) // les dernières 24 heures
        // Utiliser la méthode getTherapyEventDataFromTime avec le timestamp et l'ordre de tri
        return persistenceLayer.getTherapyEventDataFromTime(fromTime, true)
            .map { events ->
                events.filter { it.type == TE.Type.NOTE } // Filtrer les événements par type
                    .any { event ->
                        event.note?.contains("lowcarb", ignoreCase = true) == true &&
                            System.currentTimeMillis() <= (event.timestamp + event.duration)
                    }
            }
    }
    private fun findActiveHighCarbEvents(timestamp: Long): Single<Boolean> {
        val fromTime = timestamp - TimeUnit.DAYS.toMillis(1) // les dernières 24 heures
        return persistenceLayer.getTherapyEventDataFromTime(fromTime, true)
            .map { events ->
                events.filter { it.type == TE.Type.NOTE }
                    .any { event ->
                        event.note?.contains("highcarb", ignoreCase = true) == true &&
                            System.currentTimeMillis() <= (event.timestamp + event.duration)
                    }
            }
    }
    private fun findActiveMealEvents(timestamp: Long): Single<Boolean> {
        val fromTime = timestamp - TimeUnit.DAYS.toMillis(1) // les dernières 24 heures
        return persistenceLayer.getTherapyEventDataFromTime(fromTime, true)
            .map { events ->
                events.filter { it.type == TE.Type.NOTE }
                    .any { event ->
                        event.note?.contains("meal", ignoreCase = true) == true &&
                            System.currentTimeMillis() <= (event.timestamp + event.duration)
                    }
            }
    }
    private fun findActivebfastEvents(timestamp: Long): Single<Boolean> {
        val fromTime = timestamp - TimeUnit.DAYS.toMillis(1) // les dernières 24 heures
        return persistenceLayer.getTherapyEventDataFromTime(fromTime, true)
            .map { events ->
                events.filter { it.type == TE.Type.NOTE }
                    .any { event ->
                        event.note?.contains("bfast", ignoreCase = true) == true &&
                            System.currentTimeMillis() <= (event.timestamp + event.duration)
                    }
            }
    }
    private fun findActiveLunchEvents(timestamp: Long): Single<Boolean> {
        val fromTime = timestamp - TimeUnit.DAYS.toMillis(1) // les dernières 24 heures
        return persistenceLayer.getTherapyEventDataFromTime(fromTime, true)
            .map { events ->
                events.filter { it.type == TE.Type.NOTE }
                    .any { event ->
                        event.note?.contains("lunch", ignoreCase = true) == true &&
                            System.currentTimeMillis() <= (event.timestamp + event.duration)
                    }
            }
    }
    private fun findActiveDinnerEvents(timestamp: Long): Single<Boolean> {
        val fromTime = timestamp - TimeUnit.DAYS.toMillis(1) // les dernières 24 heures
        return persistenceLayer.getTherapyEventDataFromTime(fromTime, true)
            .map { events ->
                events.filter { it.type == TE.Type.NOTE }
                    .any { event ->
                        event.note?.contains("dinner", ignoreCase = true) == true &&
                            System.currentTimeMillis() <= (event.timestamp + event.duration)
                    }
            }
    }
    private fun findActiveFastingEvents(timestamp: Long): Single<Boolean> {
        val fromTime = timestamp - TimeUnit.DAYS.toMillis(1) // les dernières 24 heures
        return persistenceLayer.getTherapyEventDataFromTime(fromTime, true)
            .map { events ->
                events.filter { it.type == TE.Type.NOTE }
                    .any { event ->
                        event.note?.contains("fasting", ignoreCase = true) == true &&
                            System.currentTimeMillis() <= (event.timestamp + event.duration)
                    }
            }
    }
    private fun findActivestopEvents(timestamp: Long): Single<Boolean> {
        val fromTime = timestamp - TimeUnit.DAYS.toMillis(1) // les dernières 24 heures
        return persistenceLayer.getTherapyEventDataFromTime(fromTime, true)
            .map { events ->
                events.filter { it.type == TE.Type.NOTE }
                    .any { event ->
                        event.note?.contains("stop", ignoreCase = true) == true &&
                            System.currentTimeMillis() <= (event.timestamp + event.duration)
                    }
            }
    }

    fun getTimeElapsedSinceLastEvent(keyword: String): Long {
        val fromTime = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(60)
        val events = persistenceLayer.getTherapyEventDataFromTime(fromTime, TE.Type.NOTE, true)

        val lastEvent = events.filter { it.note?.contains(keyword, ignoreCase = true) == true }
            .maxByOrNull { it.timestamp }
        lastEvent?.let {
            // Calculer et retourner le temps écoulé en minutes depuis l'événement
            return (System.currentTimeMillis() - it.timestamp) / 60000  // Convertir en minutes
        }
        return -1  // Retourner -1 si aucun événement n'a été trouvé
    }

}