package dev.wayver.app.distro

import android.content.Context
import dev.wayver.app.container.EnvConfig
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
    private val procSysBindings = ProcSysBindings(containerBase, containerId)

    /** Host directory bound to `/tmp/xdg-runtime` inside PRoot so the native Wayland server and guest share sockets. */
    val xdgRuntimeDir = File(containerBase, "xdg-runtime").apply { mkdirs() }

    @Volatile
    private var activeProcess: Process? = null

    fun runCommand(distro: Distro, cmd: String, envConfig: EnvConfig = EnvConfig()) {
        stop()
        thread(name = "proot-$containerId") {
            try {
                if (!rootfsManager.ensureContainerReady(containerId, distro)) {
                    return@thread
                }
                val pb = createProcessBuilder(distro, cmd, envConfig) ?: return@thread
                logger.info("Starting PRoot (${distro.id}): $cmd")
                val proc = pb.start()
                activeProcess = proc
                procSysBindings.startSampler()
                streamProcessOutput(proc, distro)

                val code = proc.waitFor()
                procSysBindings.stopSampler()
                logger.info("PRoot (${distro.id}) exited with code $code")
            } catch (t: Throwable) {
                logger.error("PRoot error", t)
            }
        }
    }

    /** Runs a blocking one-time setup command inside PRoot and returns its exit code. */
    fun runSetup(distro: Distro, cmd: String, envConfig: EnvConfig = EnvConfig()): Int {
        stop()
        return try {
            val pb = createProcessBuilder(distro, cmd, envConfig) ?: return -1
            logger.info("Running PRoot setup (${distro.id})")
            val proc = pb.start()
            activeProcess = proc
            streamProcessOutput(proc, distro)
            val code = proc.waitFor()
            activeProcess = null
            logger.info("PRoot setup (${distro.id}) exited with code $code")
            code
        } catch (t: Throwable) {
            activeProcess = null
            logger.error("PRoot setup error", t)
            -1
        }
    }

    private fun createProcessBuilder(distro: Distro, cmd: String, envConfig: EnvConfig): ProcessBuilder? {
        // Android SELinux/W^X blocks executing binaries placed in filesDir; packaging PRoot and its
        // loader as `lib*.so` in jniLibs lets PackageManager extract them into executable nativeLibraryDir.
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val prootBin = File(nativeDir, "libproot.so")
        val prootLoader = File(nativeDir, "libproot-loader.so")
        if (!prootBin.exists() || !prootLoader.exists()) {
            logger.error("ERROR: libproot.so missing in $nativeDir")
            return null
        }

        val rootfsDir = rootfsManager.rootfsDir(containerId)
        procSysBindings.prepare()
        val l2sDir = File(rootfsManager.containerDir(containerId), "proot-l2s").apply { mkdirs() }
        val args = buildList {
            addAll(
                listOf(
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
                    "-b", "${l2sDir.absolutePath}:${l2sDir.absolutePath}"
                )
            )
            addAll(procSysBindings.bindArgs())
            add("-b")
            add("${xdgRuntimeDir.absolutePath}:/tmp/xdg-runtime")
            add(distro.shellPath)
            add("-c")
            add(cmd)
        }
        return ProcessBuilder(args).apply {
            redirectErrorStream(true)
            environment().apply {
                // PROOT_TMP_DIR is required because Android does not expose a writable /tmp for ELF staging.
                put("PROOT_LOADER", prootLoader.absolutePath)
                put("PROOT_TMP_DIR", tmpDir.absolutePath)
                put("PROOT_L2S_DIR", l2sDir.absolutePath)

                // Must match the Wayland & PulseAudio sockets created by DisplayActivity in xdgRuntimeDir.
                put("XDG_RUNTIME_DIR", "/tmp/xdg-runtime")
                put("WAYLAND_DISPLAY", "wayland-0")
                put("PULSE_SERVER", "unix:/tmp/xdg-runtime/pulse-native")

                put("HOME", "/root")
                put("SHELL", distro.shellPath)
                put("TERM", "xterm-256color")
                put("LANG", "C.UTF-8")
                put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                putAll(envConfig.resolve(distro.extraEnv, distro.appEnvs))
            }
        }
    }

    fun stop() {
        procSysBindings.stopSampler()
        activeProcess?.destroyForcibly()
        activeProcess = null
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
