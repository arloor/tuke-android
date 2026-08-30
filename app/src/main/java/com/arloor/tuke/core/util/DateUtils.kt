package com.arloor.tuke.core.util

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

val ScheduleTimezone: ZoneId = ZoneId.of("Asia/Shanghai")

fun nextMinuteTimeOfDay(now: ZonedDateTime = ZonedDateTime.now(ScheduleTimezone)): String {
    val next = now.plusMinutes(1)
    return "%02d:%02d".format(next.hour, next.minute)
}

fun formatDateTime(value: String?): String {
    if (value.isNullOrBlank()) {
        return "-"
    }
    return try {
        val instant = Instant.parse(value)
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(instant)
    } catch (_: Exception) {
        value
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) {
        return "-"
    }
    val units = listOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var index = 0
    while (size >= 1024 && index < units.lastIndex) {
        size /= 1024
        index += 1
    }
    val digits = if (size >= 100 || index == 0) 0 else 1
    return "${toFixed(size, digits)} ${units[index]}"
}