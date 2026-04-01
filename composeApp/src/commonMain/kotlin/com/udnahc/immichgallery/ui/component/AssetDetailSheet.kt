package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.Image
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Size
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.AssetDetailPerson
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.detail_camera
import immichgallery.composeapp.generated.resources.detail_error
import immichgallery.composeapp.generated.resources.detail_loading
import immichgallery.composeapp.generated.resources.detail_location
import immichgallery.composeapp.generated.resources.detail_people
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import androidx.compose.ui.tooling.preview.Preview
import org.jetbrains.compose.resources.stringResource

private const val BYTES_PER_KB = 1_024.0
private const val BYTES_PER_MB = 1_048_576.0
private const val THUMBNAIL_DECODE_SIZE = 256

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetDetailSheet(
    assetId: String,
    getAssetDetail: suspend (String) -> Result<AssetDetail>,
    onPersonClick: (personId: String, personName: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var detail by remember { mutableStateOf<AssetDetail?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(assetId) {
        isLoading = true
        error = null
        withContext(Dispatchers.IO) {
            getAssetDetail(assetId)
        }.fold(
            onSuccess = { detail = it; isLoading = false },
            onFailure = { error = it.message; isLoading = false }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().height(Dimens.albumCoverHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(Dimens.mediumSpacing))
                        Text(
                            stringResource(Res.string.detail_loading),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            error != null -> {
                Box(
                    modifier = Modifier.fillMaxWidth().height(Dimens.albumCoverHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(Res.string.detail_error),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            detail != null -> {
                AssetDetailContent(detail = detail!!, onPersonClick = onPersonClick)
            }
        }
    }
}

@Composable
private fun AssetDetailContent(
    detail: AssetDetail,
    onPersonClick: (personId: String, personName: String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.screenPadding)
            .padding(bottom = Dimens.extraLargeSpacing)
    ) {
        // File name
        if (detail.fileName.isNotEmpty()) {
            Text(
                text = detail.fileName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(Dimens.smallSpacing))
        }

        // Date
        val dateDisplay = detail.dateTime?.let { formatDateTime(it) }
        if (dateDisplay != null) {
            Text(
                text = dateDisplay,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Dimens.smallSpacing))
        }

        // Dimensions & file size
        val sizeInfo = remember(detail) { buildSizeInfo(detail) }
        if (sizeInfo.isNotEmpty()) {
            Text(
                text = sizeInfo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // People
        if (detail.people.isNotEmpty()) {
            Spacer(Modifier.height(Dimens.largeSpacing))
            HorizontalDivider()
            Spacer(Modifier.height(Dimens.largeSpacing))
            SectionHeader(stringResource(Res.string.detail_people))
            Spacer(Modifier.height(Dimens.mediumSpacing))
            PeopleRow(detail.people, onPersonClick)
        }

        // Location
        val locationText = remember(detail) { buildLocationText(detail) }
        if (locationText != null) {
            Spacer(Modifier.height(Dimens.largeSpacing))
            HorizontalDivider()
            Spacer(Modifier.height(Dimens.largeSpacing))
            SectionHeader(stringResource(Res.string.detail_location))
            Spacer(Modifier.height(Dimens.smallSpacing))
            Text(
                text = locationText,
                style = MaterialTheme.typography.bodyMedium
            )
            if (detail.latitude != null && detail.longitude != null) {
                Text(
                    text = "${detail.latitude}, ${detail.longitude}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Camera details
        val cameraInfo = remember(detail) { buildCameraInfo(detail) }
        if (cameraInfo.isNotEmpty()) {
            Spacer(Modifier.height(Dimens.largeSpacing))
            HorizontalDivider()
            Spacer(Modifier.height(Dimens.largeSpacing))
            SectionHeader(stringResource(Res.string.detail_camera))
            Spacer(Modifier.height(Dimens.smallSpacing))
            cameraInfo.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Description
        if (!detail.description.isNullOrBlank()) {
            Spacer(Modifier.height(Dimens.largeSpacing))
            HorizontalDivider()
            Spacer(Modifier.height(Dimens.largeSpacing))
            Text(
                text = detail.description,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun PeopleRow(
    people: List<com.udnahc.immichgallery.domain.model.AssetDetailPerson>,
    onPersonClick: (personId: String, personName: String) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Dimens.largeSpacing)
    ) {
        items(people, key = { it.id }) { person ->
            val onClick = remember(person.id) { { onPersonClick(person.id, person.name) } }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(Dimens.personAvatarSize)
                    .clickable(onClick = onClick)
            ) {
                val painter = rememberAsyncImagePainter(
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(person.thumbnailUrl)
                        .precision(Precision.EXACT)
                        .size(Size(THUMBNAIL_DECODE_SIZE, THUMBNAIL_DECODE_SIZE))
                        .build()
                )
                Image(
                    painter = painter,
                    contentDescription = person.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(Dimens.sectionHeaderHeight)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(Modifier.height(Dimens.smallSpacing))
                Text(
                    text = person.name,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private fun formatDateTime(dateTime: String): String? {
    return try {
        val instant = Instant.parse(dateTime)
        val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        val monthName = local.month.name.lowercase().replaceFirstChar { it.uppercase() }
        "$monthName ${local.dayOfMonth}, ${local.year} at ${
            local.hour.toString().padStart(2, '0')
        }:${local.minute.toString().padStart(2, '0')}"
    } catch (_: Exception) {
        if (dateTime.isNotBlank()) dateTime else null
    }
}

private fun buildSizeInfo(detail: AssetDetail): String {
    val parts = mutableListOf<String>()
    if (detail.width != null && detail.height != null) {
        parts.add("${detail.width} x ${detail.height}")
    }
    if (detail.fileSizeInByte != null) {
        if (detail.fileSizeInByte < BYTES_PER_MB) {
            val kb = detail.fileSizeInByte / BYTES_PER_KB
            val kbInt = (kb * 10).toLong()
            parts.add("${kbInt / 10}.${kbInt % 10} KB")
        } else {
            val mb = detail.fileSizeInByte / BYTES_PER_MB
            val mbInt = (mb * 10).toLong()
            parts.add("${mbInt / 10}.${mbInt % 10} MB")
        }
    }
    return parts.joinToString(" · ")
}

private fun buildLocationText(detail: AssetDetail): String? {
    val parts = listOfNotNull(detail.city, detail.state, detail.country)
    return parts.joinToString(", ").ifEmpty { null }
}

@Preview
@Composable
private fun AssetDetailContentPreview() {
    AssetDetailContent(
        detail = AssetDetail(
            id = "1",
            fileName = "IMG_2024.jpg",
            dateTime = "2024-06-15T10:30:00Z",
            cameraMake = "Apple",
            cameraModel = "iPhone 15 Pro",
            lensModel = "iPhone 15 Pro back camera",
            focalLength = 6.765,
            aperture = 1.78,
            shutterSpeed = "1/120",
            iso = 100,
            latitude = 37.7749,
            longitude = -122.4194,
            city = "San Francisco",
            state = "California",
            country = "United States",
            fileSizeInByte = 4_500_000,
            width = 4032,
            height = 3024,
            description = "Golden Gate Bridge at sunset",
            people = listOf(
                AssetDetailPerson(id = "p1", name = "Alice", thumbnailUrl = ""),
                AssetDetailPerson(id = "p2", name = "Bob", thumbnailUrl = "")
            )
        ),
        onPersonClick = { _, _ -> }
    )
}

@Preview
@Composable
private fun SectionHeaderPreview() {
    SectionHeader(title = "People")
}

@Preview
@Composable
private fun PeopleRowPreview() {
    PeopleRow(
        people = listOf(
            AssetDetailPerson(id = "1", name = "Alice", thumbnailUrl = ""),
            AssetDetailPerson(id = "2", name = "Bob", thumbnailUrl = "")
        ),
        onPersonClick = { _, _ -> }
    )
}

private fun buildCameraInfo(detail: AssetDetail): List<String> {
    val lines = mutableListOf<String>()
    val cameraName = listOfNotNull(detail.cameraMake, detail.cameraModel).joinToString(" ")
    if (cameraName.isNotBlank()) lines.add(cameraName)
    if (!detail.lensModel.isNullOrBlank()) lines.add(detail.lensModel)

    val settings = mutableListOf<String>()
    detail.focalLength?.let { settings.add("${it.toInt()}mm") }
    detail.aperture?.let { settings.add("f/$it") }
    detail.shutterSpeed?.let { settings.add("${it}s") }
    detail.iso?.let { settings.add("ISO $it") }
    if (settings.isNotEmpty()) lines.add(settings.joinToString("  "))

    return lines
}
