package app.aaps.plugins.aps.openAPSAIMI.utils

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.CloudBackupConstants
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAimiCloudBackupResult
import app.aaps.core.interfaces.rx.events.EventAimiCloudBackupTrigger
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🚀 Gestionnaire de sauvegarde AIMI vers le Cloud.
 * Coordonne la collecte des fichiers AIMI et leur envoi vers le fournisseur Cloud actif.
 */
@Singleton
class AimiBackupManager @Inject constructor(
    private val storageHelper: AimiStorageHelper,
    private val importExportPrefs: ImportExportPrefs,
    private val rxBus: RxBus,
    private val log: AAPSLogger
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val disposables = CompositeDisposable()

    init {
        disposables += rxBus.toObservable(EventAimiCloudBackupTrigger::class.java)
            .subscribe { backupToCloud() }
    }

    /**
     * Lance la sauvegarde de tous les fichiers AIMI vers le cloud.
     */
    fun backupToCloud(onComplete: (Int, Int) -> Unit = { _, _ -> }) {
        scope.launch {
            val candidates = storageHelper.listBackupCandidates()
            var successCount = 0
            
            candidates.forEach { file ->
                try {
                    val bytes = file.readBytes()
                    val mimeType = when {
                        file.name.endsWith(".json") -> "application/json"
                        file.name.endsWith(".csv") -> "text/csv"
                        file.name.endsWith(".jsonl") -> "application/x-jsonlines"
                        else -> "application/octet-stream"
                    }

                    log.info(LTag.APS, "[Cloud] AIMI Backup: Bridging upload for ${file.name}...")
                    val success = importExportPrefs.uploadFileToCloud(
                        fileName = file.name,
                        fileContent = bytes,
                        mimeType = mimeType,
                        remotePath = CloudBackupConstants.CLOUD_PATH_AIMI
                    )

                    if (success) {
                        successCount++
                        log.info(LTag.APS, "[Cloud] AIMI Backup: ✅ Bridged ${file.name}")
                    } else {
                        log.error(LTag.APS, "[Cloud] AIMI Backup: ❌ Failed to bridge ${file.name}")
                    }
                } catch (e: Exception) {
                    log.error(LTag.APS, "[Cloud] AIMI Backup: Exception during upload of ${file.name}", e)
                }
            }

            log.info(LTag.APS, "[Cloud] AIMI Backup: Bridge Request Completed ($successCount/${candidates.size})")
            val result = EventAimiCloudBackupResult(successCount, candidates.size)
            rxBus.send(result)
            withContext(Dispatchers.Main) {
                onComplete(successCount, candidates.size)
            }
        }
    }
}
