package com.arloor.tuke.core.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

fun toFixed(value: Double, scale: Int): String {
    if (!value.isFinite()) {
        return "0"
    }
    return BigDecimal(value).setScale(scale, RoundingMode.HALF_UP).toPlainString()
}

fun parseSignedNumber(input: String?): Double? {
    if (input.isNullOrBlank()) {
        return null
    }
    return input.trim().toDoubleOrNull()
}

fun colorBySignedNumber(input: String?): Long {
    val value = parseSignedNumber(input) ?: return 0xFF1B1C1FL
    return when {
        value > 0 -> 0xFFC5221FL
        value < 0 -> 0xFF188038L
        else -> 0xFF1B1C1FL
    }
}

fun colorBySignedNumber(input: Double?): Long {
    if (input == null || !input.isFinite()) {
        return 0xFF1B1C1FL
    }
    return when {
        input > 0 -> 0xFFC5221FL
        input < 0 -> 0xFF188038L
        else -> 0xFF1B1C1FL
    }
}

fun formatRate(value: Double?): String {
    if (value == null || !value.isFinite()) {
        return "-"
    }
    return "${toFixed(value, 2)}%"
}

fun formatFloatMarketCap(value: Double?): String {
    if (value == null || !value.isFinite() || value <= 0.0) {
        return "-"
    }
    return "${toFixed(value / 100_000_000.0, 2)}亿"
}

fun formatVolume(value: Double?): String {
    if (value == null || !value.isFinite() || value <= 0.0) {
        return "-"
    }
    return when {
        value >= 100_000_000.0 -> "${toFixed(value / 100_000_000.0, 2)}亿手"
        value >= 10_000.0 -> "${toFixed(value / 10_000.0, 2)}万手"
        else -> "${String.format(Locale.US, "%.0f", value)}手"
    }
}

fun formatPositionValue(value: Double): String {
    val abs = kotlin.math.abs(value)
    return when {
        abs >= 100_000_000.0 -> "${toFixed(value / 100_000_000.0, 2)}亿"
        abs >= 10_000.0 -> "${toFixed(value / 10_000.0, 2)}万"
        else -> toFixed(value, 2)
    }
}