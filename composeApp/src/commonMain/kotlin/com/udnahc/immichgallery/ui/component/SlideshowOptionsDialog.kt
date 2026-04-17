package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.SlideshowAnimations
import com.udnahc.immichgallery.domain.model.SlideshowConfig
import com.udnahc.immichgallery.domain.model.SlideshowOrder

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
        title = { Text("Slideshow") },
        text = {
            Column {
                Text("Order", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = order == SlideshowOrder.SEQUENTIAL,
                        onClick = { order = SlideshowOrder.SEQUENTIAL },
                        label = { Text("Sequential") }
                    )
                    FilterChip(
                        selected = order == SlideshowOrder.RANDOM,
                        onClick = { order = SlideshowOrder.RANDOM },
                        label = { Text("Random") }
                    )
                }

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = durationText,
                    onValueChange = { value ->
                        if (value.all { it.isDigit() } && value.length <= 3) {
                            durationText = value
                        }
                    },
                    label = { Text("Duration (seconds)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                Text("Animations", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = animations == SlideshowAnimations.OFF,
                        onClick = { animations = SlideshowAnimations.OFF },
                        label = { Text("Off") }
                    )
                    FilterChip(
                        selected = animations == SlideshowAnimations.RANDOM,
                        onClick = { animations = SlideshowAnimations.RANDOM },
                        label = { Text("Random") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val duration = durationText.toIntOrNull()?.coerceIn(1, 120) ?: 5
                onConfirm(SlideshowConfig(order, duration, animations))
            }) {
                Text("Start")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
