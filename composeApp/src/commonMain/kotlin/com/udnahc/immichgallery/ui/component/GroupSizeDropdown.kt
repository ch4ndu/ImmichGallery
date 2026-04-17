package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.udnahc.immichgallery.domain.model.GroupSize

@Composable
fun GroupSizeDropdown(
    selected: GroupSize,
    onSelected: (GroupSize) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = when (selected) {
                    GroupSize.NONE -> "None"
                    GroupSize.MONTH -> "Month"
                    GroupSize.DAY -> "Day"
                },
                style = MaterialTheme.typography.labelLarge
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("None") },
                onClick = { onSelected(GroupSize.NONE); expanded = false }
            )
            DropdownMenuItem(
                text = { Text("Month") },
                onClick = { onSelected(GroupSize.MONTH); expanded = false }
            )
            DropdownMenuItem(
                text = { Text("Day") },
                onClick = { onSelected(GroupSize.DAY); expanded = false }
            )
        }
    }
}
