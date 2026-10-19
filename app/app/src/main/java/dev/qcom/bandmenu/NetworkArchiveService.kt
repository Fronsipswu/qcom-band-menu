package dev.qcom.bandmenu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import dev.qcom.bandmenu.network.NetworkArchive

/** Independent of the temporary replay switch. Recording stops when the app is closed. */
class NetworkArchiveService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var sessionId: String? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getStringExtra("sessionId")
        if (intent?.action == "STOP") {
            owner?.stopForSession(requested, "Stopped from notification")
            if (requestedSessionId == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (requested == null || requested != requestedSessionId) {
            if (requestedSessionId == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        sessionId = requested
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("network-archive", "Network log archives", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 31, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, requested.hashCode(), Intent(this, NetworkArchiveService::class.java)
            .setAction("STOP").putExtra("sessionId", requested), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "network-archive").setSmallIcon(R.drawable.ic_stat_replay)
            .setContentTitle("Saving network log archive").setContentText("Compressed logs are being saved to your chosen folder.")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Stop recording", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(31, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(31, notification)
        if (wake == null) wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QBM:NetworkArchive").apply { setReferenceCounted(false); acquire() }
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) { owner?.stopForSession(sessionId, "App removed from recent tasks"); stopSelf() }
    override fun onDestroy() {
        wake?.takeIf { it.isHeld }?.release(); wake = null
        owner?.takeIf { it.active }?.stopForSession(sessionId, "Recording service stopped")
        super.onDestroy()
    }
    companion object {
        @Volatile var owner: NetworkArchive? = null
        @Volatile private var requestedSessionId: String? = null
        @Synchronized fun start(context: Context, sessionId: String) {
            requestedSessionId = sessionId
            context.startForegroundService(Intent(context, NetworkArchiveService::class.java).putExtra("sessionId", sessionId))
        }
        @Synchronized fun stop(context: Context, sessionId: String) {
            if (requestedSessionId != sessionId) return
            requestedSessionId = null
            context.stopService(Intent(context, NetworkArchiveService::class.java))
        }
    }
}
