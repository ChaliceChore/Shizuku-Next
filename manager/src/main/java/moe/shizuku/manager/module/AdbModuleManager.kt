package moe.shizuku.manager.module

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.newShellProcess

/**
 * Everything the app does to a module: put one there, switch it on, run it, take it away.
 *
 * The running half goes through the shell this app already has for its terminal - the same Shizuku
 * process creation, the same server, the same uid - so a module runs exactly as a command typed into
 * that terminal does, with the environment the module format promises around it.
 */
object AdbModuleManager {

    private const val TAG = "AdbModuleManager"
    private const val ROOT_DIR = "adb_modules"

    /** The services started and not yet stopped, so a user cannot start the same one twice. */
    private val services = ConcurrentHashMap<String, Process>()

    /** Where a service's output is read to, for as long as it runs. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Where modules live.
     *
     * Not the app's private storage, which is the obvious place and does not work: a module's
     * scripts are run through Shizuku, which runs them as shell, and shell cannot enter an app's own
     * directory - the first `cd` into a module would fail with a permission error and the module
     * would look broken for no visible reason. The external files directory is the one place both
     * halves can reach: this app writes it as its owner, and the shell has it as well.
     *
     * The fallback is the private directory for a device that has no external storage at all, where
     * a module can be installed and read but its scripts cannot run.
     */
    fun modulesRoot(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, ROOT_DIR)

    suspend fun list(context: Context): List<AdbModule> =
        withContext(Dispatchers.IO) { installedModules(modulesRoot(context)) }

    /**
     * Installs the module in [uri].
     *
     * Throws [ModuleInstallException] for a zip that is not a module or is not one this app will
     * unpack - see [ModulePackage] - and the caller is expected to say which.
     */
    suspend fun install(context: Context, uri: Uri): AdbModule = withContext(Dispatchers.IO) {
        val stream: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw ModuleInstallException(ModuleInstallProblem.NOT_A_MODULE)

        stream.use { ModulePackage.install(modulesRoot(context), it) }
    }

    /** Switches a module on or off, which is the one marker file the format uses. */
    fun setEnabled(module: AdbModule, enabled: Boolean): Boolean = runCatching {
        val marker = File(module.directory, AdbModule.DISABLE_MARKER)

        if (enabled) {
            !marker.exists() || marker.delete()
        } else {
            marker.parentFile?.mkdirs()
            marker.exists() || marker.createNewFile()
        }
    }.getOrDefault(false)

    fun delete(module: AdbModule): Boolean = module.directory.deleteRecursively()

    fun isServiceRunning(module: AdbModule): Boolean =
        services[module.id]?.let { process ->
            // A process that has exited throws here, and a service that has exited is not running.
            runCatching { process.exitValue() }.isFailure
        } ?: false

    /**
     * Runs the module's action, asking [approve] about the command first.
     *
     * An action is a person pressing a button, so it is always allowed to run - what may not run is
     * a command the user has not seen, which is what the approval is for.
     */
    suspend fun runAction(
        module: AdbModule,
        approve: suspend (ModuleCommandRequest) -> Boolean,
    ): ModuleActionResult = runScript(
        module = module,
        script = module.actionScript,
        missing = "This module has no action script.",
        background = false,
        approve = approve,
        log = module.lastActionLog
    )

    /**
     * Starts the module's service, and leaves it running.
     *
     * What it prints goes to the module's service log rather than to a dialog: a service is the
     * part of a module that runs with nobody watching, and it is read afterwards.
     */
    suspend fun startService(
        module: AdbModule,
        approve: suspend (ModuleCommandRequest) -> Boolean,
    ): ModuleActionResult {
        if (isServiceRunning(module)) {
            return ModuleActionResult(0, "Already running.", "")
        }

        val mode = ModuleSettings.getAccessMode()
        if (!ModuleWebUi.canRunService(module, mode, ModuleSettings.allowsBackground())) {
            return ModuleActionResult(-1, "", "Services need Full access and background actions allowed.")
        }
        val script = module.serviceScript
            ?: return ModuleActionResult(-1, "", "This module has no service script.")
        if (!module.enabled) {
            return ModuleActionResult(-1, "", "The module is switched off.")
        }

        val command = moduleScriptCommand(module, script, mode, ModuleSettings.isTrusted(module.id), true)
        if (!approve(ModuleCommandRequest(module, ModuleCommandSource.SERVICE, command))) {
            return ModuleActionResult(-1, "", "The command was not approved.")
        }

        val process = newShellProcess(command)
            ?: return ModuleActionResult(-1, "", "Shizuku is not running.")

        services[module.id] = process
        Diag.info(TAG, "service started for ${module.id}")

        scope.launch {
            val output = StringBuilder()
            readCapped(process.inputStream, output)
            runCatching {
                module.lastServiceLog.parentFile?.mkdirs()
                module.lastServiceLog.writeText(output.toString())
            }
            services.remove(module.id)
            Diag.info(TAG, "service for ${module.id} exited")
        }

        return ModuleActionResult(0, "Started.", "")
    }

