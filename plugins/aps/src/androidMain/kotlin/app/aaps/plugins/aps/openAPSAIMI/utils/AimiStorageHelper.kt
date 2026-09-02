package app.aaps.plugins.aps.openAPSAIMI.utils

import android.content.Context
import android.os.Environment
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File

/**
 * 🛡️ Helper pour stockage robuste AIMI avec stratégie hybride.
 *
 * Stratégie en 3 niveaux :
 * 1️⃣ PRÉFÉRÉ : Documents/AAPS (cohérence avec design AIMI, accessible utilisateur)
 * 2️⃣ FALLBACK : App-scoped external storage (pas de permissions requises)
 * 3️⃣ DERNIER RECOURS : Internal storage (toujours disponible)
 *
 * Garantie : NE CRASH JAMAIS, même si permissions manquantes.
 *
 * Utilisation :
 * ```kotlin
 * @Inject lateinit var storageHelper: AimiStorageHelper
 *
 * private val file by lazy { storageHelper.getAimiFile("my_data.json") }
 * ```
 */
@SingleIn(AppScope::class)
class AimiStorageHelper @Inject constructor(
    private val context: Context,
    private val log: AAPSLogger
) {

    companion object {
        /** Same cap as dump `AimiBackupManager.MAX_BACKUP_FILE_BYTES` (SAF manager stays dump). */
        const val MAX_BACKUP_FILE_BYTES: Long = 16L * 1024L * 1024L
    }

    /**
     * État du stockage utilisé (pour logs de santé)
     */
    enum class StorageStatus {
        DOCUMENTS_AAPS,      // ✅ Documents/AAPS accessible
        APP_SCOPED_EXTERNAL, // ⚠️ Fallback app-scoped external
        INTERNAL_ONLY,       // ⚠️ Dernier recours internal
        ERROR                // ❌ Erreur (ne devrait jamais arriver)
    }

    private var currentStatus: StorageStatus = StorageStatus.ERROR
    private var currentDirectory: File? = null
    private var lastError: String? = null

    /**
     * Obtient le statut actuel du stockage (pour monitoring)
     */
    fun getStorageStatus(): Triple<StorageStatus, String?, String?> {
        return Triple(currentStatus, currentDirectory?.absolutePath, lastError)
    }

    /**
     * Détermine le meilleur répertoire de stockage disponible.
     * Appelé une seule fois au premier accès (lazy init).
     */
    @Synchronized
    private fun determineStorageDirectory(): File {
        if (currentDirectory != null) {
            return currentDirectory!!
        }

        // 1️⃣ Tenter Documents/AAPS d'abord (préféré pour cohérence AIMI)
        try {
            val docsDir = File(Environment.getExternalStorageDirectory(), "Documents/AAPS")

            // Créer le répertoire s'il n'existe pas
            if (!docsDir.exists()) {
                if (docsDir.mkdirs()) {
                    log.info(LTag.AIMI, "AimiStorageHelper: ✅ Created Documents/AAPS directory")
                }
            }

            // Tester si on peut écrire (vérification permission)
            if (docsDir.exists() && docsDir.canWrite()) {
                currentStatus = StorageStatus.DOCUMENTS_AAPS
                currentDirectory = docsDir
                log.info(LTag.AIMI, "AimiStorageHelper: 📁 Using Documents/AAPS (preferred)")
                log.info(LTag.AIMI, "  → Path: ${docsDir.absolutePath}")
                return docsDir
            } else {
                lastError = "Documents/AAPS not writable (permission issue?)"
                log.warn(LTag.AIMI, "AimiStorageHelper: ⚠️ $lastError")
            }
        } catch (e: Exception) {
            lastError = "Cannot access Documents/AAPS: ${e.message}"
            log.warn(LTag.AIMI, "AimiStorageHelper: ⚠️ $lastError")
        }

        // 2️⃣ Fallback vers app-scoped external storage
        try {
            val appDataDir = context.getExternalFilesDir(null)
            if (appDataDir != null && (appDataDir.exists() || appDataDir.mkdirs())) {
                currentStatus = StorageStatus.APP_SCOPED_EXTERNAL
                currentDirectory = appDataDir
                log.info(LTag.AIMI, "AimiStorageHelper: 📁 Using app-scoped external storage (fallback)")
                log.info(LTag.AIMI, "  → Path: ${appDataDir.absolutePath}")
                log.info(LTag.AIMI, "  → Reason: $lastError")
                return appDataDir
            }
        } catch (e: Exception) {
            lastError = "Cannot access external app storage: ${e.message}"
            log.warn(LTag.AIMI, "AimiStorageHelper: Cannot access external app storage: ${e.message}")
        }

        // 3️⃣ Dernier recours : stockage interne (toujours disponible)
        currentStatus = StorageStatus.INTERNAL_ONLY
        currentDirectory = context.filesDir
        log.warn(LTag.AIMI, "AimiStorageHelper: 📁 Using internal storage (last resort)")
        log.warn(LTag.AIMI, "  → Path: ${context.filesDir.absolutePath}")
        log.warn(LTag.AIMI, "  → Reason: $lastError")
        return context.filesDir
    }

    /**
     * Obtient le répertoire de stockage AIMI.
     */
    fun getAimiDirectory(): File {
        return determineStorageDirectory()
    }

    /**
     * Obtient un fichier dans le répertoire AIMI.
     *
     * @param filename Nom du fichier (ex: "basal_learning.json")
     * @return File dans le meilleur emplacement disponible
     */
    fun getAimiFile(filename: String): File {
        val dir = getAimiDirectory()
        return File(dir, filename).also {
            log.debug(LTag.AIMI, "AimiStorageHelper: File '$filename' → ${it.absolutePath}")
        }
    }

    /**
     * Liste tous les fichiers AIMI susceptibles d'être sauvegardés.
     * Scanne récursivement le répertoire AAPS pour les modèles (.json), datasets (.csv) et logs (.jsonl).
     * Files larger than `MAX_BACKUP_FILE_BYTES` are omitted (OOM guard).
     */
    fun listBackupCandidates(): List<File> {
        val root = getAimiDirectory()
        val candidates = mutableListOf<File>()
        val maxBytes = MAX_BACKUP_FILE_BYTES

        log.info(LTag.AIMI, "AimiStorageHelper: Scanning legacy directory for backup: ${root.absolutePath}")

        fun scan(dir: File) {
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    scan(file)
                } else {
                    val name = file.name.lowercase()
                    if (name.endsWith(".json") || name.endsWith(".csv") || name.endsWith(".jsonl")) {
                        // Exclure les fichiers temporaires ou backups automatiques si nécessaire
                        if (!name.contains(".tmp") && !name.contains(".pending")) {
                            val len = file.length()
                            if (len > maxBytes) {
                                log.warn(
                                    LTag.AIMI,
                                    "AimiStorageHelper: Skipping oversized backup candidate ${file.name} ($len bytes)",
                                )
                            } else {
                                candidates.add(file)
                            }
                        }
                    }
                }
            }
        }

        scan(root)
        log.info(LTag.AIMI, "AimiStorageHelper: Found ${candidates.size} backup candidates in ${root.absolutePath}")
        return candidates
    }

    /**
     * Obtient un fichier dans un sous-répertoire AIMI.
     *
     * @param subdirectory Sous-répertoire (ex: "ml", "csv")
     * @param filename Nom du fichier
     * @return File dans le meilleur emplacement disponible
     */
    fun getAimiFile(subdirectory: String, filename: String): File {
        val dir = getAimiDirectory()
        val subdir = File(dir, subdirectory)
        if (!subdir.exists()) {
            subdir.mkdirs()
        }
        return File(subdir, filename).also {
            log.debug(LTag.AIMI, "AimiStorageHelper: File '$subdirectory/$filename' → ${it.absolutePath}")
        }
    }

    /**
     * Charge un fichier de manière robuste avec fallback.
     *
     * @param file Fichier à charger
     * @param onSuccess Callback appelé avec le contenu si succès
     * @param onError Callback appelé en cas d'erreur (optionnel)
     * @return true si chargé avec succès
     */
    fun loadFileSafe(
        file: File,
        onSuccess: (String) -> Unit,
        onError: ((Throwable) -> Unit)? = null
    ): Boolean {
        return runCatching {
            if (!file.exists()) {
                log.debug(LTag.AIMI, "AimiStorageHelper: File ${file.name} does not exist (first run)")
                return false
            }

            if (!file.canRead()) {
                log.warn(LTag.AIMI, "AimiStorageHelper: File ${file.name} exists but cannot be read")
                return false
            }

            val content = file.readText()
            if (content.isEmpty()) {
                log.warn(LTag.AIMI, "AimiStorageHelper: File ${file.name} is empty")
                return false
            }

            onSuccess(content)
            log.debug(LTag.AIMI, "AimiStorageHelper: ✅ Loaded ${file.name} (${content.length} bytes)")
            true

        }.getOrElse { e ->
            log.error(LTag.AIMI, "AimiStorageHelper: Failed to load ${file.name}: ${e.message}", e)
            onError?.invoke(e)
            false
        }
    }

    /**
     * Sauvegarde un fichier de manière robuste.
     *
     * @param file Fichier à sauvegarder
     * @param content Contenu à écrire
     * @return true si sauvegardé avec succès
     */
    fun saveFileSafe(file: File, content: String): Boolean {
        return runCatching {
            file.writeText(content)
            log.debug(LTag.AIMI, "AimiStorageHelper: ✅ Saved ${file.name} (${content.length} bytes)")
            true
        }.getOrElse { e ->
            log.warn(LTag.AIMI, "AimiStorageHelper: ⚠️ Failed to save ${file.name}: ${e.message}")
            log.debug(LTag.AIMI, "  → Path: ${file.absolutePath}")
            log.debug(LTag.AIMI, "  → Data will be lost on restart but app continues normally")
            false
        }
    }

    /**
     * Génère un rapport de santé du stockage pour les logs Adjustments.
     */
    fun getHealthReport(): String {
        val (status, path, error) = getStorageStatus()
        return when (status) {
            StorageStatus.DOCUMENTS_AAPS ->
                "✅ Storage: Documents/AAPS"
            StorageStatus.APP_SCOPED_EXTERNAL ->
                "⚠️ Storage: App-scoped (fallback) - Reason: ${error ?: "unknown"}"
            StorageStatus.INTERNAL_ONLY ->
                "⚠️ Storage: Internal only (degraded) - Reason: ${error ?: "unknown"}"
            StorageStatus.ERROR ->
                "❌ Storage: ERROR - ${error ?: "unknown"}"
        }
    }

    /**
     * The app scoped external directory, tier 2 of [determineStorageDirectory], or `null`.
     *
     * Exposed so [AndroidAimiStorage] can build the CSV fallback path without a second copy of the
     * platform call. It reads the same `getExternalFilesDir(null)` tier 2 uses, and it does not
     * change which directory this helper hands out.
     */
    fun appScopedExternalDir(): File? = context.getExternalFilesDir(null)
}
