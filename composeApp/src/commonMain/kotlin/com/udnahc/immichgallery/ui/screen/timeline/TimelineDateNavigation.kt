package com.udnahc.immichgallery.ui.screen.timeline

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.udnahc.immichgallery.domain.model.TimelineDateTarget
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.dateFromPickerMillis
import com.udnahc.immichgallery.ui.util.dateToPickerMillis
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.cancel
import immichgallery.composeapp.generated.resources.done
import immichgallery.composeapp.generated.resources.timeline_back_to_today
import immichgallery.composeapp.generated.resources.timeline_date_navigation_hint
import immichgallery.composeapp.generated.resources.timeline_date_navigation_failed
import immichgallery.composeapp.generated.resources.timeline_jump_to_date
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

class TimelineDateNavigationActions(
    val openPicker: () -> Unit,
    val backToToday: () -> Unit,
    val canOpenDateMenu: Boolean = true,
    private val onShowDate: (LocalDate) -> Unit = {},
    val cancel: () -> Unit = {}
) {
    private var registered = false

    /** A stale action cannot acknowledge a root request after Timeline leaves. */
    fun showDate(date: LocalDate): Boolean {
        if (!registered) return false
        onShowDate(date)
        return true
    }

    internal fun register() { registered = true }

    internal fun unregister() {
        if (!registered) return
        registered = false
        cancel()
    }
}

internal fun timelineDateActionsAvailable(
    totalPages: Int,
    isSourceReady: Boolean,
    isLoading: Boolean
): Boolean = totalPages > 0 || (isSourceReady && !isLoading)

private data class TimelineDateRequest(val date: LocalDate, val id: Long)

