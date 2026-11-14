package dev.wayver.app.container

import dev.wayver.app.DisplayActivity
import dev.wayver.app.distro.ProotRunner
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object ContainerManager {
    private data class ActiveEntry(
        val config: ContainerConfig,
        val activityRef: WeakReference<DisplayActivity>,
        val runner: ProotRunner
    )

    private val active = ConcurrentHashMap<String, ActiveEntry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun register(config: ContainerConfig, activity: DisplayActivity, runner: ProotRunner) {
        val previous = active.put(config.id, ActiveEntry(config, WeakReference(activity), runner))

        if (previous != null && previous.activityRef.get() !== activity) {
            previous.runner.stop()

            previous.activityRef.get()?.let { oldAct ->
                if (!oldAct.isFinishing && !oldAct.isDestroyed)
                    oldAct.runOnUiThread { oldAct.finishAndRemoveTask() }
            }
        }

        ContainerNotificationService.sync(activity.applicationContext)

        notifyChanged()
    }

    fun unregister(containerId: String, activity: DisplayActivity) {
        val current = active[containerId]

        if (current != null && current.activityRef.get() === activity) {
            active.remove(containerId)
            notifyChanged()
        }
    }

    fun isRunning(containerId: String): Boolean {
        val entry = active[containerId] ?: return false
        val act = entry.activityRef.get()
        if (act == null || act.isFinishing || act.isDestroyed) {
            active.remove(containerId)
            return false
        }
        return true
    }

    fun runningIds(): Set<String> {
        active.keys.forEach { isRunning(it) }
        return active.keys.toSet()
    }

    fun runningContainers(): List<ContainerConfig> {
        active.keys.forEach { isRunning(it) }

        return active.values.map { it.config }
    }

    fun stopContainer(containerId: String) {
        val entry = active.remove(containerId) ?: return
        entry.runner.stop()
        entry.activityRef.get()?.let { act ->
            if (!act.isFinishing && !act.isDestroyed)
                act.runOnUiThread { act.finishAndRemoveTask() }
        }
        notifyChanged()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        listeners.forEach { it.invoke() }
    }
}
