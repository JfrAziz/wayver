package dev.wayver.app.distro

import android.content.Context
import dev.wayver.app.utils.ContainerLogger
import java.io.File
import java.io.FileInputStream

/**
 * Manages reusable distro image tarballs (`files/images/<distroId>.tar.gz`) and per-container
 * rootfs directories (`files/containers/<containerId>/rootfs`).
 */
class RootfsManager(
    context: Context,
    private val logger: ContainerLogger = ContainerLogger()
) {
    private val imagesRoot = File(context.filesDir, "images").apply { mkdirs() }

    private val containersRoot = File(context.filesDir, "containers").apply { mkdirs() }

    fun imageFile(distro: Distro): File = File(imagesRoot, "${distro.id}.tar.gz")

    fun hasImage(distro: Distro): Boolean = imageFile(distro).exists()

    fun downloadImage(distro: Distro, onProgress: (String) -> Unit): Boolean =
        distro.downloadRootfs(imageFile(distro), onProgress)

    fun rootfsDir(containerId: String): File = File(containerDir(containerId), "rootfs")

    fun containerDir(containerId: String): File = File(containersRoot, containerId).apply { mkdirs() }

    fun isContainerInstalled(containerId: String, distro: Distro): Boolean {
        val dir = rootfsDir(containerId)
        return File(dir, distro.relPath).exists() || File(dir, distro.shellPath.trimStart('/')).exists()
    }

    private fun installFromImage(containerId: String, dir: File, distro: Distro, image: File): Boolean {
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()

        FileInputStream(image).use { fis -> distro.extractRootfs(fis, dir) }

        val ok = isContainerInstalled(containerId, distro)

        if (ok) logger.info("Container ready ($containerId): ${distro.name}")
        else logger.error("ERROR: image missing ${distro.shellPath}")

        return ok
    }

    fun ensureContainerReady(containerId: String, distro: Distro): Boolean {
        val dir = rootfsDir(containerId)

        if (!isContainerInstalled(containerId, distro)) {
            val cachedImg = imageFile(distro)
            if (!cachedImg.exists()) {
                logger.error("ERROR: ${distro.name} image not found. Download the image first.")
                return false
            }
            logger.info("Creating container $containerId from cached ${distro.name} image...")
            if (!installFromImage(containerId, dir, distro, cachedImg)) {
                return false
            }
        } else {
            // read from os release
            val marker = File(dir, distro.relPath)

            val version = if (marker.exists()) marker.readText().trim().lineSequence().firstOrNull().orEmpty() else ""

            logger.info("Container ready ($containerId): ${distro.name} $version".trim())
        }

        // Distro tarballs often ship /etc/resolv.conf as a dangling symlink (e.g. systemd-resolved).
        ensureFile(File(dir, "etc/resolv.conf"), "nameserver 1.1.1.1\nnameserver 8.8.8.8\n")

        // D-Bus, Mesa, and Wayland clients expect a non-empty machine-id.
        val machineIdFile = File(dir, "etc/machine-id")
        ensureFile(machineIdFile, "1234567890abcdef1234567890abcdef\n")
        ensureFile(File(dir, "var/lib/dbus/machine-id"), machineIdFile.readText())

        // Guarantee PRoot bind-mount targets exist even in minimal rootfs tarballs.
        listOf("tmp", "root", "dev", "proc", "sys").forEach { File(dir, it).mkdirs() }

        distro.prepareRootfs(dir)

        return true
    }

    fun deleteContainer(containerId: String) {
        File(containersRoot, containerId).deleteRecursively()
    }

    private fun markerFile(containerId: String, desktopId: String): File =
        File(containerDir(containerId), ".setup-$desktopId.done")

    fun isDesktopPrepared(containerId: String, desktopId: String): Boolean = markerFile(containerId, desktopId).exists()

    fun markDesktopPrepared(containerId: String, distroId: String, desktopId: String) {
        markerFile(containerId, desktopId).writeText(
            """{"desktopId":"$desktopId","distroId":"$distroId","completedAt":${System.currentTimeMillis()}}"""
        )
    }

    fun clearDesktopPrepared(containerId: String, desktopId: String) {
        markerFile(containerId, desktopId).delete()
    }

    private fun ensureFile(file: File, content: String) {
        if (!file.exists() || file.length() == 0L) {
            file.parentFile?.mkdirs()
            // File.exists() is false for broken symlinks, so delete() first to avoid EEXIST.
            file.delete()
            file.writeText(content)
        }
    }
}
