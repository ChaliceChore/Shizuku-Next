package moe.shizuku.manager.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.module.AdbModule
import moe.shizuku.manager.module.AdbModuleManager
import moe.shizuku.manager.module.ModuleCommandRequest
import moe.shizuku.manager.module.ModuleSettings

/**
 * A module's action, on its own screen.
 *
 * Their screen, and their reason for having one: an action is something a person sits and watches,
 * and what it printed is what they came for - so the output stays on the screen instead of leaving
 * with a dialog, and running it again is one button in the bar.
 *
 * The command still goes through the review first, which is this fork's own rule and not theirs:
 * an action here runs as shell through Shizuku rather than as root.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleActionScreen(module: AdbModule, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    var output by remember { mutableStateOf("") }
    var exitCode by remember { mutableStateOf<Int?>(null) }
    var running by remember { mutableStateOf(false) }
    var pendingCommand by remember { mutableStateOf<ModuleCommandRequest?>(null) }
    var approval by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

    val approve: suspend (ModuleCommandRequest) -> Boolean = { request ->
        if (ModuleSettings.isTrusted(module.id)) {
            true
        } else {
            val answer = CompletableDeferred<Boolean>()
            approval = answer
            pendingCommand = request
            answer.await()
        }
    }

    fun run() {
        scope.launch {
            running = true
            output = ""
            exitCode = null

            val result = AdbModuleManager.runAction(module, approve)
            output = result.combinedOutput
            exitCode = result.exitCode
            running = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(module.name) },
            windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            actions = {
                IconButton(onClick = { if (!running) run() }, enabled = !running) {
                    Icon(
                        imageVector = if (running) {
                            Icons.Outlined.HourglassEmpty
                        } else {
                            Icons.Outlined.PlayArrow
                        },
                        contentDescription = stringResource(
                            if (running) R.string.modules_action_running else R.string.modules_action_run
                        )
                    )
                }
            }
        )

        if (running) {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        exitCode?.let { code ->
            Text(
                text = stringResource(R.string.modules_result_exit, code),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)
            )
        }

        // Their console: what the script printed, in the font it printed in.
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.large
        ) {
            Text(
                text = output,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp)
            )
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
}
