package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.back
import immichgallery.composeapp.generated.resources.ic_back
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private const val BAR_ALPHA = 0.8f

@Composable
fun DetailTopBar(
    title: String,
    onBack: () -> Unit,
    trailingContent: @Composable (() -> Unit)? = null
) {
    val barColor = MaterialTheme.colorScheme.background.copy(alpha = BAR_ALPHA)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(barColor)
            .statusBarsPadding()
            .height(Dimens.topBarHeight)
            .padding(start = Dimens.smallSpacing, end = Dimens.screenPadding)
    ) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(
                painterResource(Res.drawable.ic_back),
                contentDescription = stringResource(Res.string.back),
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = Dimens.topBarHeight)
        )
        if (trailingContent != null) {
            Row(modifier = Modifier.align(Alignment.CenterEnd)) {
                trailingContent()
            }
        }
    }
}

@Preview
@Composable
private fun DetailTopBarPreview() {
    DetailTopBar(title = "Album Name", onBack = {})
}
