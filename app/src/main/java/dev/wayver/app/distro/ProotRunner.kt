package dev.wayver.app.distro

import android.content.Context
import dev.wayver.app.utils.ContainerLogger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.concurrent.thread

/**
 * Launches Linux commands inside an isolated per-container PRoot sandbox.
 *
 * Each container gets its own `proot-tmp`, `dev-shm`, and `xdg-runtime` directories under
 * `files/containers/<containerId>/` so multiple containers can run concurrently without
 * colliding on Wayland sockets or POSIX shared memory.
 */
class ProotRunner(
    private val context: Context,
    private val rootfsManager: RootfsManager,
    private val containerId: String,
    private val logger: ContainerLogger = ContainerLogger()
) {
    private val containerBase = rootfsManager.containerDir(containerId)
    private val tmpDir = File(containerBase, "proot-tmp").apply { mkdirs() }
    private val shmDir = File(containerBase, "dev-shm").apply { mkdirs() }

    /** Host directory bound to `/tmp/xdg-runtime` inside PRoot so the native Wayland server and guest share sockets. */
    val xdgRuntimeDir = File(containerBase, "xdg-runtime").apply { mkdirs() }

    @Volatile
    private var activeProcess: Process? = null

    fun runCommand(distro: Distro, cmd: String, icdJsonPath: String? = null) {
        stop()
        thread(name = "proot-$containerId") {
            try {
                if (!rootfsManager.ensureContainerReady(containerId, distro)) {
                    return@thread
                }

                // Android SELinux/W^X blocks executing binaries placed in filesDir; packaging PRoot and its
                // loader as `lib*.so` in jniLibs lets PackageManager extract them into executable nativeLibraryDir.
                val nativeDir = context.applicationInfo.nativeLibraryDir
                val prootBin = File(nativeDir, "libproot.so")
                val prootLoader = File(nativeDir, "libproot-loader.so")
                if (!prootBin.exists() || !prootLoader.exists()) {
                    logger.error("ERROR: libproot.so missing in $nativeDir")
                    return@thread
                }

                val rootfsDir = rootfsManager.rootfsDir(containerId)
                val args = buildProotArgs(prootBin, rootfsDir, distro, cmd)
                val pb =
                    ProcessBuilder(args).apply {
                        redirectErrorStream(true)
                        configureEnv(environment(), prootLoader, distro, icdJsonPath)
                    }

                logger.info("Starting PRoot (${distro.id}): $cmd")
                val proc = pb.start()
                activeProcess = proc
                streamProcessOutput(proc, distro)

                val code = proc.waitFor()
                logger.info("PRoot (${distro.id}) exited with code $code")
            } catch (t: Throwable) {
                logger.error("PRoot error", t)
            }
        }
    }

    fun stop() {
        activeProcess?.destroyForcibly()
        activeProcess = null
    }

    private fun buildProotArgs(prootBin: File, rootfsDir: File, distro: Distro, cmd: String): List<String> = listOf(
        prootBin.absolutePath,
        "-r", rootfsDir.absolutePath,
        // Fake root (UID/GID 0) so package managers and rootfs setup work unprivileged.
        "-0",
        // Android app_data_file blocks hardlinks (EPERM); convert link(2) to symlinks for dpkg/apt.
        "--link2symlink",
        "-w", "/root",
        // Bind Android kernel interfaces and per-container IPC/Wayland directories.
        "-b", "/dev",
        "-b", "${shmDir.absolutePath}:/dev/shm",
        "-b", "/proc",
        "-b", "/sys",
        "-b", "${xdgRuntimeDir.absolutePath}:/tmp/xdg-runtime",
        distro.shellPath, "-c", cmd
    )

    private fun configureEnv(env: MutableMap<String, String>, prootLoader: File, distro: Distro, icdJsonPath: String?) {
        // PROOT_TMP_DIR is required because Android does not expose a writable /tmp for ELF loader staging.
        env["PROOT_LOADER"] = prootLoader.absolutePath
        env["PROOT_TMP_DIR"] = tmpDir.absolutePath

        // Must match the Wayland socket created by DisplayActivity's NativeBridge in xdgRuntimeDir.
        env["XDG_RUNTIME_DIR"] = "/tmp/xdg-runtime"
        env["WAYLAND_DISPLAY"] = "wayland-0"

        env["HOME"] = "/root"
        env["TERM"] = "xterm-256color"
        env["LANG"] = "C.UTF-8"
        env["PATH"] = distro.pathEnv
        distro.extraEnv.forEach { (k, v) -> env[k] = v }

        if (icdJsonPath != null) {
            env["VK_ICD_FILENAMES"] = icdJsonPath
        }
    }

    private fun streamProcessOutput(proc: Process, distro: Distro) {
        BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                logger.info("[${distro.id}] $line")
            }
        }
    }
}
