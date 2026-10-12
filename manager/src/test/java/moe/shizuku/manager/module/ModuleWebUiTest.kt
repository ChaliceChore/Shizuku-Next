package moe.shizuku.manager.module

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What a module's WebUI may do, and what it is told.
 *
 * A module's page has a bridge back into an app that runs commands as shell, so the rules have to
 * hold on their own: the shell bridge only when the module asked for one, access is Full and the
 * module is on, and a service only under Full access with background actions allowed. The answers
 * are the format's, read back here as JSON rather than compared as strings, so a page parsing them
 * gets what the documentation says it will.
 */
class ModuleWebUiTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A module on disk, made afresh each call so a test can ask for more than one. */
    private fun module(
        id: String = "my-module",
        bridge: Boolean = true,
        enabled: Boolean = true
    ): AdbModule {
        val dir = File(folder.root, id).apply { mkdirs() }
        File(dir, ModuleProp.FILE_NAME)
            .writeText("id=$id\nname=My Module\nusesShellBridge=$bridge\n")

        val marker = File(dir, AdbModule.DISABLE_MARKER)
        if (enabled) marker.delete() else marker.writeText("")

        return AdbModule(ModuleProp.parse(File(dir, ModuleProp.FILE_NAME).readText())!!, dir)
    }

    private fun field(json: String, name: String): String? =
        (Json.parseToJsonElement(json) as? JsonObject)?.get(name)?.jsonPrimitive?.content

    @Test
    fun `the shell bridge needs the module to ask, full access, and the module on`() {
        assertTrue(ModuleWebUi.canUseShellBridge(module(), ModuleAccessMode.FULL))
        assertFalse(ModuleWebUi.canUseShellBridge(module(enabled = false), ModuleAccessMode.FULL))

        assertFalse(ModuleWebUi.canUseShellBridge(module(), ModuleAccessMode.SAFE))
        assertFalse(ModuleWebUi.canUseShellBridge(module(bridge = false), ModuleAccessMode.FULL))
        assertFalse(ModuleWebUi.canUseShellBridge(module(bridge = false, enabled = false), ModuleAccessMode.FULL))
    }

    @Test
    fun `a service needs full access, background allowed, and the module on`() {
        assertTrue(ModuleWebUi.canRunService(module(), ModuleAccessMode.FULL, true))

        assertFalse(ModuleWebUi.canRunService(module(), ModuleAccessMode.SAFE, true))
        assertFalse(ModuleWebUi.canRunService(module(), ModuleAccessMode.FULL, false))
        assertFalse(ModuleWebUi.canRunService(module(enabled = false), ModuleAccessMode.FULL, true))
    }

    @Test
    fun `the info object says who the module is and what it is allowed`() {
        val info = ModuleWebUi.moduleInfoJson(module(), ModuleAccessMode.FULL, trusted = true)

        assertEquals("my-module", field(info, "id"))
        assertEquals("My Module", field(info, "name"))
        assertEquals("true", field(info, "enabled"))
        assertEquals("full", field(info, "accessMode"))
        assertEquals("true", field(info, "trusted"))
        assertEquals("true", field(info, "shellBridge"))

        // Everything a page needs to find its own files, as the format documents it.
        assertEquals(module().name, field(info, "name"))
        assertTrue(field(info, "moduleDir")!!.endsWith("my-module"))
    }

    @Test
    fun `an exec result is ok only when it exited zero in time`() {
        val ok = ModuleWebUi.execResultJson(ModuleActionResult(0, "hello", ""), timedOut = false)
        assertEquals("true", field(ok, "ok"))
        assertEquals("0", field(ok, "exitCode"))
        assertEquals("hello", field(ok, "stdout"))
        assertEquals("false", field(ok, "timedOut"))

        val failed = ModuleWebUi.execResultJson(ModuleActionResult(1, "", "no"), timedOut = false)
        assertEquals("false", field(failed, "ok"))

        // Out of time is not success, whatever it exited with, and it says so separately.
        val timedOut = ModuleWebUi.execResultJson(
            ModuleActionResult(ModuleRunLimits.TIMEOUT_EXIT_CODE, "", ""),
            timedOut = true
        )
        assertEquals("false", field(timedOut, "ok"))
        assertEquals("true", field(timedOut, "timedOut"))
    }

    @Test
    fun `a refused command says why instead of pretending to have run`() {
        val refused = ModuleWebUi.refusedJson("This module has no shell bridge.")

        assertEquals("false", field(refused, "ok"))
        assertEquals("-1", field(refused, "exitCode"))
        assertEquals("This module has no shell bridge.", field(refused, "stderr"))
    }

    @Test
    fun `the result is JSON a page can parse, not a string it has to`() {
        val element = Json.parseToJsonElement(
            ModuleWebUi.execResultJson(ModuleActionResult(0, "out", "err"), timedOut = false)
        ) as JsonObject

        assertTrue((element["ok"] as JsonPrimitive).boolean)
        assertEquals(0, (element["exitCode"] as JsonPrimitive).int)
        assertTrue((element["timedOut"] as JsonPrimitive).content.toBoolean().not())
    }

    @Test
    fun `options this bridge cannot honour are refused by name`() {
        assertNull(unsupportedOption(null))
        assertNull(unsupportedOption(""))
        assertNull(unsupportedOption("{}"))
        assertNull(unsupportedOption("""{"timeoutSeconds":30}"""))

        assertEquals("stdin", unsupportedOption("""{"stdin":"hello\n"}"""))
        assertEquals("cwd", unsupportedOption("""{"cwd":"webui"}"""))
        assertEquals("env", unsupportedOption("""{"env":{"FOO":"bar"}}"""))
    }
}