    fun stopService(module: AdbModule): Boolean {
        val process = services.remove(module.id) ?: return false

        return runCatching {
            process.destroy()
            true
        }.getOrDefault(false)
    }

    /**
     * A command a module's own WebUI asked for.
     *
     * It is a separate path from an action because it is a separate trust decision: an action runs
     * when a person presses a button, while a page can ask as often as it likes, so every command it
     * asks for is one the user is shown - unless they have marked the module trusted, which is what
     * that mark is for. What the page may ask at all is [ModuleWebUi.canUseShellBridge]'s business.
     */
    suspend fun runWebUiCommand(
        module: AdbModule,
        command: String,
        approve: suspend (ModuleCommandRequest) -> Boolean,
    ): ModuleActionResult {
        if (!ModuleWebUi.canUseShellBridge(module, ModuleSettings.getAccessMode())) {
            return ModuleActionResult(-1, "", "This module has no shell bridge.")
        }

        if (!ModuleSettings.isTrusted(module.id) &&
            !approve(ModuleCommandRequest(module, ModuleCommandSource.WEB_UI, command))
        ) {
            return ModuleActionResult(-1, "", "The command was not approved.")
        }

        return runCommand(command)
    }

    private suspend fun runScript(
        module: AdbModule,
        script: File?,
        missing: String,
        background: Boolean,
        approve: suspend (ModuleCommandRequest) -> Boolean,
        log: File,
    ): ModuleActionResult {
        if (script == null) return ModuleActionResult(-1, "", missing)
        if (!module.enabled) return ModuleActionResult(-1, "", "The module is switched off.")

        val mode = ModuleSettings.getAccessMode()
        val command = moduleScriptCommand(
            module = module,
            script = script,
            mode = mode,
            trusted = ModuleSettings.isTrusted(module.id),
            background = background
        )

        if (!approve(ModuleCommandRequest(module, ModuleCommandSource.ACTION, command))) {
            return ModuleActionResult(-1, "", "The command was not approved.")
        }

        val result = runCommand(command)

        runCatching {
            log.parentFile?.mkdirs()
            log.writeText(result.combinedOutput)
        }

        return result
    }

    /** Runs [command] as shell, with the output capped and the clock running. */
    private suspend fun runCommand(command: String): ModuleActionResult = withContext(Dispatchers.IO) {
        val process = newShellProcess(command)
            ?: return@withContext ModuleActionResult(-1, "", "Shizuku is not running.")

        val stdout = StringBuilder()
        val stderr = StringBuilder()

        coroutineScope {
            // Both streams are read on their own coroutine: a command that writes a lot would
            // otherwise block on a full pipe while the other stream is being read.
            val readers = listOf(
                launch { readCapped(process.inputStream, stdout) },
                launch { readCapped(process.errorStream, stderr) }
            )

            val exitCode = awaitExit(process, ModuleRunLimits.SCRIPT_TIMEOUT_SECONDS * 1000L)
            if (exitCode == null) {
                // Out of time: the process is killed and the reader coroutines end with their
                // streams. The exit code is the one the format uses for this, so a module can tell
                // the difference between a failure and running too long.
                runCatching { process.destroy() }
                readers.joinAll()
                ModuleActionResult(ModuleRunLimits.TIMEOUT_EXIT_CODE, stdout.toString(), stderr.toString())
            } else {
                readers.joinAll()
                ModuleActionResult(exitCode, stdout.toString(), stderr.toString())
            }
        }
    }

    /** The process's exit code, or null when it is still running when the time is up. */
    private suspend fun awaitExit(process: Process, timeoutMillis: Long): Int? {
        val deadline = System.currentTimeMillis() + timeoutMillis

        while (System.currentTimeMillis() < deadline) {
            val exit = runCatching { process.exitValue() }.getOrNull()
            if (exit != null) return exit

            delay(100)
        }

        return null
    }

    /**
     * Reads a process's stream into [sink], keeping only what fits.
     *
     * A module that prints in a loop would otherwise fill the manager's heap with its log: the
     * format gives a stream a cap, and this is where it is applied.
     */
    private fun readCapped(stream: InputStream, sink: StringBuilder) {
        runCatching {
            val buffer = ByteArray(8 * 1024)
            var total = 0

            while (total < ModuleRunLimits.MAX_OUTPUT_BYTES) {
                val read = stream.read(buffer)
                if (read <= 0) break

                val room = ModuleRunLimits.MAX_OUTPUT_BYTES - total
                sink.append(String(buffer, 0, minOf(read, room), Charsets.UTF_8))
                total += read
            }
        }
    }
}
