package app.aaps.aimi_viewer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Read-only bridge between Flutter and Android's Storage Access Framework.
 *
 * Android gives the app a persisted content URI tree permission. Recognized
 * exports are copied to private cache before Dart parses them; source files
 * are never opened for writing.
 */
class MainActivity : FlutterActivity() {

    private companion object {
        const val CHANNEL = "app.aaps.aimiviewer/storage"
        const val PICK_DIRECTORY_REQUEST = 4107
        const val PREFS = "aimi_viewer_storage"
        const val PREF_TREE_URI = "tree_uri"
        const val TAG = "AimiViewerStorage"

        const val DECISIONS_24H = "AIMI_Decisions_Last24h.jsonl"
        const val DECISIONS_FULL = "AIMI_Decisions.jsonl"

        val STAGE_LIMITS = linkedMapOf(
            DECISIONS_24H to 64L * 1024 * 1024,
            DECISIONS_FULL to 48L * 1024 * 1024,
            "oapsaimi_pkpd_records.csv" to 24L * 1024 * 1024,
            "AIMI_HORMONITOR_event_stream_v1.jsonl" to 48L * 1024 * 1024,
            "AIMI_HORMONITOR_daily_outcomes_v1.jsonl" to 8L * 1024 * 1024,
            "AIMI_HORMONITOR_dataset_qa_v1.jsonl" to 4L * 1024 * 1024,
            "AIMI_HORMONITOR_shadow_contributions_v1.jsonl" to 12L * 1024 * 1024,
            "AIMI_HORMONITOR_loop_blackbox_v1.jsonl" to 12L * 1024 * 1024,
            "AIMI_HORMONITOR_daily_state_v1.json" to 2L * 1024 * 1024,
        )
    }

