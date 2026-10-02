package com.udnahc.immichgallery.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Size
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetType

internal data class ViewerImagePrefetchTarget(
    val assetId: String,
    val url: String,
    val width: Int,
    val height: Int
)

internal fun viewerImagePrefetchAssets(assets: List<Asset>, settledPage: Int, upcoming: Asset?): List<Asset> =
    (listOf(settledPage - 1, settledPage, settledPage + 1).mapNotNull(assets::getOrNull) + listOfNotNull(upcoming)).distinctBy { it.id }

internal fun viewerImagePrefetchTargets(
    assets: List<Asset>,
    settledPage: Int,
    viewportSize: IntSize,
    upcoming: Asset? = null
): List<ViewerImagePrefetchTarget> = viewerImagePrefetchTargets(
    assets = viewerImagePrefetchAssets(assets, settledPage, upcoming),
    viewportSize = viewportSize
)

internal fun viewerImagePrefetchTargets(
    assets: List<Asset>,
    viewportSize: IntSize
): List<ViewerImagePrefetchTarget> {
    if (viewportSize.width <= 0 || viewportSize.height <= 0) return emptyList()
    return assets.mapNotNull { asset ->
        if (asset.type != AssetType.IMAGE) return@mapNotNull null
        ViewerImagePrefetchTarget(
            assetId = asset.id,
            url = asset.originalUrl,
            width = viewportSize.width,
            height = viewportSize.height
        )
    }.distinct()
}

internal fun interface ViewerImagePrefetchHandle {
    fun dispose()
}

internal class ViewerImagePrefetchController(
    private val enqueue: (ViewerImagePrefetchTarget) -> ViewerImagePrefetchHandle
) {
    private val active = linkedMapOf<ViewerImagePrefetchTarget, ViewerImagePrefetchHandle>()

    fun update(targets: List<ViewerImagePrefetchTarget>) {
        val desired = targets.toSet()
        active.keys.filterNot(desired::contains).forEach { target ->
            active.remove(target)?.dispose()
        }
        desired.filterNot(active::containsKey).forEach { target ->
            active[target] = enqueue(target)
        }
    }

    fun dispose() {
        active.values.forEach(ViewerImagePrefetchHandle::dispose)
        active.clear()
    }

    internal fun activeTargetsForTests(): Set<ViewerImagePrefetchTarget> = active.keys
}

@Composable
internal fun ViewerImagePrefetch(
    assets: List<Asset>,
    settledPage: Int,
    upcoming: Asset? = null
) {
    ViewerImagePrefetchCandidates(
        assets = viewerImagePrefetchAssets(assets, settledPage, upcoming)
    )
}

@Composable
internal fun ViewerImagePrefetchCandidates(
    assets: List<Asset>
) {
    val context = LocalPlatformContext.current
    val viewportSize = LocalWindowInfo.current.containerSize
    val imageLoader = remember(context) { SingletonImageLoader.get(context) }
    val controller = remember(context, imageLoader) {
        ViewerImagePrefetchController { target ->
            val disposable = imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(target.url)
                    .size(Size(target.width, target.height))
                    .build()
            )
            ViewerImagePrefetchHandle(disposable::dispose)
        }
    }

    LaunchedEffect(assets, viewportSize, controller) {
        controller.update(viewerImagePrefetchTargets(assets, viewportSize))
    }

    DisposableEffect(controller) {
        onDispose(controller::dispose)
    }
}
