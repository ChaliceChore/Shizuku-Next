package moe.shizuku.manager.ui.screen

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import moe.shizuku.manager.module.AdbModule
import moe.shizuku.manager.module.AdbModuleManager
import moe.shizuku.manager.module.ModuleCommandRequest
import moe.shizuku.manager.module.ModuleJsBridge
import moe.shizuku.manager.module.ModuleProp
import moe.shizuku.manager.module.ModuleSettings

/**
 * A module's own page, running inside this app.
 *
 * Two things about this WebView are the point of it. It has no internet - a page with a bridge back
 * into Shizuku does not also get a network this app cannot see - and its bridge is the whole of what
 * the page can reach: the information the format says it gets, and commands that a person approves
 * unless they have marked that module trusted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleWebUiScreen(module: AdbModule, onBack: () -> Unit) {
    val webRoot = module.webRoot ?: return

    var pendingCommand by remember { mutableStateOf<ModuleCommandRequest?>(null) }
    var approval by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

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

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(module.name) },
            windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }
        )

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.blockNetworkLoads = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = false

                    webViewClient = WebViewClient()

                    addJavascriptInterface(
                        ModuleJsBridge(module) { command ->
                            AdbModuleManager.runWebUiCommand(module, command, approve)
                        },
                        "Shizuku"
                    )

                    loadUrl("file://${File(webRoot, ModuleProp.WEB_UI_ENTRY).absolutePath}")
                }
            }
        )
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
