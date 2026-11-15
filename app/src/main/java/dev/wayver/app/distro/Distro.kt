package dev.wayver.app.distro

import android.content.Context
import java.io.File

data class PrepareContext(
    val context: Context,
    val rootfsDir: File,
    val runInProot: (cmd: String) -> Int
)

data class DesktopSpec(
    val id: String,
    val name: String,
    val launch: String,
    val prepare: (PrepareContext) -> Boolean = { true }
)

interface Distro {
    val id: String
    val name: String
    val iconRes: Int

    val rootfsUrl: String
    val markerRelPath: String
    val shellPath: String
    val pathEnv: String
        get() = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    val extraEnv: Map<String, String>
        get() = emptyMap()

    /** Per-app environment overrides installed as `/usr/local/bin/<app>` wrapper shims. */
    val appEnvs: Map<String, Map<String, String>>
        get() = emptyMap()
    val desktops: List<DesktopSpec>

    fun desktopById(desktopId: String?): DesktopSpec = desktops.firstOrNull { it.id == desktopId } ?: desktops.first()

    fun prepareRootfs(rootfsDir: File) {}
}
