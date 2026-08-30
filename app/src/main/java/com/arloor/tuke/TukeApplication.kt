package com.arloor.tuke

import android.app.Application
import android.os.Process
import com.arloor.tuke.di.AppContainer
import java.io.File

class TukeApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (isEngineProcess()) return
        appContainer = AppContainer(this)
    }

    private fun isEngineProcess(): Boolean {
        val cmdline = runCatching { File("/proc/${Process.myPid()}/cmdline").readText() }.getOrDefault("")
        return cmdline.contains(":engine")
    }
}