    private data class DocumentEntry(
        val name: String,
        val uri: Uri,
        val size: Long,
        val lastModifiedMs: Long,
    )

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var pendingPickerResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "getDirectory" -> handleGetDirectory(result)
                    "chooseDirectory" -> handleChooseDirectory(result)
                    "stageFiles" -> handleStageFiles(result)
                    else -> result.notImplemented()
                }
            }
    }

    private fun handleGetDirectory(result: MethodChannel.Result) {
        val uri = persistedTreeUri()
        if (uri == null) {
            result.success(null)
            return
        }
        if (!hasPersistedReadPermission(uri)) {
            preferences().edit().remove(PREF_TREE_URI).apply()
            result.error("PERMISSION_LOST", "The persisted folder permission is no longer valid.", null)
            return
        }
        result.success(directoryMap(uri))
    }

    private fun handleChooseDirectory(result: MethodChannel.Result) {
        if (pendingPickerResult != null) {
            result.error("PICKER_ACTIVE", "A folder picker is already open.", null)
            return
        }
        pendingPickerResult = result
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:Documents/AAPS",
                    ),
                )
            }
        }
        startActivityForResult(intent, PICK_DIRECTORY_REQUEST)
    }

    @Deprecated("Kept for compatibility with FlutterActivity activity result dispatch.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_DIRECTORY_REQUEST) return
        val result = pendingPickerResult ?: return
        pendingPickerResult = null
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            result.success(null)
            return
        }

        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val previous = persistedTreeUri()
            preferences().edit().putString(PREF_TREE_URI, uri.toString()).apply()
            if (previous != null && previous != uri) {
                runCatching {
                    contentResolver.releasePersistableUriPermission(
                        previous,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            result.success(directoryMap(uri))
        } catch (error: SecurityException) {
            result.error("PERMISSION_LOST", error.message, null)
        }
    }

    private fun handleStageFiles(result: MethodChannel.Result) {
        val treeUri = persistedTreeUri()
        if (treeUri == null || !hasPersistedReadPermission(treeUri)) {
            result.error("PERMISSION_LOST", "Select Documents/AAPS again.", null)
            return
        }
        ioExecutor.execute {
            try {
                val staged = stageRecognizedFiles(treeUri)
                runOnUiThread { result.success(staged) }
            } catch (error: SecurityException) {
                runOnUiThread { result.error("PERMISSION_LOST", error.message, null) }
            } catch (error: Exception) {
                Log.e(TAG, "Unable to stage AIMI exports", error)
                runOnUiThread { result.error("STAGE_FAILED", error.message, null) }
            }
        }
    }

    private fun stageRecognizedFiles(treeUri: Uri): List<Map<String, Any>> {
        val children = listDirectChildren(treeUri).associateBy { it.name }
        val names = STAGE_LIMITS.keys.toMutableList()
        if (children.containsKey(DECISIONS_24H)) {
            names.remove(DECISIONS_FULL)
        } else {
            names.remove(DECISIONS_24H)
        }

        val stageDirectory = File(cacheDir, "aimi_import").apply {
            if (!exists() && !mkdirs()) error("Unable to create private staging directory.")
        }
        stageDirectory.listFiles()?.filter { it.isFile }?.forEach { it.delete() }

        return names.mapNotNull { name ->
            val entry = children[name] ?: return@mapNotNull null
            runCatching {
                stageEntry(
                    entry = entry,
                    target = File(stageDirectory, name),
                    maxBytes = STAGE_LIMITS.getValue(name),
                    tailSafe = name.endsWith(".jsonl") || name.endsWith(".csv"),
                )
            }.onFailure { error ->
                Log.w(TAG, "Skipping unreadable export $name", error)
            }.getOrNull()
        }
    }

    private fun stageEntry(
        entry: DocumentEntry,
        target: File,
        maxBytes: Long,
        tailSafe: Boolean,
    ): Map<String, Any> {
        val truncated = tailSafe && entry.size > maxBytes
        val offset = if (truncated) entry.size - maxBytes else 0L
        val descriptor = contentResolver.openFileDescriptor(entry.uri, "r")
            ?: error("Unable to open ${entry.name}")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            if (offset > 0L) {
                input.channel.position(offset)
                // The seek normally lands in the middle of a record. Drop that fragment.
                var value = input.read()
                while (value >= 0 && value != '\n'.code) value = input.read()
            }
            FileOutputStream(target, false).use { output -> input.copyTo(output) }
        }
        return mapOf(
            "name" to entry.name,
            "path" to target.absolutePath,
            "sourceSize" to entry.size,
            "stagedSize" to target.length(),
            "lastModifiedMs" to entry.lastModifiedMs,
            "truncated" to truncated,
        )
    }

    private fun listDirectChildren(treeUri: Uri): List<DocumentEntry> {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val result = ArrayList<DocumentEntry>()
        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                if (cursor.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val name = cursor.getString(nameIndex) ?: continue
                if (!STAGE_LIMITS.containsKey(name)) continue
                val childUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    cursor.getString(idIndex),
                )
                result += DocumentEntry(
                    name = name,
                    uri = childUri,
                    size = if (cursor.isNull(sizeIndex)) 0L else cursor.getLong(sizeIndex),
                    lastModifiedMs = if (cursor.isNull(modifiedIndex)) 0L else cursor.getLong(modifiedIndex),
                )
            }
        }
        return result
    }

    private fun directoryMap(uri: Uri): Map<String, String> = mapOf(
        "uri" to uri.toString(),
        "name" to displayName(uri),
    )

    private fun displayName(uri: Uri): String {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val rawName = runCatching {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return if (rawName.equals("AAPS", ignoreCase = true)) "Documents/AAPS"
        else rawName?.takeIf { it.isNotBlank() } ?: "Documents/AAPS"
    }

    private fun hasPersistedReadPermission(uri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission
        }

    private fun persistedTreeUri(): Uri? =
        preferences().getString(PREF_TREE_URI, null)?.let(Uri::parse)

    private fun preferences() = getSharedPreferences(PREFS, MODE_PRIVATE)

    override fun onDestroy() {
        ioExecutor.shutdown()
        super.onDestroy()
    }
}
