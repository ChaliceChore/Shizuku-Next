package moe.shizuku.manager.module

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What a module's WebUI is allowed to do, and what it is told.
 *
 * A module's WebUI is a page inside this app with a bridge back into it, so the rules about what
 * that bridge may do are stated here, away from the WebView, where they can be read and tested on
 * their own. Both of them start from a module being switched on: a page from a module the user
 * turned off has no business running commands.
 */
object ModuleWebUi {

    /**
     * Whether [module] may be handed the shell bridge.
     *
     * The module has to ask for it in its `module.prop` and access has to be Full. A module that did
     * not ask gets the info object and nothing else, which is what a module's page needs to draw
     * itself and no more.
     */
    fun canUseShellBridge(module: AdbModule, mode: ModuleAccessMode): Boolean =
        module.enabled && module.prop.usesShellBridge && mode == ModuleAccessMode.FULL

    /**
     * Whether [module]'s service script may be started.
     *
     * Safe access never runs services, whatever else is switched on, and Full access runs one only
     * when the user has also allowed background actions: a service is the part of a module that runs
     * without anybody watching it.
     */
    fun canRunService(
        module: AdbModule,
        mode: ModuleAccessMode,
        backgroundAllowed: Boolean,
    ): Boolean = module.enabled && mode == ModuleAccessMode.FULL && backgroundAllowed

    /** What `window.Shizuku.getModuleInfo()` answers. */
    fun moduleInfoJson(module: AdbModule, mode: ModuleAccessMode, trusted: Boolean): String =
        buildJsonObject {
            put("id", module.id)
            put("name", module.name)
            put("version", module.prop.version)
            put("versionCode", module.prop.versionCode)
            put("author", module.prop.author)
            put("description", module.prop.description)
            put("enabled", module.enabled)
            put("accessMode", mode.value)
            put("trusted", trusted)
            put("moduleDir", module.directory.absolutePath)
            put("shellBridge", canUseShellBridge(module, mode))
        }.toString()

    /**
     * What `window.Shizuku.exec()` answers: the run's result, in the shape the format documents.
     *
     * `ok` is the whole of it for most callers - a command that exited zero and did not run out of
     * time is one that worked - so it is computed here rather than left to every page to work out
     * from the exit code.
     */
    fun execResultJson(result: ModuleActionResult, timedOut: Boolean): String =
        buildJsonObject {
            put("ok", result.exitCode == 0 && !timedOut)
            put("exitCode", result.exitCode)
            put("stdout", result.stdout)
            put("stderr", result.stderr)
            put("timedOut", timedOut)
        }.toString()

    /** The result for a command that was refused before it ran. */
    fun refusedJson(reason: String): String =
        buildJsonObject {
            put("ok", false)
            put("exitCode", -1)
            put("stdout", "")
            put("stderr", reason)
            put("timedOut", false)
        }.toString()
}
