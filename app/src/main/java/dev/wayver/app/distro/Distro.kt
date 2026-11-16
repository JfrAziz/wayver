package dev.wayver.app.distro

import android.content.Context
import dev.wayver.app.utils.HttpDownloader
import dev.wayver.app.utils.TarExtractor
import java.io.File
import java.io.InputStream

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

    val relPath: String
    val shellPath: String

    // Per-distro environment ovverrides
    val extraEnv: Map<String, String>
        get() = emptyMap()

    /** Per-app environment overrides installed as `/usr/local/bin/<app>` wrapper shims. */
    val appEnvs: Map<String, Map<String, String>>
        get() = emptyMap()

    val desktops: List<DesktopSpec>

    fun desktopById(desktopId: String?): DesktopSpec = desktops.firstOrNull { it.id == desktopId } ?: desktops.first()

    fun downloadRootfs(destFile: File, onProgress: (String) -> Unit): Boolean =
        HttpDownloader.download(rootfsUrl, destFile, onProgress)

    fun extractRootfs(stream: InputStream, rootfsDir: File) {
        TarExtractor.extract(stream, rootfsDir)
    }

    fun prepareRootfs(rootfsDir: File) {}
}
