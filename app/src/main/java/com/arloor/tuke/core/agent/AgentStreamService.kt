package com.arloor.tuke.core.agent

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.arloor.tuke.MainActivity

/**
 * AI 助手流式生成期间的前台服务:配合 partial wake lock,让 App 切后台后
 * SSE 长连接尽量实时继续(服务端断连后 run 仍会跑完并持久化,这里争取本地
 * 实时收到)。生成结束(完成/失败/取消)后由 ViewModel 停止。
 */
class AgentStreamService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        acquireWakeLock()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val lock = wakeLock
        if (lock != null && lock.isHeld) {
            runCatching { lock.release() }
        }
        wakeLock = null
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "AI 助手后台生成",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("AI 助手正在生成回答")
            .setContentText("可切换应用，生成不会中断")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun acquireWakeLock() {
        val existing = wakeLock
        if (existing != null && existing.isHeld) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        // 与上游最长请求时间一致；超时后系统自动释放，避免忘释放耗电。
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    companion object {
        private const val CHANNEL_ID = "agent_stream"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "tuke:agent_stream"
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

        /** 启动前台服务;系统拒绝(如后台启动限制)时静默失败,不影响本地流。 */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AgentStreamService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentStreamService::class.java))
        }
    }
}
