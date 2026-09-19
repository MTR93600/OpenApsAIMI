package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.aaps.plugins.aps.R

/** The provider ids [ProviderDropdown] offers, matching what `StringKey.AimiAdvisorProvider` stores. */
internal val AI_PROVIDER_IDS = listOf("OPENAI", "GEMINI", "DEEPSEEK", "CLAUDE")

/**
 * The AI provider picker, shared by the Meal Advisor and the Profile Advisor's model-selector
 * dialog so both screens pick from the same list with the same labels.
 *
 * Extracted from the Meal Advisor screen (sub-lot 5/5 of the Profile Advisor Compose port) - the
 * Meal Advisor's own rendering is unchanged, it just calls this shared copy now.
 *
 * [selected] must be one of [AI_PROVIDER_IDS] (upper-case); an unknown value falls back to OpenAI's
 * label rather than showing blank text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderDropdown(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val displayNames = mapOf(
        "OPENAI" to stringResource(R.string.aimi_prefs_provider_openai),
        "GEMINI" to stringResource(R.string.aimi_prefs_provider_gemini),
        "DEEPSEEK" to stringResource(R.string.aimi_prefs_provider_deepseek),
        "CLAUDE" to stringResource(R.string.aimi_prefs_provider_claude),
    )
    val selectedText = displayNames[selected] ?: displayNames.getValue("OPENAI")

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.aimi_meal_advisor_provider_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            AI_PROVIDER_IDS.forEach { providerId ->
                DropdownMenuItem(
                    text = { Text(displayNames.getValue(providerId)) },
                    onClick = {
                        onSelect(providerId)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}
