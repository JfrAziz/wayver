package dev.wayver.app.core

import android.content.Context

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("linux_desktop_prefs", Context.MODE_PRIVATE)

    var activeDistroId: String
        get() = prefs.getString("active_distro_id", "alpine") ?: "alpine"
        set(value) = prefs.edit().putString("active_distro_id", value).apply()

    var themeMode: String
        get() = prefs.getString("theme_mode", "auto") ?: "auto"
        set(value) = prefs.edit().putString("theme_mode", value).apply()
}
