package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.domain.model.LARGE_SCREEN_MOSAIC_COLUMN_COUNTS
import com.udnahc.immichgallery.domain.model.MosaicTemplateFamily
import com.udnahc.immichgallery.domain.model.NORMAL_SCREEN_MOSAIC_COLUMN_COUNTS
import com.udnahc.immichgallery.domain.model.ViewConfig
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.apply
import immichgallery.composeapp.generated.resources.cancel
import immichgallery.composeapp.generated.resources.ic_more_vert
import immichgallery.composeapp.generated.resources.ic_settings
import immichgallery.composeapp.generated.resources.mosaic
import immichgallery.composeapp.generated.resources.mosaic_apply_blocking_warning
import immichgallery.composeapp.generated.resources.mosaic_apply_failed
import immichgallery.composeapp.generated.resources.mosaic_apply_preparing
import immichgallery.composeapp.generated.resources.mosaic_cache_results
import immichgallery.composeapp.generated.resources.mosaic_density_dense
import immichgallery.composeapp.generated.resources.mosaic_density_denser
import immichgallery.composeapp.generated.resources.mosaic_density_large
import immichgallery.composeapp.generated.resources.mosaic_density_larger
import immichgallery.composeapp.generated.resources.mosaic_density_maximum
import immichgallery.composeapp.generated.resources.mosaic_density_medium
import immichgallery.composeapp.generated.resources.mosaic_density_title
import immichgallery.composeapp.generated.resources.mosaic_density_very_dense
import immichgallery.composeapp.generated.resources.mosaic_disable_zoom
import immichgallery.composeapp.generated.resources.mosaic_family_eight
import immichgallery.composeapp.generated.resources.mosaic_family_five
import immichgallery.composeapp.generated.resources.mosaic_family_four
import immichgallery.composeapp.generated.resources.mosaic_family_seven
import immichgallery.composeapp.generated.resources.mosaic_family_six
import immichgallery.composeapp.generated.resources.mosaic_settings
import immichgallery.composeapp.generated.resources.mosaic_settings_tab_density
import immichgallery.composeapp.generated.resources.mosaic_settings_tab_options
import immichgallery.composeapp.generated.resources.mosaic_settings_title
import immichgallery.composeapp.generated.resources.mosaic_settings_warning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun MosaicViewConfigIconMenu(
    viewConfig: ViewConfig,
    onViewConfigChanged: (ViewConfig) -> Unit,
    onPrepareViewConfig: suspend (ViewConfig) -> Result<Unit> = { Result.success(Unit) }
) {
    var expanded by remember { mutableStateOf(false) }
    val mosaicColumnOptions = mosaicColumnOptionsForWindow()
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
                mosaicColumnOptions = mosaicColumnOptions,
                onViewConfigChanged = onViewConfigChanged,
                onPrepareViewConfig = onPrepareViewConfig,
                onDismissMenu = { expanded = false }
            )
        }
    }
}

@Composable
fun MosaicViewConfigMenuItem(
    viewConfig: ViewConfig,
    mosaicColumnOptions: IntRange = NORMAL_SCREEN_MOSAIC_COLUMN_COUNTS,
    onViewConfigChanged: (ViewConfig) -> Unit,
    onPrepareViewConfig: suspend (ViewConfig) -> Result<Unit> = { Result.success(Unit) },
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
            showSettings = true
        }
    )

    if (showSettings) {
        MosaicSettingsDialog(
            viewConfig = viewConfig,
            mosaicColumnOptions = mosaicColumnOptions,
            onViewConfigChanged = onViewConfigChanged,
            onPrepareViewConfig = onPrepareViewConfig,
            onDismiss = { showSettings = false }
        )
    }
}

