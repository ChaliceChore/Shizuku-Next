package moe.shizuku.manager.ui.screen

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.module.AdbModule
import moe.shizuku.manager.module.AdbModuleManager
import moe.shizuku.manager.module.ModuleAccessMode
import moe.shizuku.manager.module.ModuleActionResult
import moe.shizuku.manager.module.ModuleCommandRequest
import moe.shizuku.manager.module.ModuleInstallException
import moe.shizuku.manager.module.ModuleInstallProblem
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.ui.component.PillButton
import moe.shizuku.manager.ui.component.PillButtonQuiet
import moe.shizuku.manager.ui.component.SegmentedColumn
import moe.shizuku.manager.ui.component.SegmentedListItem

/**
 * The modules this app has installed, and what can be done with them.
 *
 * Everything on this screen is an offer to run somebody else's shell script as Shizuku's user, so it
 * is laid out to say so: the two switches that decide how much a module may do come first with what
 * they mean written underneath, a module's own commands are shown before they run, and the output
 * comes back afterwards rather than disappearing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModulesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var modules by remember { mutableStateOf<List<AdbModule>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var webUiFor by remember { mutableStateOf<AdbModule?>(null) }

    var fullAccess by remember { mutableStateOf(ModuleSettings.getAccessMode() == ModuleAccessMode.FULL) }
    var backgroundAllowed by remember { mutableStateOf(ModuleSettings.allowsBackground()) }

    var pendingCommand by remember { mutableStateOf<ModuleCommandRequest?>(null) }
    var approval by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

    var result by remember { mutableStateOf<ModuleActionResult?>(null) }
    var resultTitle by remember { mutableStateOf("") }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var pendingDelete by remember { mutableStateOf<AdbModule?>(null) }
    var running by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun reload() {
        scope.launch { modules = AdbModuleManager.list(context) }
    }

    /**
     * What every command goes through first.
     *
     * A module the user has marked trusted runs without being asked again; anything else waits here
     * for the dialog, which is the only place a command is ever approved.
     */
    val approve: suspend (ModuleCommandRequest) -> Boolean = { request ->
        if (ModuleSettings.isTrusted(request.module.id)) {
            true
        } else {
            val answer = CompletableDeferred<Boolean>()
            approval = answer
            pendingCommand = request
            answer.await()
        }
    }

    LaunchedEffect(Unit) { reload() }

    // The WebUI takes over the screen rather than opening on top of it: a page is not a dialog, and
    // its bridge lives exactly as long as this screen does.
    webUiFor?.let { module ->
        ModuleWebUiScreen(module = module, onBack = { webUiFor = null })
        return
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        scope.launch {
            busy = true
            runCatching { AdbModuleManager.install(context, uri) }
                .onFailure { failure ->
                    errorRes = (failure as? ModuleInstallException)
                        ?.let { installProblem(it.problem) }
                        ?: R.string.modules_error_install_failed
                }
                .onSuccess { installed ->
                    Toast.makeText(
                        context,
                        context.getString(R.string.modules_installed, installed.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            busy = false
            reload()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tools_modules)) },
            windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }
        )

        if (busy) {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = stringResource(R.string.modules_warning),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            item {
                SegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            headlineContent = { Text(stringResource(R.string.modules_access_full)) },
                            supportingContent = {
                                Text(stringResource(R.string.modules_access_full_summary))
                            },
                            switchState = fullAccess,
                            onSwitchChange = { checked ->
                                fullAccess = checked
                                ModuleSettings.setAccessMode(
                                    if (checked) ModuleAccessMode.FULL else ModuleAccessMode.SAFE
                                )
                                // Background actions need Full access, so turning it off turns
                                // them off with it rather than leaving a switch on that does
                                // nothing.
                                if (!checked && backgroundAllowed) {
                                    backgroundAllowed = false
                                    ModuleSettings.setAllowsBackground(false)
                                }
                            }
                        )
                    }
                    item {
                        SegmentedListItem(
                            centerSlots = true,
                            headlineContent = { Text(stringResource(R.string.modules_allow_background)) },
                            supportingContent = {
                                Text(stringResource(R.string.modules_allow_background_summary))
                            },
                            switchState = backgroundAllowed,
                            switchEnabled = fullAccess,
                            onSwitchChange = { checked ->
                                backgroundAllowed = checked
                                ModuleSettings.setAllowsBackground(checked)
                            }
                        )
                    }
                }
            }

            item {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { picker.launch(arrayOf("*/*")) }
                ) {
                    Text(stringResource(R.string.modules_install))
                }
            }

            if (modules.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.modules_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                item {
                    SegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                        modules.forEach { module ->
                            item {
                                SegmentedListItem(
                                    centerSlots = true,
                                    headlineContent = { Text(module.name) },
                                    supportingContent = {
                                        Text(
                                            stringResource(
                                                R.string.modules_row_summary,
                                                module.prop.version ?: "-",
                                                module.id
                                            )
                                        )
                                    },
                                    switchState = module.enabled,
                                    onSwitchChange = { checked ->
                                        AdbModuleManager.setEnabled(module, checked)
                                        reload()
                                    }
                                )
                            }

                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (module.hasAction) {
                                        PillButtonQuiet(onClick = {
                                            scope.launch {
                                                busy = true
                                                val action = AdbModuleManager.runAction(module, approve)
                                                busy = false
                                                resultTitle = module.name
                                                result = action
                                                reload()
                                            }
                                        }) { Text(stringResource(R.string.modules_action_run)) }
                                    }

                                    if (module.hasService) {
                                        if (running.contains(module.id)) {
                                            PillButtonQuiet(onClick = {
                                                AdbModuleManager.stopService(module)
                                                running = running - module.id
                                            }) { Text(stringResource(R.string.modules_service_stop)) }
                                        } else {
                                            PillButtonQuiet(onClick = {
                                                scope.launch {
                                                    busy = true
                                                    val started = AdbModuleManager.startService(module, approve)
                                                    busy = false
                                                    if (started.exitCode == 0) {
                                                        running = running + module.id
                                                    } else {
                                                        resultTitle = module.name
                                                        result = started
                                                    }
                                                }
                                            }) { Text(stringResource(R.string.modules_service_start)) }
                                        }
                                    }

                                    if (module.hasWebUi) {
                                        PillButtonQuiet(onClick = { webUiFor = module }) {
                                            Text(stringResource(R.string.modules_web_ui))
                                        }
                                    }

                                    PillButtonQuiet(onClick = { pendingDelete = module }) {
                                        Text(stringResource(R.string.modules_delete))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingCommand?.let { request ->
        CommandReviewDialog(
            request = request,
            onApprove = {
                pendingCommand = null
                approval?.complete(true)
                approval = null
            },
            onReject = {
                pendingCommand = null
                approval?.complete(false)
                approval = null
            }
        )
    }

    result?.let { actionResult ->
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text(stringResource(R.string.modules_result_title, resultTitle)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.modules_result_exit, actionResult.exitCode),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = actionResult.combinedOutput,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                PillButton(onClick = { result = null }) { Text(stringResource(android.R.string.ok)) }
            }
        )
    }

    pendingDelete?.let { module ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.modules_delete_confirm, module.name)) },
            text = { Text(stringResource(R.string.modules_delete_confirm_message)) },
            confirmButton = {
                PillButton(onClick = {
                    pendingDelete = null
                    running = running - module.id
                    AdbModuleManager.delete(module)
                    reload()
                }) { Text(stringResource(R.string.modules_delete)) }
            },
            dismissButton = {
                PillButtonQuiet(onClick = { pendingDelete = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    errorRes?.let { messageRes ->
        AlertDialog(
            onDismissRequest = { errorRes = null },
            title = { Text(stringResource(R.string.error)) },
            text = { Text(stringResource(messageRes)) },
            confirmButton = {
                PillButton(onClick = { errorRes = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}

/** Why a zip was refused, said in the terms the user can act on. */
private fun installProblem(problem: ModuleInstallProblem): Int = when (problem) {
    ModuleInstallProblem.NOT_A_MODULE -> R.string.modules_error_not_a_module
    ModuleInstallProblem.UNSAFE_PATH -> R.string.modules_error_unsafe_path
    ModuleInstallProblem.TOO_MANY_ENTRIES -> R.string.modules_error_too_many_entries
    ModuleInstallProblem.TOO_LARGE -> R.string.modules_error_too_large
    ModuleInstallProblem.SCRIPT_TOO_LARGE -> R.string.modules_error_script_too_large
}

/**
 * The command a module is about to run, shown before it runs.
 *
 * This is the whole of the review: the command as it will be handed to the shell, in the font a
 * command is read in, with no summary in between - a summary is what a person would nod at.
 */
@Composable
fun CommandReviewDialog(
    request: ModuleCommandRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(stringResource(R.string.modules_review_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.modules_review_message, request.module.name),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = request.command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            PillButton(onClick = onApprove) {
                Text(stringResource(R.string.modules_review_approve))
            }
        },
        dismissButton = {
            PillButtonQuiet(onClick = onReject) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

/** The icon the settings row for this screen uses. */
val ModulesIcon = Icons.Outlined.Extension
