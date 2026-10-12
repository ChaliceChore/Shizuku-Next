package moe.shizuku.manager.ui.screen

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.automirrored.outlined.Wysiwyg
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
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

/**
 * The modules this app has installed.
 *
 * The cards are KernelSU-Next's: a module's own banner faintly behind it, its name in semibold with
 * its version under it, the whole card opening to show what can be done with it, and the actions as
 * tonal buttons with their icons. That shape is worth copying because a module is a thing with a
 * picture and a paragraph and one or two buttons, which is exactly what it draws well - and this
 * app has no wish to invent a second way of showing one.
 *
 * Where it parts company is the two switches at the top: KernelSU runs everything as root and has
 * nothing to ask, while here a module's scripts run as shell through Shizuku and what a module may
 * do is the user's decision. Nothing runs that has not been shown first either.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModulesScreen(bottomPadding: Dp = 0.dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var modules by remember { mutableStateOf<List<AdbModule>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var webUiFor by remember { mutableStateOf<AdbModule?>(null) }
    var catalogOpen by remember { mutableStateOf(false) }

    var fullAccess by remember { mutableStateOf(ModuleSettings.getAccessMode() == ModuleAccessMode.FULL) }
    var backgroundAllowed by remember { mutableStateOf(ModuleSettings.allowsBackground()) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }

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

    // Both of these take the screen rather than opening over it: a page and a list are not dialogs.
    webUiFor?.let { module ->
        ModuleWebUiScreen(module = module, onBack = { webUiFor = null })
        return
    }

    if (catalogOpen) {
        ModulesCatalogScreen(
            installedIds = modules.map { it.id }.toSet(),
            onBack = { catalogOpen = false },
            onInstalled = {
                catalogOpen = false
                reload()
            }
        )
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
            actions = {
                IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.modules_install))
                }
                IconButton(onClick = { catalogOpen = true }) {
                    Icon(Icons.Filled.Cloud, contentDescription = stringResource(R.string.modules_catalog))
                }
            }
        )

        if (busy) {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = stringResource(R.string.modules_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.modules_access_full),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = stringResource(R.string.modules_access_full_summary),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = fullAccess,
                                onCheckedChange = { checked ->
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

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.modules_allow_background),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = stringResource(R.string.modules_allow_background_summary),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = backgroundAllowed,
                                enabled = fullAccess,
                                onCheckedChange = { checked ->
                                    backgroundAllowed = checked
                                    ModuleSettings.setAllowsBackground(checked)
                                }
                            )
                        }
                    }
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
            }

            modules.forEach { module ->
                item {
                    ModuleCard(
                        module = module,
                        expanded = module.id in expanded,
                        serviceRunning = module.id in running,
                        onExpandToggle = {
                            expanded = if (module.id in expanded) {
                                expanded - module.id
                            } else {
                                expanded + module.id
                            }
                        },
                        onEnabledChange = { checked ->
                            AdbModuleManager.setEnabled(module, checked)
                            reload()
                        },
                        onRunAction = {
                            scope.launch {
                                busy = true
                                val action = AdbModuleManager.runAction(module, approve)
                                busy = false
                                resultTitle = module.name
                                result = action
                                reload()
                            }
                        },
                        onToggleService = {
                            if (module.id in running) {
                                AdbModuleManager.stopService(module)
                                running = running - module.id
                            } else {
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
                            }
                        },
                        onOpenWebUi = { webUiFor = module },
                        onDelete = { pendingDelete = module }
                    )
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

/**
 * One module, drawn the way KernelSU-Next draws one.
 *
 * The banner is behind the card's content at 18 percent and faded into the surface towards the
 * bottom, which is the detail that makes this look like their screen rather than like a list of
 * text: a module that has a picture shows it, and one that has none is a plain card.
 */
@Composable
private fun ModuleCard(
    module: AdbModule,
    expanded: Boolean,
    serviceRunning: Boolean,
    onExpandToggle: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onRunAction: () -> Unit,
    onToggleService: () -> Unit,
    onOpenWebUi: () -> Unit,
    onDelete: () -> Unit,
) {
    var bitmap by remember(module.banner) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(module.banner) {
        val banner = module.banner ?: return@LaunchedEffect

        bitmap = runCatching {
            BitmapFactory.decodeFile(banner.absolutePath)?.asImageBitmap()
        }.getOrNull()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onExpandToggle)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            bitmap?.let { image ->
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentScale = ContentScale.Crop,
                    alpha = 0.18f
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                                )
                            )
                        )
                )
            }

            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = module.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(
                                R.string.modules_card_line,
                                module.prop.version ?: "-",
                                module.prop.author ?: "-"
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Icon(
                        imageVector = if (expanded) {
                            Icons.Filled.KeyboardArrowUp
                        } else {
                            Icons.Filled.KeyboardArrowDown
                        },
                        contentDescription = null
                    )

                    Switch(
                        checked = module.enabled,
                        onCheckedChange = onEnabledChange,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                if (expanded) {
                    Text(
                        text = module.prop.id,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 10.dp)
                    )

                    module.prop.description?.takeIf { it.isNotEmpty() }?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (module.hasAction) {
                            ModuleButton(
                                icon = Icons.Outlined.PlayArrow,
                                label = stringResource(R.string.modules_action_run),
                                onClick = onRunAction
                            )
                        }

                        if (module.hasService) {
                            ModuleButton(
                                icon = Icons.Outlined.Stop,
                                label = stringResource(
                                    if (serviceRunning) {
                                        R.string.modules_service_stop
                                    } else {
                                        R.string.modules_service_start
                                    }
                                ),
                                onClick = onToggleService
                            )
                        }

                        if (module.hasWebUi) {
                            ModuleButton(
                                icon = Icons.AutoMirrored.Outlined.Wysiwyg,
                                label = stringResource(R.string.modules_web_ui),
                                onClick = onOpenWebUi
                            )
                        }

                        ModuleButton(
                            icon = Icons.Outlined.Delete,
                            label = stringResource(R.string.modules_delete),
                            onClick = onDelete
                        )
                    }
                }
            }
        }
    }
}

/** One of a card's actions: their shape, an icon and a word. */
@Composable
private fun ModuleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = ButtonDefaults.TextButtonContentPadding
    ) {
        Icon(modifier = Modifier.size(20.dp), imageVector = icon, contentDescription = null)
        Text(
            modifier = Modifier.padding(start = 8.dp),
            text = label,
            style = MaterialTheme.typography.labelLarge
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
