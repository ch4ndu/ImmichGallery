package com.udnahc.immichgallery.ui.screen.detail

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import com.udnahc.immichgallery.ui.component.StaticPhotoOverlay
import com.udnahc.immichgallery.ui.navigation.AlbumsRoute
import com.udnahc.immichgallery.ui.navigation.PhotoDetailRoute
import com.udnahc.immichgallery.ui.navigation.SearchRoute
import com.udnahc.immichgallery.ui.navigation.TimelineRoute
import com.udnahc.immichgallery.ui.screen.album.AlbumDetailViewModel
import com.udnahc.immichgallery.ui.screen.people.PersonDetailViewModel
import com.udnahc.immichgallery.ui.screen.search.SearchViewModel
import com.udnahc.immichgallery.ui.screen.timeline.TimelinePhotoOverlay
import com.udnahc.immichgallery.ui.screen.timeline.TimelineViewModel
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PhotoDetailScreen(
    route: PhotoDetailRoute,
    tabNavController: NavController,
    onBack: () -> Unit,
    onPersonClick: (personId: String, personName: String) -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    when (route.sourceScreen) {
        "timeline" -> {
            val vm: TimelineViewModel = koinViewModel(
                viewModelStoreOwner = tabNavController.getBackStackEntry(TimelineRoute)
            )
            TimelinePhotoOverlay(
                timelineState = vm.state,
                initialIndex = vm.detailInitialIndex,
                apiKey = vm.apiKey,
                getAssetFileName = vm::getAssetFileName,
                getAssetDetail = vm::getAssetDetail,
                getAssetsForBucket = vm::getAssetsForBucket,
                onBucketNeeded = vm::loadBucketAssets,
                onPersonClick = onPersonClick,
                onDismiss = { currentAssetId ->
                    vm.lastViewedAssetId = currentAssetId
                    onBack()
                },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
        "search" -> {
            val vm: SearchViewModel = koinViewModel(
                viewModelStoreOwner = tabNavController.getBackStackEntry(SearchRoute)
            )
            val state by vm.state.collectAsState()
            StaticPhotoOverlay(
                assets = state.results,
                initialIndex = state.results.indexOfFirst { it.id == route.assetId }.coerceAtLeast(0),
                apiKey = vm.apiKey,
                getAssetDetail = vm::getAssetDetail,
                onPersonClick = onPersonClick,
                onDismiss = { currentAssetId ->
                    vm.lastViewedAssetId = currentAssetId
                    onBack()
                },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
        "album" -> {
            val parentEntry = tabNavController.previousBackStackEntry ?: return
            val vm: AlbumDetailViewModel = koinViewModel(
                viewModelStoreOwner = parentEntry
            )
            val state by vm.state.collectAsState()
            StaticPhotoOverlay(
                assets = state.assets,
                initialIndex = state.assets.indexOfFirst { it.id == route.assetId }.coerceAtLeast(0),
                apiKey = vm.apiKey,
                getAssetDetail = vm::getAssetDetail,
                onPersonClick = onPersonClick,
                onDismiss = { currentAssetId ->
                    vm.lastViewedAssetId = currentAssetId
                    onBack()
                },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
        "person" -> {
            val parentEntry = tabNavController.previousBackStackEntry ?: return
            val vm: PersonDetailViewModel = koinViewModel(
                viewModelStoreOwner = parentEntry
            )
            val state by vm.state.collectAsState()
            StaticPhotoOverlay(
                assets = state.assets,
                initialIndex = state.assets.indexOfFirst { it.id == route.assetId }.coerceAtLeast(0),
                apiKey = vm.apiKey,
                getAssetDetail = vm::getAssetDetail,
                onPersonClick = onPersonClick,
                onDismiss = { currentAssetId ->
                    vm.lastViewedAssetId = currentAssetId
                    onBack()
                },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    }
}
