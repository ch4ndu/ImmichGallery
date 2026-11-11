package com.udnahc.immichgallery.ui.component

import androidx.compose.runtime.*
import com.udnahc.immichgallery.data.repository.SessionOperationStamp
import com.udnahc.immichgallery.domain.action.auth.SessionOperationRunner
import com.udnahc.immichgallery.domain.model.*
import org.koin.compose.koinInject

typealias SlideshowWindowLaunch = (SlideshowWindowSource, String, SlideshowConfig) -> Unit

/** Unsupported on mobile and in previews. The host must admit before inserting a window. */
val LocalSlideshowWindowHost = staticCompositionLocalOf<((SlideshowWindowRequest) -> Unit)?> { null }

/** Retain the source owner's epoch before any options/deferred confirmation is created. */
@Composable
fun rememberSlideshowWindowLauncher(
    owner: Any,
    admittedStamp: SessionOperationStamp? = null
): SlideshowWindowLaunch? {
    val host = LocalSlideshowWindowHost.current ?: return null
    val runner: SessionOperationRunner = koinInject()
    val latestHost by rememberUpdatedState(host)
    val epoch = remember(owner) { admittedStamp?.ownerEpoch ?: runner.ownerEpochSnapshot() }
    val alive = remember(owner) { mutableStateOf(true) }
    DisposableEffect(owner) { onDispose { alive.value = false } }
    val launch: SlideshowWindowLaunch = remember(owner) {
        { source, assetId, config ->
            if (alive.value) {
                val snapshot = when (source) {
                    is SlideshowWindowSource.Snapshot -> source.copy(assets = source.assets.toList())
                    is SlideshowWindowSource.Timeline -> source.copy(buckets = source.buckets.toList())
                    else -> source
                }
                latestHost?.invoke(SlideshowWindowRequest(snapshot, assetId, config,
                    admittedStamp ?: runner.stampForOwner(epoch)))
            }
        }
    }
    return launch
}

/** A viewer can disappear while its source screen remains mounted. Retain the parent's
 * authority, but invalidate any deferred viewer/stack confirmation on disposal. */
@Composable
internal fun rememberSlideshowWindowLaunchScope(parent: SlideshowWindowLaunch?): SlideshowWindowLaunch? {
    val alive = remember(parent) { mutableStateOf(true) }
    DisposableEffect(parent) { onDispose { alive.value = false } }
    val launch: SlideshowWindowLaunch = remember(parent) {
        { source, assetId, config ->
            if (alive.value) parent?.invoke(source, assetId, config)
        }
    }
    return launch.takeIf { parent != null }
}

/** Detached windows supply their own visible/nonminimized/terminal eligibility. */
internal val LocalSlideshowUsesAppForeground = staticCompositionLocalOf { true }
