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
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.group_size_day
import immichgallery.composeapp.generated.resources.group_size_month
import immichgallery.composeapp.generated.resources.group_size_none
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private fun GroupSize.labelRes(): StringResource = when (this) {
    GroupSize.NONE -> Res.string.group_size_none
    GroupSize.MONTH -> Res.string.group_size_month
    GroupSize.DAY -> Res.string.group_size_day
}

@Composable
fun GroupSizeDropdown(
    selected: GroupSize,
    onSelected: (GroupSize) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = stringResource(selected.labelRes()),
                style = MaterialTheme.typography.labelLarge
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GroupSize.entries.forEach { size ->
                DropdownMenuItem(
                    text = { Text(stringResource(size.labelRes())) },
                    onClick = { onSelected(size); expanded = false }
                )
            }
        }
    }
}
