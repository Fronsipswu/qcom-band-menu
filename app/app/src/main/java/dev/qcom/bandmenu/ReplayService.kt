package dev.qcom.bandmenu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps the process running while replay is on, so the Network page keeps
 * polling and recording with the app in the background. It does no work of
 * its own: the poller lives in [DaemonHolder]. Closing the app (Back, or
 * swiping it away) stops it.
 */
class ReplayService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Network replay", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_replay)
            .setContentTitle("Recording network measurements")
            .setContentText("Replay is on. Turn it off from the Network page menu.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "replay"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ReplayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReplayService::class.java))
        }
    }
}
