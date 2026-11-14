package dev.wayver.app.distro

import android.os.SystemClock
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Provides readable `/proc` and `/sys` bind-mount stubs for Android SELinux-restricted paths
 * (following the `proot-distro` pattern), updated live from readable per-PID `/proc/<pid>/stat`
 * entries and [SystemClock.elapsedRealtime].
 */
class ProcSysBindings(containerBase: File, private val containerId: String) {
    private val procStubDir = File(containerBase, "proc-stubs").apply { mkdirs() }
    private val sysStubDir = File(containerBase, "sys-stubs").apply { mkdirs() }

    @Volatile
    private var samplerRunning = false
    private var samplerThread: Thread? = null

    fun prepare() {
        val cpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val elapsedMs = SystemClock.elapsedRealtime()
        val btime = ((System.currentTimeMillis() - elapsedMs) / 1000L).coerceAtLeast(1L)
        val idleInit = (elapsedMs / 10L).coerceAtLeast(1000L)
        val uptimeSec = elapsedMs / 1000.0

        val procContents = mapOf(
            "stat" to buildProcStat(cpus, 0L, 0L, idleInit * cpus, btime),
            "uptime" to String.format(Locale.US, "%.2f %.2f\n", uptimeSec, uptimeSec),
            "loadavg" to "0.00 0.00 0.00 1/1 1\n",
            "net_dev" to
                "Inter-|   Receive                                                |  Transmit\n" +
                " face |bytes    packets errs drop fifo frame compressed multicast" +
                "|bytes    packets errs drop fifo colls carrier compressed\n" +
                "    lo:       0       0    0    0    0     0          0         0" +
                "        0       0    0    0    0     0       0          0\n",
            "diskstats" to "",
            "vmstat" to "pgpgin 0\npgpgout 0\npswpin 0\npswpout 0\n"
        )
        for ((relName, _) in PROC_BIND_TARGETS) {
            File(procStubDir, relName).writeText(procContents.getValue(relName))
        }

        for ((relName, _) in SYS_BIND_TARGETS) {
            File(sysStubDir, relName).mkdirs()
        }
        val cpuDir = File(sysStubDir, "devices/system/cpu").apply { mkdirs() }
        val range = if (cpus == 1) "0\n" else "0-${cpus - 1}\n"
        File(cpuDir, "present").writeText(range)
        File(cpuDir, "online").writeText(range)
        File(cpuDir, "possible").writeText(range)
    }

    fun bindArgs(): List<String> = buildList {
        add("-b")
        add("/proc")
        for ((relName, target) in PROC_BIND_TARGETS) {
            add("-b")
            add("${File(procStubDir, relName).absolutePath}:$target")
        }
        add("-b")
        add("/sys")
        for ((relName, target) in SYS_BIND_TARGETS) {
            add("-b")
            add("${File(sysStubDir, relName).absolutePath}:$target")
        }
    }

    fun startSampler() {
        stopSampler()
        samplerRunning = true
        samplerThread = thread(name = "stats-$containerId") {
            val cpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val prevTicks = HashMap<Int, Pair<Long, Long>>()
            var totalUser = 0L
            var totalSys = 0L
            while (samplerRunning) {
                try {
                    Thread.sleep(1000L)
                } catch (_: InterruptedException) {
                    break
                }
                if (!samplerRunning) break

                val nextTicks = HashMap<Int, Pair<Long, Long>>()
                val procEntries = File("/proc").listFiles() ?: continue
                for (entry in procEntries) {
                    val pid = entry.name.toIntOrNull() ?: continue
                    val statLine = runCatching { File(entry, "stat").readText() }.getOrNull() ?: continue
                    val rparen = statLine.lastIndexOf(')')
                    if (rparen < 0 || rparen + 2 >= statLine.length) continue
                    val fields = statLine.substring(rparen + 2).split(' ')
                    if (fields.size <= 12) continue
                    val utime = fields[11].toLongOrNull() ?: continue
                    val stime = fields[12].toLongOrNull() ?: continue
                    nextTicks[pid] = utime to stime
                    val prev = prevTicks[pid]
                    if (prev != null) {
                        totalUser += (utime - prev.first).coerceAtLeast(0L)
                        totalSys += (stime - prev.second).coerceAtLeast(0L)
                    }
                }
                prevTicks.clear()
                prevTicks.putAll(nextTicks)

                val elapsedMs = SystemClock.elapsedRealtime()
                val elapsedTicks = (elapsedMs / 10L) * cpus
                val idleTotal = (elapsedTicks - totalUser - totalSys).coerceAtLeast(1L)
                val btime = ((System.currentTimeMillis() - elapsedMs) / 1000L).coerceAtLeast(1L)
                File(procStubDir, "stat").writeText(buildProcStat(cpus, totalUser, totalSys, idleTotal, btime))

                val uptimeSec = elapsedMs / 1000.0
                val uptimeText = String.format(Locale.US, "%.2f %.2f\n", uptimeSec, uptimeSec)
                File(procStubDir, "uptime").writeText(uptimeText)
            }
        }
    }

    fun stopSampler() {
        samplerRunning = false
        samplerThread?.interrupt()
        samplerThread = null
    }

    private fun buildProcStat(cpus: Int, user: Long, sys: Long, idle: Long, btime: Long): String {
        val perCpuUser = user / cpus
        val perCpuSys = sys / cpus
        val perCpuIdle = idle / cpus
        val sb = StringBuilder("cpu  $user 0 $sys $idle 0 0 0 0 0 0\n")
        for (i in 0 until cpus) {
            sb.append("cpu$i $perCpuUser 0 $perCpuSys $perCpuIdle 0 0 0 0 0 0\n")
        }
        sb.append("intr 0\nctxt 0\nbtime $btime\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n")
            .append("softirq 0 0 0 0 0 0 0 0 0 0 0\n")
        return sb.toString()
    }

    private companion object {
        val PROC_BIND_TARGETS = listOf(
            "stat" to "/proc/stat",
            "uptime" to "/proc/uptime",
            "loadavg" to "/proc/loadavg",
            "net_dev" to "/proc/net/dev",
            "diskstats" to "/proc/diskstats",
            "vmstat" to "/proc/vmstat"
        )
        val SYS_BIND_TARGETS = listOf(
            "devices" to "/sys/devices",
            "power_supply" to "/sys/class/power_supply",
            "net" to "/sys/class/net"
        )
    }
}
