package app.aaps.plugins.aps.openAPSAIMI.advisor.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.advisor.diag.AimiSupportPackageExporter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Study Compose stand-in for parked `AimiProfileAdvisorActivity` support-ZIP flow.
 *
 * Expert-code gate, optional ticket text, then a ZIP with the diagnostic report
 * (`[ACTIVE PROFILE]`), last-24h decision log, and `oapsaimiML2_records.csv` tail.
 * View Activity pattern is wrong for this KMP tree (Compose preferences, no Hilt).
 */
@Composable
fun AimiSupportPackageScreen(
    onBack: () -> Unit,
    verifyCode: (String) -> Boolean,
    buildPackage: suspend (issue: String) -> AimiSupportPackageExporter.Result,
    sharePackage: (zipFile: File, issue: String) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var unlocked by rememberSaveable { mutableStateOf(false) }
    var issue by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val generatingMessage = stringResource(R.string.aimi_diag_generating)
    val invalidMessage = stringResource(R.string.aimi_adv_support_invalid)
    val emptyMessage = stringResource(R.string.aimi_diag_empty)
    val errorPrefix = stringResource(R.string.aimi_adv_error_gen)

    fun onVerify() {
        if (verifyCode(code)) {
            unlocked = true
        } else {
            unlocked = false
            scope.launch { snackbarHostState.showSnackbar(invalidMessage) }
        }
    }

    fun onGenerate() {
        if (busy) return
        busy = true
        scope.launch {
            snackbarHostState.showSnackbar(generatingMessage)
            val result = withContext(Dispatchers.IO) { buildPackage(issue) }
            busy = false
            when (result) {
                is AimiSupportPackageExporter.Result.Ready -> sharePackage(result.zipFile, issue)
                AimiSupportPackageExporter.Result.Empty -> snackbarHostState.showSnackbar(emptyMessage)
                is AimiSupportPackageExporter.Result.Failed ->
                    snackbarHostState.showSnackbar("$errorPrefix: ${result.message ?: ""}")
            }
        }
    }

    ProvidePreferenceTheme {
        Scaffold(
            topBar = {
                AapsTopAppBar(
                    title = { Text(stringResource(R.string.aimi_adv_support_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(app.aaps.core.ui.R.string.back),
                            )
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(horizontal = AapsSpacing.large)
                    .padding(bottom = AapsSpacing.xxLarge)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
            ) {
                Text(
                    text = stringResource(R.string.aimi_adv_support_msg),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.aimi_adv_support_code_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Button(
                    onClick = { onVerify() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.aimi_adv_support_verify))
                }
                if (unlocked) {
                    Text(
                        text = stringResource(R.string.aimi_adv_issue_msg),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = issue,
                        onValueChange = { issue = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.aimi_adv_issue_hint)) },
                        minLines = 3,
                    )
                    Button(
                        onClick = { onGenerate() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.aimi_adv_generate_btn))
                    }
                }
            }
        }
    }
}
