package app.aaps.plugins.aps.openAPSAIMI.remote

import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNSClientNewLog
import app.aaps.core.interfaces.rx.events.EventNewHistoryData
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject
import javax.inject.Singleton
import app.aaps.core.data.time.T

@Singleton
class AimiRemoteManager @Inject constructor(
    private val rxBus: RxBus,
    private val aapsSchedulers: AapsSchedulers,
    private val aapsLogger: AAPSLogger,
    private val fabricPrivacy: FabricPrivacy,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil,
    private val parser: RemoteCommandParser,
    private val executor: RemoteCommandExecutor
) {

    private val disposable = CompositeDisposable()
    private var isStarted = false
    
    // Cache to prevent re-executing the same command ID
    private val processedIds = mutableSetOf<String>()

    fun start() {
        if (isStarted) return
        isStarted = true
        
        aapsLogger.debug(LTag.APS, "[Remote] Starting AimiRemoteManager")

        disposable += rxBus
            .toObservable(EventNewHistoryData::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ 
                checkForRemoteCommands() 
            }, fabricPrivacy::logException)
    }

    fun stop() {
        isStarted = false
        disposable.clear()
        processedIds.clear()
        aapsLogger.debug(LTag.APS, "[Remote] Stopped AimiRemoteManager")
    }

    private fun checkForRemoteCommands() {
        // Look for very recent TherapyEvents of type Note/Announcement
        // window: last 5 minutes
        val now = dateUtil.now()
        val since = now - T.mins(5).msecs()
        
        val events = persistenceLayer.getTherapyEventDataFromToTime(since, now).blockingGet() ?: emptyList()
        
        events.forEach { event ->
            // Only Notes/Announcements
            if (event.type == TE.Type.NOTE || event.type == TE.Type.ANNOUNCEMENT) {
                val text = event.note ?: return@forEach
                val id = event.ids.nightscoutId ?: event.timestamp.toString()
                
                if (processedIds.contains(id)) return@forEach
                
                val parsed = parser.parse(text)
                if (parsed != null) {
                    aapsLogger.info(LTag.APS, "[Remote] Found command: $text (ID: $id)")
                    processedIds.add(id) // Mark as processed BEFORE execution to avoid loops
                    
                    // Cleanup cache if too big
                    if (processedIds.size > 100) {
                        processedIds.clear()
                        processedIds.add(id)
                    }

                    executor.execute(parsed)
                }
            }
        }
    }
}
