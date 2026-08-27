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
import java.io.ByteArrayOutputStream
import java.io.File
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope

/**
 * Gestionnaire de sauvegarde AIMI vers le Cloud.
 * Coordonne la collecte des fichiers AIMI et leur envoi vers le fournisseur Cloud actif.
 *
 * Never load unbounded files into heap: oversized candidates are skipped, and
 * [OutOfMemoryError] on a single file must not kill the process (Firebase OOM
 * from ~200MB `readBytes` on decision logs).
 */
@SingleIn(AppScope::class)
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

    companion object {
        /** Hard cap per file before full in-memory read/upload (models/CSV stay under this). */
        const val MAX_BACKUP_FILE_BYTES: Long = 16L * 1024L * 1024L
    }

    init {
        log.info(LTag.APS, "AimiBackupManager initialized and listening for triggers")
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
            var skippedOversized = 0
            
            candidatesList.forEach { candidate ->
                try {
                    val bytes = readCandidateBytesCapped(candidate) ?: run {
                        skippedOversized++
                        log.warn(
                            LTag.APS,
                            "[Cloud] AIMI Backup: Skipping ${candidate.name} " +
                                "(size unknown or > $MAX_BACKUP_FILE_BYTES cap) to avoid OOM",
                        )
                        return@forEach
                    }

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
                        log.info(LTag.APS, "[Cloud] AIMI Backup: Successfully uploaded ${candidate.name}")
                    } else {
                        log.error(LTag.APS, "[Cloud] AIMI Backup: Failed to upload ${candidate.name}")
                    }
                } catch (oom: OutOfMemoryError) {
                    // OutOfMemoryError is Error, not Exception — must catch or the process dies.
                    log.error(
                        LTag.APS,
                        "[Cloud] AIMI Backup: OOM reading/uploading ${candidate.name}; skipping file",
                        oom,
                    )
                    System.gc()
                } catch (e: Exception) {
                    log.error(LTag.APS, "[Cloud] AIMI Backup: Exception during upload of ${candidate.name}", e)
                }
            }

            log.info(
                LTag.APS,
                "[Cloud] AIMI Backup: Bridge Request Completed " +
                    "($successCount/${candidatesList.size}, skippedOversized=$skippedOversized)",
            )
            val result = EventAimiCloudBackupResult(successCount, candidatesList.size)
            rxBus.send(result)
            withContext(Dispatchers.Main) {
                onComplete(successCount, candidatesList.size)
            }
        }
    }

    /**
     * Reads candidate bytes only if size is within [MAX_BACKUP_FILE_BYTES].
     * Returns null when oversized (known length or streaming overrun).
     * SAF `length()` may be 0 when unknown — stream with a hard cap instead of `readBytes()`.
     */
    private fun readCandidateBytesCapped(candidate: BackupCandidate): ByteArray? {
        val knownSize = candidate.sizeBytes()
        if (knownSize > MAX_BACKUP_FILE_BYTES) return null

        return when (candidate) {
            is BackupCandidate.FromLegacy -> {
                if (knownSize <= 0L && candidate.file.length() > MAX_BACKUP_FILE_BYTES) return null
                candidate.readBytes(context, storage)
            }
            is BackupCandidate.FromSaf -> readSafBytesCapped(candidate.doc)
        }
    }

    private fun readSafBytesCapped(doc: DocumentFile): ByteArray? {
        val knownSize = doc.length()
        if (knownSize > MAX_BACKUP_FILE_BYTES) return null
        val input = context.contentResolver.openInputStream(doc.uri) ?: return null
        input.use { stream ->
            val initial = when {
                knownSize in 1..MAX_BACKUP_FILE_BYTES -> knownSize.toInt()
                else -> 8 * 1024
            }
            val out = ByteArrayOutputStream(initial)
            val buf = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BACKUP_FILE_BYTES) return null
                out.write(buf, 0, n)
            }
            return out.toByteArray()
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
                                val len = doc.length()
                                if (len > MAX_BACKUP_FILE_BYTES) {
                                    log.warn(
                                        LTag.APS,
                                        "[Cloud] AIMI Backup: SAF skip oversized ${doc.name} ($len bytes)",
                                    )
                                } else {
                                    candidates.add(doc)
                                }
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
        /** Known size in bytes, or 0 when unknown (SAF). */
        abstract fun sizeBytes(): Long
        abstract fun readBytes(context: Context, storage: Storage): ByteArray

        data class FromLegacy(val file: File) : BackupCandidate() {
            override val name: String = file.name
            override fun sizeBytes(): Long = file.length()
            override fun readBytes(context: Context, storage: Storage): ByteArray = file.readBytes()
        }

        data class FromSaf(val doc: DocumentFile) : BackupCandidate() {
            override val name: String = doc.name ?: "Unknown"
            override fun sizeBytes(): Long = doc.length()
            override fun readBytes(context: Context, storage: Storage): ByteArray {
                return storage.getBinaryFileContents(context.contentResolver, doc) ?: throw Exception("Cannot read SAF file")
            }
        }
    }
}