/** Returns a cancellation action for explicit scrollbar and photo interactions. */
@Composable
internal fun TimelineDateNavigation(
    state: TimelineState,
    listState: LazyListState,
    scrollAnchorReconciler: TimelineScrollAnchorReconciler,
    onActionsChanged: (TimelineDateNavigationActions?) -> Unit,
    resolveTarget: suspend (LocalDate) -> Result<TimelineDateTarget?>,
    yearRange: (LocalDate) -> IntRange,
    onBucketTargeted: (Int, TimelineBucketTargetReason) -> Unit
): () -> Unit {
    var showPicker by remember { mutableStateOf(false) }
    var requestedDate by remember { mutableStateOf<TimelineDateRequest?>(null) }
    var nextRequestId by remember { mutableLongStateOf(0L) }
    var target by remember { mutableStateOf<TimelineDateTarget?>(null) }
    var positionedBucket by remember { mutableStateOf<String?>(null) }
    var showFailure by remember { mutableStateOf(false) }
    val latestResolveTarget by rememberUpdatedState(resolveTarget)
    val canOpenDateMenu = state.pageIndex.totalPages > 0
    val acceptsExternalDate = timelineDateActionsAvailable(
        totalPages = state.pageIndex.totalPages,
        isSourceReady = state.isSourceReady,
        isLoading = state.isLoading
    )
    val clearRequest = {
        requestedDate = null
        target = null
        positionedBucket = null
    }
    val cancel = {
        if (requestedDate != null || target != null) scrollAnchorReconciler.cancelRequestedAnchor()
        clearRequest()
    }
    val requestDate = { date: LocalDate ->
        cancel()
        target = null
        positionedBucket = null
        showFailure = false
        requestedDate = TimelineDateRequest(date, ++nextRequestId)
    }
    val actions = remember(canOpenDateMenu) {
        TimelineDateNavigationActions(
            openPicker = {
                cancel()
                showPicker = true
            },
            backToToday = { requestDate(timelineToday()) },
            canOpenDateMenu = canOpenDateMenu,
            onShowDate = requestDate,
            cancel = {
                cancel()
                showPicker = false
                showFailure = false
            }
        )
    }
    DisposableEffect(actions, acceptsExternalDate, onActionsChanged) {
        if (acceptsExternalDate) actions.register()
        onActionsChanged(actions.takeIf { acceptsExternalDate })
        onDispose {
            actions.unregister()
            onActionsChanged(null)
        }
    }
    LaunchedEffect(requestedDate) {
        val request = requestedDate ?: return@LaunchedEffect
        val result = latestResolveTarget(request.date)
        // Input can supersede the request before composition cancels this effect.
        if (requestedDate != request) return@LaunchedEffect
        result.fold(
            onSuccess = { resolved ->
                target = resolved
                if (resolved == null) {
                    showFailure = true
                    requestedDate = null
                }
            },
            onFailure = {
                showFailure = true
                requestedDate = null
            }
        )
    }
    val headerOffset = with(LocalDensity.current) { Dimens.sectionHeaderHeight.roundToPx() }
    // First reveal the bucket and let the existing materialization/Mosaic queue
    // run. Keep the stable asset id until its actual row or band is published.
    LaunchedEffect(target, state.displayIndex, state.buckets) {
        val destination = target ?: return@LaunchedEffect
        val bucketIndex = state.buckets.indexOfFirst { it.timeBucket == destination.timeBucket }
        if (bucketIndex < 0) {
            cancel()
            return@LaunchedEffect
        }
        val projection = state.displayIndex
        val exactIndex = destination.assetId?.let(projection.assetDisplayIndexById::get)
        if (exactIndex == null && positionedBucket == destination.timeBucket) return@LaunchedEffect
        val displayIndex = exactIndex ?: projection.displayIndexesByBucket[bucketIndex]
            ?.firstOrNull() ?: return@LaunchedEffect
        val offset = if (exactIndex != null) -headerOffset else 0
        if (exactIndex != null) {
            scrollAnchorReconciler.requestAssetAnchor(
                projection, destination.assetId, bucketIndex, offset
            )
        } else {
            scrollAnchorReconciler.requestBucketAnchor(
                projection, bucketIndex, projection.gridKeyByDisplayIndex.getOrNull(displayIndex), offset
            )
        }
        listState.requestScrollToItem(displayIndex, offset)
        positionedBucket = destination.timeBucket
        onBucketTargeted(bucketIndex, TimelineBucketTargetReason.DateNavigation)
        if (exactIndex != null || destination.assetId == null) clearRequest()
    }
    if (showPicker) {
        val today = remember { timelineToday() }
        TimelineDatePickerDialog(
            initialDate = requestedDate?.date ?: today,
            yearRange = yearRange(today),
            onDismiss = { showPicker = false },
            onDateSelected = { date ->
                requestDate(date)
                showPicker = false
            }
        )
    }
    if (showFailure) {
        AlertDialog(
            onDismissRequest = { showFailure = false },
            title = { Text(stringResource(Res.string.timeline_jump_to_date)) },
            text = { Text(stringResource(Res.string.timeline_date_navigation_failed)) },
            confirmButton = {
                TextButton(onClick = { showFailure = false }) {
                    Text(stringResource(Res.string.done))
                }
            }
        )
    }
    return cancel
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimelineDatePickerDialog(
    initialDate: LocalDate,
    yearRange: IntRange,
    onDismiss: () -> Unit,
    onDateSelected: (LocalDate) -> Unit
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = dateToPickerMillis(initialDate),
        yearRange = yearRange
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = pickerState.selectedDateMillis != null,
                onClick = {
                    pickerState.selectedDateMillis?.let { onDateSelected(dateFromPickerMillis(it)) }
                }
            ) { Text(stringResource(Res.string.timeline_jump_to_date)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onDateSelected(timelineToday()) }) {
                    Text(stringResource(Res.string.timeline_back_to_today))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
            }
        }
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            DatePicker(
                state = pickerState,
                showModeToggle = true,
                title = {
                    Column(Modifier.padding(
                        start = Dimens.screenPadding,
                        end = Dimens.screenPadding,
                        top = Dimens.screenPadding
                    )) {
                        Text(
                            stringResource(Res.string.timeline_jump_to_date),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            stringResource(Res.string.timeline_date_navigation_hint),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            )
        }
    }
}

private fun timelineToday(): LocalDate =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
