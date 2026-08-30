package com.arloor.tuke.core.update

import android.content.Context
import java.io.File

object UpdateApkCleaner {
    fun ensureUpdateDir(context: Context): File =
        File(context.cacheDir, "updates").apply { mkdirs() }

    fun cleanupDownloadedApks(context: Context, keepFile: File? = null) {
        val updateDir = File(context.cacheDir, "updates")
        if (!updateDir.isDirectory) return

        val keepPath = keepFile?.absoluteFile?.normalize()?.absolutePath
        updateDir.listFiles()?.forEach { file ->
            runCatching {
                val isUpdateFile = file.isFile &&
                    (file.extension.equals("apk", ignoreCase = true) || file.name.endsWith(".apk.part"))
                val shouldKeep = keepPath != null &&
                    file.absoluteFile.normalize().absolutePath == keepPath
                if (isUpdateFile && !shouldKeep) file.delete()
            }
        }
        runCatching { if (updateDir.listFiles().isNullOrEmpty()) updateDir.delete() }
    }
}
