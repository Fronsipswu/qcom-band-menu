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
import android.net.Uri
import dev.qcom.bandmenu.network.TrafficTest
import java.util.concurrent.atomic.AtomicReference

class TrafficTestService : Service() {
    private var sessionId: String? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedId = intent?.getStringExtra(SESSION)
        if (intent?.action == STOP) {
            if (requestedId != null && requestedId == sessionId && requestedId == TrafficTest.get(this).activeSessionId) {
                TrafficTest.get(this).stopForSession(requestedId)
                stopSelf()
            } else if (sessionId == null && TrafficTest.get(this).activeSessionId == null) stopSelf()
            return START_NOT_STICKY
        }
        if (requestedId == null || requestedId != TrafficTest.get(this).activeSessionId) {
            if (sessionId == null) stopSelf()
            return START_NOT_STICKY
        }
        sessionId = requestedId
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Mobile data tests", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TrafficTestService::class.java).setAction(STOP)
            .setData(Uri.parse("qbm://data-test/$requestedId/stop")).putExtra(SESSION, requestedId), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_replay)
            .setContentTitle("Mobile data test running").setContentText("Open the Network page for progress and results.")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(2, notification)
        if (requestedId != TrafficTest.get(this).activeSessionId) stopSelf()
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        TrafficTest.get(this).stop("App closed")
        stopSelf()
    }
    override fun onDestroy() {
        TrafficTest.get(this).stopForSession(sessionId, "Test service stopped")
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "data_tests"
        private const val STOP = "dev.qcom.bandmenu.STOP_DATA_TEST"
        private const val SESSION = "session_id"
        private val requestedSession = AtomicReference<String?>(null)
        fun start(context: Context, sessionId: String) {
            requestedSession.set(sessionId)
            try {
                context.startForegroundService(Intent(context, TrafficTestService::class.java).putExtra(SESSION, sessionId))
            } catch (e: Exception) {
                requestedSession.compareAndSet(sessionId, null)
                throw e
            }
        }
        fun stop(context: Context, sessionId: String) {
            if (requestedSession.compareAndSet(sessionId, null)) context.stopService(Intent(context, TrafficTestService::class.java))
        }
    }
}
