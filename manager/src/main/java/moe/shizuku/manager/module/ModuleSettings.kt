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

    /**
     * The order the module list is shown in, under the same keys their screen keeps it in, so the
     * names mean the same thing to anybody who has seen both.
     */
    const val SORT_A_TO_Z = "module_sort_a_to_z"
    const val SORT_Z_TO_A = "module_sort_z_to_a"
    const val SORT_SIZE_LOW_TO_HIGH = "module_sort_size_low_to_high"
    const val SORT_SIZE_HIGH_TO_LOW = "module_sort_size_high_to_low"
    const val SORT_ENABLED_FIRST = "module_sort_enabled_first"
    const val SORT_ACTION_FIRST = "module_sort_action_first"
    const val SORT_WEBUI_FIRST = "module_sort_webui_first"

    fun sortFlag(key: String, default: Boolean = false): Boolean =
        ShizukuSettings.getPreferences().getBoolean(key, default)

    fun setSortFlag(key: String, value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(key, value).apply()
    }
}
