package com.arloor.tuke.feature.agent

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 聊天页使用独立于数据表格的阅读型排版。
 *
 * 中文长文需要比 Material 默认 bodyMedium 更大的字号与更松的行距；显式指定字重也能
 * 避免 Compose Text 与承载 Markdown 的原生 TextView 呈现出不同的视觉重量。
 */
internal val ChatBodyTextStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp,
    lineHeight = 25.sp,
    letterSpacing = 0.sp,
)

internal val ChatUserTextStyle = ChatBodyTextStyle.copy(lineHeight = 24.sp)

internal val ChatMetaTextStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.sp,
)

internal val ChatTitleTextStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp,
    lineHeight = 22.sp,
    letterSpacing = 0.sp,
)
