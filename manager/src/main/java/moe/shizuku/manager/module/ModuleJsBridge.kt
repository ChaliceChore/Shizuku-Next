package moe.shizuku.manager.module

import android.webkit.JavascriptInterface
import kotlinx.coroutines.runBlocking

/** Where a command a module's page asked for came from. */
enum class ModuleCommandSource {
    WEB_UI,
    ACTION,
    SERVICE
}

/** A command about to run, for the user to look at first. */
data class ModuleCommandRequest(
    val module: AdbModule,
    val source: ModuleCommandSource,
    val command: String
)

/**
 * The `window.Shizuku` object a module's WebUI talks to.
 *
 * A module's page runs inside this app, so this object is the whole of what it can reach: the
 * information the format says it gets, and - only when the module asked for a shell bridge, access
 * is Full, and the module is switched on - a way to run a command. Every answer is JSON, in the
 * shape the format documents, so a page written for any implementation of it works here.
 *
 * The WebView that owns this object is created with internet access switched off: a page with a
 * bridge into a shell does not also get to talk to a network this app cannot see.
 */
class ModuleJsBridge(
    private val module: AdbModule,
    private val runCommand: suspend (String) -> ModuleActionResult,
) {

    @JavascriptInterface
    fun getModuleInfo(): String =
        ModuleWebUi.moduleInfoJson(
            module = module,
            mode = ModuleSettings.getAccessMode(),
            trusted = ModuleSettings.isTrusted(module.id)
        )

    @JavascriptInterface
    fun exec(command: String): String = runCommandJson(command)

    /**
     * The same, with the options object the format defines.
     *
     * Only a timeout is implemented. The others - stdin, a working directory and extra environment
     * variables - would each be a way to make the command something other than what the user
     * approved, and a bridge that quietly ignores an option is worse than one that says it cannot:
     * a page asking for them gets a refusal naming the option.
     */
    @JavascriptInterface
    fun execWithOptions(command: String, optionsJson: String?): String {
        val unsupported = unsupportedOption(optionsJson)
        if (unsupported != null) {
            return ModuleWebUi.refusedJson("This bridge does not support the $unsupported option.")
        }

        return runCommandJson(command)
    }

    private fun runCommandJson(command: String): String {
        val mode = ModuleSettings.getAccessMode()
        if (!ModuleWebUi.canUseShellBridge(module, mode)) {
            return ModuleWebUi.refusedJson("This module has no shell bridge.")
        }

        val result = runBlocking { runCommand(command) }

        return ModuleWebUi.execResultJson(result, result.exitCode == ModuleRunLimits.TIMEOUT_EXIT_CODE)
    }
}

/**
 * The first option in [optionsJson] this bridge does not implement, or null when there is none.
 *
 * The check is a search rather than a parse on purpose: the answer to a missing option is the same
 * whatever else the object holds, and a malformed one is the page's to fix.
 */
internal fun unsupportedOption(optionsJson: String?): String? {
    val json = optionsJson?.takeIf { it.isNotBlank() } ?: return null

    return listOf("stdin", "cwd", "env", "envVars", "workingDirectory")
        .firstOrNull { name -> Regex("\"\\s*$name\\s*\"").containsMatchIn(json) }
}
