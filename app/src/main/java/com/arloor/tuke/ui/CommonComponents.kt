package com.arloor.tuke.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class BannerTone {
    Info,
    Success,
    Error,
}

@Composable
fun NoticeBanner(
    text: String,
    tone: BannerTone,
    modifier: Modifier = Modifier,
) {
    val borderColor = when (tone) {
        BannerTone.Info -> Primary.copy(alpha = 0.35f)
        BannerTone.Success -> Success.copy(alpha = 0.35f)
        BannerTone.Error -> DangerBorder
    }
    val backgroundColor = when (tone) {
        BannerTone.Info -> PrimaryContainer.copy(alpha = 0.65f)
        BannerTone.Success -> SuccessBg
        BannerTone.Error -> DangerBg
    }
    val textColor = when (tone) {
        BannerTone.Info -> Primary
        BannerTone.Success -> Success
        BannerTone.Error -> Danger
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .background(backgroundColor, RoundedCornerShape(12.dp))
            .padding(horizontal = SpacingMd, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
        )
    }
}

@Composable
fun LoadingState(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpacingSm),
    ) {
        CircularProgressIndicator()
        Text(text = text, color = TextMuted)
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = SpacingLg,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardCornerRadius))
            .background(Color.White, RoundedCornerShape(CardCornerRadius))
            .border(1.dp, Border, RoundedCornerShape(CardCornerRadius))
            .padding(contentPadding),
    ) {
        content()
    }
}

@Composable
fun LabelValueRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimary,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SpacingXs),
        horizontalArrangement = Arrangement.spacedBy(SpacingMd),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, color = TextMuted, style = MaterialTheme.typography.bodySmall)
        Text(
            text = value,
            color = valueColor,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}


@Composable
fun EmptyState(
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = SpacingXl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpacingSm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextSubtle,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = message,
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(PageHorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpacingMd, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = Danger,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = message,
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            Button(onClick = onRetry) {
                Text("重试")
            }
        }
    }
}

@Composable
fun RequireAuth(
    isLoggedIn: Boolean,
    isRestoring: Boolean,
    loginContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    when {
        isLoggedIn -> content()
        isRestoring -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(PageHorizontalPadding),
                verticalArrangement = Arrangement.Center,
            ) {
                LoadingState("正在启动本地引擎...")
            }
        }
        !isLoggedIn -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(PageHorizontalPadding),
                verticalArrangement = Arrangement.Center,
            ) {
                loginContent()
            }
        }
    }
}
