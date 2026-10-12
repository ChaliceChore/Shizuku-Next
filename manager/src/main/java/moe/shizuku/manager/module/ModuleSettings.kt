package moe.shizuku.manager.module

import moe.shizuku.manager.ShizukuSettings

/**
 * What modules are allowed to do, as the user set it.
 *
 * Two switches and a per-module mark of trust, all kept where every other setting of this app is
 * kept. They default to the cautious end: safe access, no background actions, no module trusted.
 */
object ModuleSettings {

    private const val KEY_ACCESS_MODE = "module_access_mode"
    private const val KEY_BACKGROUND = "module_allow_background"
    private const val KEY_TRUSTED_PREFIX = "module_trusted_"

    fun getAccessMode(): ModuleAccessMode =
        ModuleAccessMode.fromValue(ShizukuSettings.getPreferences().getString(KEY_ACCESS_MODE, null))

    fun setAccessMode(mode: ModuleAccessMode) {
        ShizukuSettings.getPreferences().edit().putString(KEY_ACCESS_MODE, mode.value).apply()
    }

    fun allowsBackground(): Boolean =
        ShizukuSettings.getPreferences().getBoolean(KEY_BACKGROUND, false)

    fun setAllowsBackground(allowed: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_BACKGROUND, allowed).apply()
    }

    fun isTrusted(moduleId: String): Boolean =
        ShizukuSettings.getPreferences().getBoolean(KEY_TRUSTED_PREFIX + moduleId, false)

    fun setTrusted(moduleId: String, trusted: Boolean) {
        ShizukuSettings.getPreferences().edit()
            .putBoolean(KEY_TRUSTED_PREFIX + moduleId, trusted)
            .apply()
    }
}
