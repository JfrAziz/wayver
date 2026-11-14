package dev.wayver.app.container

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.wayver.app.DisplayActivity
import dev.wayver.app.R
import dev.wayver.app.distro.DistroRegistry

/**
 * Foreground service that maintains one persistent notification per running container
 * with "Open" and "Kill" actions.
 */
class ContainerNotificationService : Service() {
    companion object {
        private const val CHANNEL_ID = "wayver_containers"
        private const val ACTION_SYNC = "dev.wayver.app.action.SYNC_CONTAINERS"
        private const val ACTION_KILL = "dev.wayver.app.action.KILL_CONTAINER"
        private const val EXTRA_CONTAINER_ID = "container_id"

        fun sync(context: Context) {
            if (ContainerManager.runningContainers().isEmpty()) return
            val intent = Intent(context, ContainerNotificationService::class.java).setAction(ACTION_SYNC)
            ContextCompat.startForegroundService(context, intent)
        }

        private fun notifIdFor(containerId: String): Int = 1000 + (containerId.hashCode() and 0x7FFFFFFF) % 100_000
    }

    private val nm: NotificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var foregroundNotifId: Int? = null
    private val extraNotifIds = mutableSetOf<Int>()

    private val onContainersChanged: () -> Unit = {
        mainHandler.post { reconcileNotifications() }
    }

    override fun onCreate() {
        super.onCreate()

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Active Containers", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Controls for running Linux containers"
                setShowBadge(false)
            }
        )

        ContainerManager.addListener(onContainersChanged)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_KILL)
            intent.getStringExtra(EXTRA_CONTAINER_ID)
                ?.takeIf { it.isNotEmpty() }
                ?.let { ContainerManager.stopContainer(it) }

        reconcileNotifications()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ContainerManager.removeListener(onContainersChanged)

        clearAllNotifications()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun reconcileNotifications() {
        val running = ContainerManager.runningContainers()
        if (running.isEmpty()) {
            clearAllNotifications()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val fgContainer = running.first()
        val nextFgId = notifIdFor(fgContainer.id)
        val prevFgId = foregroundNotifId

        // Promote first active container as the FGS notification (never double-posted via nm.notify).
        promoteForeground(nextFgId, buildNotification(fgContainer))
        extraNotifIds.remove(nextFgId)
        foregroundNotifId = nextFgId

        val nextExtraIds = running.drop(1).associateBy { notifIdFor(it.id) }

        // Cancel any previous foreground or extra notification IDs no longer in use.
        if (prevFgId != null && prevFgId != nextFgId && prevFgId !in nextExtraIds)
            nm.cancel(prevFgId)

        val staleExtras = extraNotifIds.filter { it !in nextExtraIds }

        for (id in staleExtras) {
            nm.cancel(id)
            extraNotifIds.remove(id)
        }

        for ((id, container) in nextExtraIds) {
            nm.notify(id, buildNotification(container))
            extraNotifIds.add(id)
        }
    }

    private fun clearAllNotifications() {
        extraNotifIds.forEach { nm.cancel(it) }
        extraNotifIds.clear()
        foregroundNotifId?.let { nm.cancel(it) }
        foregroundNotifId = null
    }

    private fun promoteForeground(notifId: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(notifId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else
            startForeground(notifId, notification)
    }

    private fun buildNotification(container: ContainerConfig): Notification {
        val id = notifIdFor(container.id)
        val distro = DistroRegistry.byId(container.distroId)
        val desktop = distro.desktopById(container.desktopId)

        val openPending = PendingIntent.getActivity(
            this,
            id * 2,
            DisplayActivity.createIntent(this, container.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val killPending = PendingIntent.getService(
            this,
            id * 2 + 1,
            Intent(this, ContainerNotificationService::class.java)
                .setAction(ACTION_KILL)
                .putExtra(EXTRA_CONTAINER_ID, container.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(container.name)
            .setContentText("${distro.name} • ${desktop.name}")
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openPending)
            .addAction(0, "Open", openPending)
            .addAction(0, "Kill", killPending)
            .build()
    }
}
