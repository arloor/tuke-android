package com.arloor.tuke.core.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 用系统浏览器/对应 App 打开链接，返回是否成功唤起。
 */
fun openUrl(context: Context, url: String, packageName: String? = null): Boolean {
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            if (!packageName.isNullOrBlank()) {
                setPackage(packageName)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: Throwable) {
        false
    }
}

/**
 * 弹出系统分享面板分享一段文本（如会话公开链接），返回是否成功唤起。
 */
fun shareText(context: Context, text: String, title: String = "分享"): Boolean {
    return try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TITLE, title)
        }
        context.startActivity(Intent.createChooser(intent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    } catch (_: Throwable) {
        false
    }
}

/**
 * 优先尝试 App 内 deep link，失败则回退到网页链接。
 */
fun openAppUrlOrWeb(
    context: Context,
    appUrl: String?,
    webUrl: String?,
    appPackageName: String? = null,
): Boolean {
    if (!appUrl.isNullOrBlank() && openUrl(context, appUrl, appPackageName)) {
        return true
    }
    return !webUrl.isNullOrBlank() && openUrl(context, webUrl)
}