@Composable
private fun MosaicSettingsDialog(
    viewConfig: ViewConfig,
    mosaicColumnOptions: IntRange,
    onViewConfigChanged: (ViewConfig) -> Unit,
    onPrepareViewConfig: suspend (ViewConfig) -> Result<Unit>,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(MosaicSettingsTab.Options) }
    var isApplying by remember { mutableStateOf(false) }
    var applyError by remember { mutableStateOf(false) }
    var draftEnabled by remember(viewConfig) { mutableStateOf(viewConfig.mosaicEnabled) }
    var draftFamilies by remember(viewConfig) { mutableStateOf(viewConfig.mosaicFamilies) }
    var draftCacheResults by remember(viewConfig) { mutableStateOf(viewConfig.cacheMosaicResults) }
    var draftDisableZoom by remember(viewConfig) { mutableStateOf(viewConfig.disableZoomWhenMosaicEnabled) }
    var draftColumnCount by remember(viewConfig, mosaicColumnOptions) {
        mutableStateOf(
            viewConfig.mosaicColumnCount.coerceIn(
                mosaicColumnOptions.first,
                mosaicColumnOptions.last
            )
        )
    }
    val draftConfig = viewConfig.copy(
        mosaicEnabled = draftEnabled,
        mosaicFamilies = draftFamilies,
        cacheMosaicResults = draftCacheResults,
        disableZoomWhenMosaicEnabled = draftDisableZoom,
        mosaicColumnCount = draftColumnCount.coerceIn(
            mosaicColumnOptions.first,
            mosaicColumnOptions.last
        )
    ).normalized

    AlertDialog(
        onDismissRequest = {
            if (!isApplying) onDismiss()
        },
        title = { Text(stringResource(Res.string.mosaic_settings_title)) },
        text = {
            Column {
                if (isApplying) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.width(Dimens.mediumSpacing))
                        Text(stringResource(Res.string.mosaic_apply_preparing))
                    }
                    return@Column
                }
                Row {
                    TextButton(onClick = { selectedTab = MosaicSettingsTab.Options }) {
                        Text(
                            text = stringResource(Res.string.mosaic_settings_tab_options),
                            color = if (selectedTab == MosaicSettingsTab.Options) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                    TextButton(onClick = { selectedTab = MosaicSettingsTab.Density }) {
                        Text(
                            text = stringResource(Res.string.mosaic_settings_tab_density),
                            color = if (selectedTab == MosaicSettingsTab.Density) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
                when (selectedTab) {
                    MosaicSettingsTab.Options -> {
                        MosaicBooleanRow(
                            label = stringResource(Res.string.mosaic),
                            checked = draftEnabled,
                            onCheckedChange = { draftEnabled = it }
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
                        MosaicBooleanRow(
                            label = stringResource(Res.string.mosaic_cache_results),
                            checked = draftCacheResults,
                            onCheckedChange = { draftCacheResults = it }
                        )
                        MosaicBooleanRow(
                            label = stringResource(Res.string.mosaic_disable_zoom),
                            checked = draftDisableZoom,
                            onCheckedChange = { draftDisableZoom = it }
                        )
                    }

                    MosaicSettingsTab.Density -> {
                        Text(
                            text = stringResource(Res.string.mosaic_density_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        mosaicColumnOptions.forEach { columnCount ->
                            MosaicDensityRow(
                                columnCount = columnCount,
                                selected = draftColumnCount == columnCount,
                                onSelected = { draftColumnCount = columnCount }
                            )
                        }
                    }
                }
                if (applyError) {
                    Spacer(Modifier.height(Dimens.smallSpacing))
                    Text(
                        text = stringResource(Res.string.mosaic_apply_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Spacer(Modifier.height(Dimens.smallSpacing))
                Text(
                    text = if (draftCacheResults) {
                        stringResource(Res.string.mosaic_apply_blocking_warning)
                    } else {
                        stringResource(Res.string.mosaic_settings_warning)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic)
                )
            }
        },
        dismissButton = {
            if (!isApplying) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                applyError = false
                val nextConfig = draftConfig
                isApplying = nextConfig.cacheMosaicResults
                scope.launch {
                    if (nextConfig.cacheMosaicResults) {
                        withFrameNanos { }
                    }
                    val prepareResult = if (nextConfig.cacheMosaicResults) {
                        withContext(Dispatchers.Default) { onPrepareViewConfig(nextConfig) }
                    } else {
                        Result.success(Unit)
                    }
                    prepareResult.fold(
                        onSuccess = {
                            withContext(Dispatchers.Default) {
                                onViewConfigChanged(nextConfig)
                            }
                            isApplying = false
                            onDismiss()
                        },
                        onFailure = {
                            isApplying = false
                            applyError = true
                        }
                    )
                }
            }, enabled = !isApplying && draftConfig != viewConfig.normalized) {
                Text(stringResource(Res.string.apply))
            }
        }
    )
}

@Composable
internal fun mosaicColumnOptionsForWindow(): IntRange {
    val windowWidth = LocalWindowInfo.current.containerDpSize.width
    return if (windowWidth.value.isFinite() && windowWidth >= LargeScreenMosaicColumnsMinWidth) {
        LARGE_SCREEN_MOSAIC_COLUMN_COUNTS
    } else {
        NORMAL_SCREEN_MOSAIC_COLUMN_COUNTS
    }
}

private enum class MosaicSettingsTab {
    Options,
    Density
}

@Composable
private fun MosaicDensityRow(
    columnCount: Int,
    selected: Boolean,
    onSelected: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelected
        )
        Spacer(Modifier.width(Dimens.smallSpacing))
        Text(text = mosaicDensityLabel(columnCount))
    }
}

@Composable
private fun mosaicDensityLabel(columnCount: Int): String =
    when (columnCount) {
        2 -> stringResource(Res.string.mosaic_density_larger)
        3 -> stringResource(Res.string.mosaic_density_large)
        4 -> stringResource(Res.string.mosaic_density_medium)
        5 -> stringResource(Res.string.mosaic_density_dense)
        6 -> stringResource(Res.string.mosaic_density_denser)
        7 -> stringResource(Res.string.mosaic_density_very_dense)
        8 -> stringResource(Res.string.mosaic_density_maximum)
        else -> stringResource(Res.string.mosaic_density_medium)
    }

@Composable
private fun MosaicBooleanRow(
    label: String,
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
        Text(text = label)
    }
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
    MosaicTemplateFamily.SEVEN_TILE -> Res.string.mosaic_family_seven
    MosaicTemplateFamily.EIGHT_TILE -> Res.string.mosaic_family_eight
}

private val LargeScreenMosaicColumnsMinWidth = 600.dp
