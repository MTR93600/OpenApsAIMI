package app.aaps.plugins.aps.openAPSAIMI.utils

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.CloudBackupConstants
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAimiCloudBackupResult
import app.aaps.core.interfaces.rx.events.EventAimiCloudBackupTrigger
import app.aaps.core.interfaces.storage.Storage
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
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
    private val log: AAPSLogger,
    private val context: Context,
    private val storage: Storage,
    private val preferences: Preferences
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val disposables = CompositeDisposable()

    init {
        log.info(LTag.APS, "🚀 AimiBackupManager initialized and listening for triggers")
        disposables += rxBus.toObservable(EventAimiCloudBackupTrigger::class.java)
            .subscribe { backupToCloud() }
    }

    /**
     * Lance la sauvegarde de tous les fichiers AIMI vers le cloud.
     */
    fun backupToCloud(onComplete: (Int, Int) -> Unit = { _, _ -> }) {
        scope.launch {
            log.info(LTag.APS, "[Cloud] AIMI Backup: Starting multi-strategy scan...")
            
            // 1. Scan Legacy/App-scoped (File API)
            val legacyCandidates = storageHelper.listBackupCandidates()
            
            // 2. Scan SAF (Storage Access Framework)
            val safCandidates = scanSafCandidates()
            
            // 3. Fusion et déduplication
            // On utilise une Map indexée par le nom du fichier pour ne garder qu'une version
            val allCandidates = mutableMapOf<String, BackupCandidate>()
            
            legacyCandidates.forEach { file ->
                allCandidates[file.name] = BackupCandidate.FromLegacy(file)
            }
            
            safCandidates.forEach { doc ->
                val name = doc.name ?: "Unknown"
                // On privilégie le SAF si doublon car souvent plus à jour sur Android récent
                allCandidates[name] = BackupCandidate.FromSaf(doc)
            }
            
            val candidatesList = allCandidates.values.toList()
            log.info(LTag.APS, "[Cloud] AIMI Backup: Total unique candidates found: ${candidatesList.size} " +
                    "(Legacy: ${legacyCandidates.size}, SAF: ${safCandidates.size})")

            var successCount = 0
            
            candidatesList.forEach { candidate ->
                try {
                    val bytes = candidate.readBytes(context, storage)
                    val mimeType = when {
                        candidate.name.endsWith(".json") -> "application/json"
                        candidate.name.endsWith(".csv") -> "text/csv"
                        candidate.name.endsWith(".jsonl") -> "application/x-jsonlines"
                        else -> "application/octet-stream"
                    }

                    log.info(LTag.APS, "[Cloud] AIMI Backup: Uploading ${candidate.name} (${bytes.size} bytes) to ${CloudBackupConstants.CLOUD_PATH_AIMI}...")
                    
                    // On s'assure que le chemin est propre (normalizeAapsPath s'en occupe déjà côté GDrive, mais on logue le chemin cible)
                    val success = importExportPrefs.uploadFileToCloud(
                        fileName = candidate.name,
                        fileContent = bytes,
                        mimeType = mimeType,
                        remotePath = CloudBackupConstants.CLOUD_PATH_AIMI
                    )

                    if (success) {
                        successCount++
                        log.info(LTag.APS, "[Cloud] AIMI Backup: ✅ Successfully uploaded ${candidate.name}")
                    } else {
                        log.error(LTag.APS, "[Cloud] AIMI Backup: ❌ Failed to upload ${candidate.name}")
                    }
                } catch (e: Exception) {
                    log.error(LTag.APS, "[Cloud] AIMI Backup: Exception during upload of ${candidate.name}", e)
                }
            }

            log.info(LTag.APS, "[Cloud] AIMI Backup: Bridge Request Completed ($successCount/${candidatesList.size})")
            val result = EventAimiCloudBackupResult(successCount, candidatesList.size)
            rxBus.send(result)
            withContext(Dispatchers.Main) {
                onComplete(successCount, candidatesList.size)
            }
        }
    }

    /**
     * Scanne récursivement le dossier AAPS via SAF.
     */
    private fun scanSafCandidates(): List<DocumentFile> {
        val candidates = mutableListOf<DocumentFile>()
        val uriString = preferences.getIfExists(StringKey.AapsDirectoryUri) ?: return emptyList()
        
        try {
            val rootUri = Uri.parse(uriString)
            val rootDoc = DocumentFile.fromTreeUri(context, rootUri)
            if (rootDoc == null || !rootDoc.canRead()) {
                log.warn(LTag.APS, "[Cloud] AIMI Backup: SAF Root unreachable or unreadable: $uriString")
                return emptyList()
            }

            fun scan(dir: DocumentFile) {
                dir.listFiles().forEach { doc ->
                    if (doc.isDirectory) {
                        scan(doc)
                    } else {
                        val name = doc.name?.lowercase() ?: ""
                        if (name.endsWith(".json") || name.endsWith(".csv") || name.endsWith(".jsonl")) {
                            if (!name.contains(".tmp") && !name.contains(".pending")) {
                                candidates.add(doc)
                            }
                        }
                    }
                }
            }

            scan(rootDoc)
            log.info(LTag.APS, "[Cloud] AIMI Backup: SAF scan found ${candidates.size} files in tree $uriString")
        } catch (e: Exception) {
            log.error(LTag.APS, "[Cloud] AIMI Backup: SAF scan failed", e)
        }
        
        return candidates
    }

    /**
     * Abstraction pour gérer les deux types de sources (File et DocumentFile).
     */
    sealed class BackupCandidate {
        abstract val name: String
        abstract fun readBytes(context: Context, storage: Storage): ByteArray

        data class FromLegacy(val file: File) : BackupCandidate() {
            override val name: String = file.name
            override fun readBytes(context: Context, storage: Storage): ByteArray = file.readBytes()
        }

        data class FromSaf(val doc: DocumentFile) : BackupCandidate() {
            override val name: String = doc.name ?: "Unknown"
            override fun readBytes(context: Context, storage: Storage): ByteArray {
                return storage.getBinaryFileContents(context.contentResolver, doc) ?: throw Exception("Cannot read SAF file")
            }
        }
    }
}
