package com.arloor.tuke.engine

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.arloor.tuke.MainActivity
import com.arloor.tuke.R
import java.io.File
import org.json.JSONObject

class TukeEngineService : Service() {
    private var process: Process? = null
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEngine()
                stopForegroundInternal()
                stopSelf()
            }
            ACTION_RUN_END -> stopForegroundInternal()
            ACTION_RUN_BEGIN -> startForegroundNotification()
            else -> startEngine()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopEngine()
        super.onDestroy()
    }

    private fun startEngine() {
        stopEngine()
        val config = File(filesDir, "engine-config.json")
        if (!config.exists()) return
        val binary = File(applicationInfo.nativeLibraryDir, "libtuke.so")
        if (!binary.exists()) {
            writeRuntime(JSONObject().put("status", "error").put("error", "缺少本地引擎 libtuke.so"))
            return
        }
        binary.setExecutable(true)
        val runtime = File(filesDir, "engine-runtime.json")
        runtime.delete()
        val builder = ProcessBuilder(binary.absolutePath, "--config", config.absolutePath)
            .directory(filesDir)
            .redirectErrorStream(true)
        val env = builder.environment()
        env["HOME"] = filesDir.absolutePath
        val logFile = File(filesDir, "engine-stdout.log")
        process = builder.start()
        Thread {
            runCatching {
                logFile.outputStream().buffered().use { out ->
                    process?.inputStream?.copyTo(out)
                }
            }
        }.apply { isDaemon = true; name = "tuke-engine-log" }.start()
    }

    private fun stopEngine() {
        process?.destroy()
        process = null
        File(filesDir, "engine-runtime.json").delete()
    }

    private fun writeRuntime(json: JSONObject) {
        File(filesDir, "engine-runtime.json").writeText(json.toString())
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AI 助手后台生成", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("AI 助手正在生成回答")
            .setContentText("可切换应用，生成不会中断")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        foreground = true
    }

    private fun stopForegroundInternal() {
        if (!foreground) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
    }

    companion object {
        private const val CHANNEL_ID = "tuke_engine"
        private const val NOTIFICATION_ID = 2001
        const val ACTION_START = "com.arloor.tuke.engine.START"
        const val ACTION_STOP = "com.arloor.tuke.engine.STOP"
        const val ACTION_RUN_BEGIN = "com.arloor.tuke.engine.RUN_BEGIN"
        const val ACTION_RUN_END = "com.arloor.tuke.engine.RUN_END"

        fun start(context: Context) = send(context, ACTION_START, foreground = false)
        fun stop(context: Context) = send(context, ACTION_STOP, foreground = false)
        fun runBegin(context: Context) = send(context, ACTION_RUN_BEGIN, foreground = true)
        fun runEnd(context: Context) = send(context, ACTION_RUN_END, foreground = false)

        private fun send(context: Context, action: String, foreground: Boolean) {
            val intent = Intent(context, TukeEngineService::class.java).setAction(action)
            runCatching {
                if (foreground) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
