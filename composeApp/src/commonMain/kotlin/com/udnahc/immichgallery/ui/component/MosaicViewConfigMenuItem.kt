package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.ViewConfig
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.done
import immichgallery.composeapp.generated.resources.ic_more_vert
import immichgallery.composeapp.generated.resources.ic_settings
import immichgallery.composeapp.generated.resources.mosaic
import immichgallery.composeapp.generated.resources.mosaic_family_five
import immichgallery.composeapp.generated.resources.mosaic_family_four
import immichgallery.composeapp.generated.resources.mosaic_family_six
import immichgallery.composeapp.generated.resources.mosaic_settings
import immichgallery.composeapp.generated.resources.mosaic_settings_title
import immichgallery.composeapp.generated.resources.mosaic_settings_warning
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.painterResource

@Composable
fun MosaicViewConfigIconMenu(
    viewConfig: ViewConfig,
    onViewConfigChanged: (ViewConfig) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(Res.drawable.ic_more_vert),
                contentDescription = stringResource(Res.string.mosaic_settings)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            MosaicViewConfigMenuItem(
                viewConfig = viewConfig,
                onViewConfigChanged = onViewConfigChanged,
                onDismissMenu = { expanded = false }
            )
        }
    }
}

@Composable
fun MosaicViewConfigMenuItem(
    viewConfig: ViewConfig,
    onViewConfigChanged: (ViewConfig) -> Unit,
    onDismissMenu: () -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }

    DropdownMenuItem(
        leadingIcon = {
            Checkbox(
                checked = viewConfig.mosaicEnabled,
                onCheckedChange = null
            )
        },
        text = { Text(stringResource(Res.string.mosaic)) },
        trailingIcon = {
            IconButton(onClick = { showSettings = true }) {
                Icon(
                    painter = painterResource(Res.drawable.ic_settings),
                    contentDescription = stringResource(Res.string.mosaic_settings)
                )
            }
        },
        onClick = {
            onViewConfigChanged(viewConfig.copy(mosaicEnabled = !viewConfig.mosaicEnabled))
            onDismissMenu()
        }
    )

    if (showSettings) {
        MosaicSettingsDialog(
            viewConfig = viewConfig,
            onViewConfigChanged = onViewConfigChanged,
            onDismiss = { showSettings = false }
        )
    }
}

@Composable
private fun MosaicSettingsDialog(
    viewConfig: ViewConfig,
    onViewConfigChanged: (ViewConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var draftFamilies by remember(viewConfig) { mutableStateOf(viewConfig.mosaicFamilies) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.mosaic_settings_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(Res.string.mosaic_settings_warning),
                    style = MaterialTheme.typography.bodyMedium
                )
                MosaicTemplateFamily.entries.forEach { family ->
                    MosaicFamilyRow(
                        family = family,
                        checked = family in draftFamilies,
                        onCheckedChange = { checked ->
                            val nextFamilies = if (checked) {
                                draftFamilies + family
                            } else {
                                draftFamilies - family
                            }
                            if (nextFamilies.isNotEmpty()) {
                                draftFamilies = nextFamilies
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onViewConfigChanged(viewConfig.copy(mosaicFamilies = draftFamilies))
                onDismiss()
            }) {
                Text(stringResource(Res.string.done))
            }
        }
    )
}

@Composable
private fun MosaicFamilyRow(
    family: MosaicTemplateFamily,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Spacer(Modifier.width(Dimens.smallSpacing))
        Text(text = stringResource(family.labelRes()))
    }
}

private fun MosaicTemplateFamily.labelRes() = when (this) {
    MosaicTemplateFamily.FOUR_TILE -> Res.string.mosaic_family_four
    MosaicTemplateFamily.FIVE_TILE -> Res.string.mosaic_family_five
    MosaicTemplateFamily.SIX_TILE -> Res.string.mosaic_family_six
}
