package dev.wayver.app.distro

import android.content.Context
import dev.wayver.app.utils.ContainerLogger
import dev.wayver.app.utils.HttpDownloader
import dev.wayver.app.utils.TarExtractor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Manages reusable distro image tarballs (`files/images/<distroId>.tar.gz`) and per-container
 * rootfs directories (`files/containers/<containerId>/rootfs`).
 */
class RootfsManager(
    private val context: Context,
    private val logger: ContainerLogger = ContainerLogger()
) {
    private val imagesRoot = File(context.filesDir, "images").apply { mkdirs() }
    private val containersRoot = File(context.filesDir, "containers").apply { mkdirs() }

    fun imageFile(distro: Distro): File = File(imagesRoot, "${distro.id}.tar.gz")

    fun hasImage(distro: Distro): Boolean = imageFile(distro).exists()

    fun containerDir(containerId: String): File = File(containersRoot, containerId).apply { mkdirs() }

    fun rootfsDir(containerId: String): File = File(containerDir(containerId), "rootfs")

    fun isContainerInstalled(containerId: String, distro: Distro): Boolean {
        val dir = rootfsDir(containerId)
        return File(dir, distro.markerRelPath).exists() || File(dir, distro.shellPath.trimStart('/')).exists()
    }

    fun deleteContainer(containerId: String) {
        val dir = File(containersRoot, containerId)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    fun ensureContainerReady(containerId: String, distro: Distro): Boolean {
        val dir = rootfsDir(containerId)
        val marker = File(dir, distro.markerRelPath)
        if (isContainerInstalled(containerId, distro)) {
            val version = if (marker.exists()) marker.readText().trim().lineSequence().firstOrNull() ?: "" else ""
            logger.info("Container ready ($containerId): ${distro.name} $version".trim())
            ensureBaseFiles(dir)
            distro.prepareRootfs(dir)
            return true
        }

        val cachedImg = imageFile(distro)
        if (cachedImg.exists()) {
            logger.info("Creating container $containerId from cached ${distro.name} image...")
            return FileInputStream(cachedImg).use { fis ->
                extractIntoContainer(containerId, distro, fis)
            }
        }

        logger.error("ERROR: ${distro.name} image not found. Download or Import Image first.")
        return false
    }

    fun importImage(distro: Distro, rawStream: InputStream): Boolean {
        val target = imageFile(distro)
        val part = File(imagesRoot, "${distro.id}.tar.gz.part")
        part.delete()
        return try {
            rawStream.use { input ->
                FileOutputStream(part).use { output ->
                    input.copyTo(output, 65536)
                }
            }
            if (target.exists()) target.delete()
            part.renameTo(target)
        } finally {
            part.delete()
        }
    }

    fun downloadImage(distro: Distro, onProgress: (String) -> Unit): Boolean {
        val urlStr = distro.rootfsUrl ?: return false
        return HttpDownloader.download(urlStr, imageFile(distro), onProgress)
    }

    private fun extractIntoContainer(containerId: String, distro: Distro, stream: InputStream): Boolean {
        val dir = rootfsDir(containerId)
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()

        TarExtractor.extract(stream, dir)
        ensureBaseFiles(dir)
        distro.prepareRootfs(dir)

        val ok = isContainerInstalled(containerId, distro)
        if (ok) {
            logger.info("Container ready ($containerId): ${distro.name}")
        } else {
            logger.error("ERROR: image missing ${distro.shellPath}")
        }
        return ok
    }

    private fun ensureBaseFiles(rootfs: File) {
        File(rootfs, "etc").mkdirs()

        // Distro tarballs often ship /etc/resolv.conf as a dangling symlink (e.g. systemd-resolved).
        writeIfMissingOrEmpty(
            File(rootfs, "etc/resolv.conf"),
            "nameserver 1.1.1.1\nnameserver 8.8.8.8\n"
        )

        // D-Bus, Mesa, and Wayland clients expect a non-empty machine-id.
        val machineIdFile = File(rootfs, "etc/machine-id")
        writeIfMissingOrEmpty(machineIdFile, "1234567890abcdef1234567890abcdef\n")

        val dbusDir = File(rootfs, "var/lib/dbus").apply { mkdirs() }
        writeIfMissingOrEmpty(File(dbusDir, "machine-id"), machineIdFile.readText())

        // Guarantee PRoot bind-mount targets exist even in minimal rootfs tarballs.
        listOf("tmp", "root", "dev", "proc", "sys").forEach { File(rootfs, it).mkdirs() }
    }

    private fun writeIfMissingOrEmpty(file: File, content: String) {
        if (!file.exists() || file.length() == 0L) {
            // File.exists() is false for broken symlinks, so delete() first to avoid EEXIST.
            file.delete()
            file.writeText(content)
        }
    }
}
