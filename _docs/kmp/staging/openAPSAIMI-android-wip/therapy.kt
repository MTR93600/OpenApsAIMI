package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import java.util.Calendar
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class Therapy(private val persistenceLayer: PersistenceLayer) {

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
    var calibrationTime = false
    var deleteEventDate: String? = null
    var deleteTime = false
    private var latestNoteEvents: List<TE> = emptyList()

    fun updateStatesBasedOnTherapyEvents(forceRefresh: Boolean = false) {
        if (forceRefresh) {
            refreshBlocking()
            return
        }
        snapshotRef.get()?.let { applySnapshot(it) }
        refreshIfNeededAsync()
    }

    private fun refreshBlocking() {
        runCatching {
            runBlocking(Dispatchers.IO) { buildSnapshot() }
        }.onSuccess { snapshot ->
            snapshotRef.set(snapshot)
            applySnapshot(snapshot)
        }.onFailure {
            snapshotRef.get()?.let { applySnapshot(it) } ?: resetAllStates()
        }
    }

    private fun refreshIfNeededAsync() {
        val now = System.currentTimeMillis()
        val current = snapshotRef.get()
        if (current != null && now - current.generatedAtMs < SNAPSHOT_TTL_MS) return
        if (!refreshInFlight.compareAndSet(false, true)) return

        ioScope.launch {
            try {
                val snapshot = buildSnapshot()
                snapshotRef.set(snapshot)
                applySnapshot(snapshot)
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    private suspend fun buildSnapshot(): TherapySnapshot {
        val now = System.currentTimeMillis()
        val fromTime = now - TimeUnit.DAYS.toMillis(1)
        val events = persistenceLayer.getTherapyEventDataFromTime(fromTime, ascending = true)
        val noteEvents = events.filter { it.type == TE.Type.NOTE }
        val stop = findActivestopEvents(events, now)
        if (!stop) {
            val deleteNote = noteEvents.find { it.note?.contains("delete", ignoreCase = true) == true }?.note
            return TherapySnapshot(
                sleepTime = findActiveSleepEvents(events, now),
                sportTime = findActiveSportEvents(events, now),
                snackTime = findActiveSnackEvents(events, now),
                lowCarbTime = findActiveLowCarbEvents(events, now),
                highCarbTime = findActiveHighCarbEvents(events, now),
                mealTime = findActiveMealEvents(events, now),
                bfastTime = findActivebfastEvents(events, now),
                lunchTime = findActiveLunchEvents(events, now),
                dinnerTime = findActiveDinnerEvents(events, now),
                fastingTime = findActiveFastingEvents(events, now),
                stopTime = false,
                calibrationTime = isCalibrationEvent(events, now),
                deleteTime = findActivedeleteEvents(events, now),
                deleteEventDate = extractDateFromDeleteEvent(deleteNote),
                latestNoteEvents = noteEvents,
                generatedAtMs = now
            )
        }

        clearActiveEventsOnStop()
        return TherapySnapshot(
            sleepTime = false,
            sportTime = false,
            snackTime = false,
            lowCarbTime = false,
            highCarbTime = false,
            mealTime = false,
            bfastTime = false,
            lunchTime = false,
            dinnerTime = false,
            fastingTime = false,
            stopTime = true,
            calibrationTime = false,
            deleteTime = false,
            deleteEventDate = null,
            latestNoteEvents = noteEvents,
            generatedAtMs = now
        )
    }

    private suspend fun clearActiveEventsOnStop() {
        persistenceLayer.deleteLastEventMatchingKeyword("sleep")
        persistenceLayer.deleteLastEventMatchingKeyword("sport")
        persistenceLayer.deleteLastEventMatchingKeyword("snack")
        persistenceLayer.deleteLastEventMatchingKeyword("lowcarb")
        persistenceLayer.deleteLastEventMatchingKeyword("highcarb")
        persistenceLayer.deleteLastEventMatchingKeyword("meal")
        persistenceLayer.deleteLastEventMatchingKeyword("bfast")
        persistenceLayer.deleteLastEventMatchingKeyword("lunch")
        persistenceLayer.deleteLastEventMatchingKeyword("dinner")
        persistenceLayer.deleteLastEventMatchingKeyword("fasting")
        persistenceLayer.deleteLastEventMatchingKeyword("delete")
    }

    private fun applySnapshot(snapshot: TherapySnapshot) {
        sleepTime = snapshot.sleepTime
        sportTime = snapshot.sportTime
        snackTime = snapshot.snackTime
        lowCarbTime = snapshot.lowCarbTime
        highCarbTime = snapshot.highCarbTime
        mealTime = snapshot.mealTime
        bfastTime = snapshot.bfastTime
        lunchTime = snapshot.lunchTime
        dinnerTime = snapshot.dinnerTime
        fastingTime = snapshot.fastingTime
        stopTime = snapshot.stopTime
        calibrationTime = snapshot.calibrationTime
        deleteTime = snapshot.deleteTime
        deleteEventDate = snapshot.deleteEventDate
        latestNoteEvents = snapshot.latestNoteEvents
    }

    private fun resetAllStates() {
        sleepTime = false
        sportTime = false
        snackTime = false
        lowCarbTime = false
        highCarbTime = false
        mealTime = false
        bfastTime = false
        lunchTime = false
        dinnerTime = false
        fastingTime = false
        deleteTime = false
    }

    private fun findActiveSleepEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                event.note?.contains("sleep", ignoreCase = true) == true &&
                    now <= (event.timestamp + event.duration)
            }

    private fun isCalibrationEvent(events: List<TE>, now: Long): Boolean {
        val cutoff = now - TimeUnit.MINUTES.toMillis(15)
        return events.filter { it.type == TE.Type.FINGER_STICK_BG_VALUE && it.timestamp >= cutoff }
            .any { event -> now <= (event.timestamp + event.duration) }
    }

    private fun extractDateFromDeleteEvent(note: String?): String? {
        val deletePattern = Pattern.compile("delete (\\d{2}/\\d{2}/\\d{4})", Pattern.CASE_INSENSITIVE)
        val matcher = deletePattern.matcher(note ?: "")
        return if (matcher.find()) {
            matcher.group(1)
        } else {
            null
        }
    }

    private fun findActiveSportEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                val note = event.note?.lowercase() ?: ""
                val containsSport = note.contains("sport", ignoreCase = true)
                val isWalking = note.contains("marche", ignoreCase = true) || note.contains("walk", ignoreCase = true)
                (containsSport && !isWalking) &&
                    now <= (event.timestamp + event.duration)
            }

    private fun findActiveSnackEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                event.note?.contains("snack", ignoreCase = true) == true &&
                    now <= (event.timestamp + event.duration)
            }

    private fun findActiveLowCarbEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                event.note?.contains("lowcarb", ignoreCase = true) == true &&
                    now <= (event.timestamp + event.duration)
            }

    private fun findActiveHighCarbEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                val note = event.note ?: ""
                (note.contains("highcarb", ignoreCase = true) || note.contains("high carb", ignoreCase = true)) &&
                    now <= (event.timestamp + event.duration)
            }

    private fun findActiveMealEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                event.note?.contains("meal", ignoreCase = true) == true &&
                    now <= (event.timestamp + event.duration)
            }

    private fun findActivebfastEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                val note = event.note ?: ""
                (note.contains("bfast", ignoreCase = true) || note.contains("breakfast", ignoreCase = true)) &&
                    now <= (event.timestamp + event.duration)
            }

    private fun findActiveLunchEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                event.note?.contains("lunch", ignoreCase = true) == true &&
                    now <= (event.timestamp + event.duration)
            }

    private fun findActivedeleteEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                event.note?.contains("delete", ignoreCase = true) == true &&
                    now <= (event.timestamp + event.duration)
            }

    private fun findActiveDinnerEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                event.note?.contains("dinner", ignoreCase = true) == true &&
                    now <= (event.timestamp + event.duration)
            }

    private fun findActiveFastingEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                event.note?.contains("fasting", ignoreCase = true) == true &&
                    now <= (event.timestamp + event.duration)
            }

    private fun findActivestopEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                event.note?.contains("stop", ignoreCase = true) == true &&
                    now <= (event.timestamp + event.duration)
            }

    fun getTimeElapsedSinceLastEvent(keyword: String): Long {
        val now = System.currentTimeMillis()
        val fromTime = now - TimeUnit.MINUTES.toMillis(60)
        val lastEvent = latestNoteEvents
            .asSequence()
            .filter { it.timestamp >= fromTime && it.note?.contains(keyword, ignoreCase = true) == true }
            .maxByOrNull { it.timestamp }
        return lastEvent?.let { (now - it.timestamp) / 60000 } ?: -1L
    }

    private data class TherapySnapshot(
        val sleepTime: Boolean,
        val sportTime: Boolean,
        val snackTime: Boolean,
        val lowCarbTime: Boolean,
        val highCarbTime: Boolean,
        val mealTime: Boolean,
        val bfastTime: Boolean,
        val lunchTime: Boolean,
        val dinnerTime: Boolean,
        val fastingTime: Boolean,
        val stopTime: Boolean,
        val calibrationTime: Boolean,
        val deleteTime: Boolean,
        val deleteEventDate: String?,
        val latestNoteEvents: List<TE>,
        val generatedAtMs: Long
    )

    companion object {
        private const val SNAPSHOT_TTL_MS = 30_000L
        private val snapshotRef = AtomicReference<TherapySnapshot?>(null)
        private val refreshInFlight = AtomicBoolean(false)
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
