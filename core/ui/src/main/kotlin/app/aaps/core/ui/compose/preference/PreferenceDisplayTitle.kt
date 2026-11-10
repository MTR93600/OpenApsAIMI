package app.aaps.core.ui.compose.preference

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.util.Locale

/**
 * Label for a preference row. Keys without [titleResId] (common when titles are set only in XML)
 * still need text in Compose — fall back to a readable form of the storage key.
 */
@Composable
internal fun preferenceDisplayTitle(titleResId: Int, storageKey: String): String =
    if (titleResId != 0) {
        stringResource(titleResId)
    } else {
        humanizeStorageKey(storageKey)
    }

private fun humanizeStorageKey(storageKey: String): String {
    var rest = storageKey.trim()
    if (rest.startsWith("key_")) {
        rest = rest.removePrefix("key_")
    }
    return rest.split('_')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word ->
            word.replaceFirstChar { c ->
                if (c.isLowerCase()) c.titlecase(Locale.getDefault()) else c.toString()
            }
        }
        .ifEmpty { storageKey }
}
