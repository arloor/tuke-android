package com.arloor.tuke.engine

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.system.Os
import android.system.OsConstants
import java.io.File
import org.json.JSONObject

class TukeEngineService : Service() {
    private var process: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEngine()
                stopSelf()
            }
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
        runtimeFile().delete()
        val builder = ProcessBuilder(binary.absolutePath, "--config", config.absolutePath)
            .directory(filesDir)
            .redirectErrorStream(true)
        val env = builder.environment()
        env["HOME"] = filesDir.absolutePath
        val logFile = File(filesDir, "engine-stdout.log")
        val launched = runCatching { builder.start() }.getOrElse { error ->
            writeRuntime(
                JSONObject()
                    .put("status", "error")
                    .put("error", "启动本地引擎失败：${error.message ?: error.javaClass.simpleName}"),
            )
            return
        }
        process = launched
        Thread {
            runCatching {
                logFile.outputStream().buffered().use { out ->
                    launched.inputStream.copyTo(out)
                }
            }
        }.apply { isDaemon = true; name = "tuke-engine-log" }.start()
    }

    private fun stopEngine() {
        val running = process
        process = null
        if (running != null) {
            runCatching { running.destroy() }
        } else {
            stopRecordedEngine()
        }
        runtimeFile().delete()
    }

    private fun stopRecordedEngine() {
        val runtime = runtimeFile()
        val pid = runCatching { JSONObject(runtime.readText()).optInt("pid", -1) }.getOrDefault(-1)
        if (pid <= 0) return
        val expectedBinary = File(applicationInfo.nativeLibraryDir, "libtuke.so").absolutePath
        val actualBinary = runCatching {
            File("/proc/$pid/cmdline").readText().substringBefore('\u0000')
        }.getOrNull()
        if (actualBinary == expectedBinary) {
            runCatching { Os.kill(pid, OsConstants.SIGTERM) }
        }
    }

    private fun writeRuntime(json: JSONObject) {
        runtimeFile().writeText(json.toString())
    }

    private fun runtimeFile() = File(filesDir, "engine-runtime.json")

    companion object {
        const val ACTION_START = "com.arloor.tuke.engine.START"
        const val ACTION_STOP = "com.arloor.tuke.engine.STOP"

        fun start(context: Context) = send(context, ACTION_START)
        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            val intent = Intent(context, TukeEngineService::class.java).setAction(action)
            runCatching { context.startService(intent) }
        }
    }
}
