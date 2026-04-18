package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.dismiss
import immichgallery.composeapp.generated.resources.ic_close
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun ErrorBanner(
    message: String,
    lastSyncedAt: Long? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayText = if (lastSyncedAt != null) {
        val formatted = formatLastSynced(lastSyncedAt)
        "$message. Last updated $formatted"
    } else {
        message
    }

    BannerRow(
        text = displayText,
        backgroundColor = MaterialTheme.colorScheme.errorContainer,
        textColor = MaterialTheme.colorScheme.onErrorContainer,
        onDismiss = onDismiss,
        modifier = modifier
    )
}

@Composable
fun SuccessBanner(
    message: String,
    autoDismissMillis: Long = 5000,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(message) {
        delay(autoDismissMillis)
        onDismiss()
    }

    BannerRow(
        text = message,
        backgroundColor = Color(0xFF4CAF50),
        textColor = Color.White,
        onDismiss = onDismiss,
        modifier = modifier
    )
}

@Composable
private fun BannerRow(
    text: String,
    backgroundColor: Color,
    textColor: Color,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.bannerPaddingHorizontal)
            .clip(RoundedCornerShape(Dimens.bannerCornerRadius))
            .background(backgroundColor)
            .padding(horizontal = Dimens.bannerPaddingHorizontal, vertical = Dimens.mediumSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(Dimens.mediumSpacing))
        IconButton(onClick = onDismiss, modifier = Modifier.size(Dimens.iconSize)) {
            Icon(
                painterResource(Res.drawable.ic_close),
                contentDescription = stringResource(Res.string.dismiss),
                tint = textColor,
                modifier = Modifier.size(Dimens.bannerIconSize)
            )
        }
    }
}

private fun formatLastSynced(epochMillis: Long): String {
    return try {
        val instant = Instant.fromEpochMilliseconds(epochMillis)
        val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        val monthName = local.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
        "${local.dayOfMonth} $monthName ${local.year}"
    } catch (_: Exception) {
        "unknown"
    }
}
