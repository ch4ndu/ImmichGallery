package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.udnahc.immichgallery.domain.model.SlideshowAnimations
import com.udnahc.immichgallery.domain.model.SlideshowConfig
import com.udnahc.immichgallery.domain.model.SlideshowOrder
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.cancel
import immichgallery.composeapp.generated.resources.detail_slideshow
import immichgallery.composeapp.generated.resources.slideshow_animations
import immichgallery.composeapp.generated.resources.slideshow_animations_off
import immichgallery.composeapp.generated.resources.slideshow_animations_random
import immichgallery.composeapp.generated.resources.slideshow_duration_seconds
import immichgallery.composeapp.generated.resources.slideshow_order
import immichgallery.composeapp.generated.resources.slideshow_order_random
import immichgallery.composeapp.generated.resources.slideshow_order_sequential
import immichgallery.composeapp.generated.resources.slideshow_start
import org.jetbrains.compose.resources.stringResource

@Composable
fun SlideshowOptionsDialog(
    onConfirm: (SlideshowConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var order by remember { mutableStateOf(SlideshowOrder.SEQUENTIAL) }
    var durationText by remember { mutableStateOf("5") }
    var animations by remember { mutableStateOf(SlideshowAnimations.RANDOM) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.detail_slideshow)) },
        text = {
            Column {
                Text(stringResource(Res.string.slideshow_order), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(Dimens.smallSpacing))
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)) {
                    FilterChip(
                        selected = order == SlideshowOrder.SEQUENTIAL,
                        onClick = { order = SlideshowOrder.SEQUENTIAL },
                        label = { Text(stringResource(Res.string.slideshow_order_sequential)) }
                    )
                    FilterChip(
                        selected = order == SlideshowOrder.RANDOM,
                        onClick = { order = SlideshowOrder.RANDOM },
                        label = { Text(stringResource(Res.string.slideshow_order_random)) }
                    )
                }

                Spacer(Modifier.height(Dimens.largeSpacing))

                OutlinedTextField(
                    value = durationText,
                    onValueChange = { value ->
                        if (value.all { it.isDigit() } && value.length <= 3) {
                            durationText = value
                        }
                    },
                    label = { Text(stringResource(Res.string.slideshow_duration_seconds)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(Dimens.largeSpacing))

                Text(stringResource(Res.string.slideshow_animations), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(Dimens.smallSpacing))
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)) {
                    FilterChip(
                        selected = animations == SlideshowAnimations.OFF,
                        onClick = { animations = SlideshowAnimations.OFF },
                        label = { Text(stringResource(Res.string.slideshow_animations_off)) }
                    )
                    FilterChip(
                        selected = animations == SlideshowAnimations.RANDOM,
                        onClick = { animations = SlideshowAnimations.RANDOM },
                        label = { Text(stringResource(Res.string.slideshow_animations_random)) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val duration = durationText.toIntOrNull()?.coerceIn(1, 120) ?: 5
                onConfirm(SlideshowConfig(order, duration, animations))
            }) {
                Text(stringResource(Res.string.slideshow_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )
}
