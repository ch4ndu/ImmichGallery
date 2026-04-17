package com.udnahc.immichgallery.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.udnahc.immichgallery.data.repository.ServerStatusRepository
import kotlin.math.roundToInt
import org.koin.compose.koinInject
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.ui.screen.album.AlbumListScreen
import com.udnahc.immichgallery.ui.screen.detail.PhotoDetailScreen
import com.udnahc.immichgallery.ui.screen.people.PeopleScreen
import com.udnahc.immichgallery.ui.screen.search.SearchScreen
import com.udnahc.immichgallery.ui.screen.timeline.TimelineScreen
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.ic_albums
import immichgallery.composeapp.generated.resources.ic_more_vert
import immichgallery.composeapp.generated.resources.ic_refresh
import immichgallery.composeapp.generated.resources.timeline_group_day
import immichgallery.composeapp.generated.resources.timeline_group_month
import immichgallery.composeapp.generated.resources.ic_people
import immichgallery.composeapp.generated.resources.ic_search
import immichgallery.composeapp.generated.resources.ic_timeline
import immichgallery.composeapp.generated.resources.logout
import immichgallery.composeapp.generated.resources.tab_albums
import immichgallery.composeapp.generated.resources.tab_people
import immichgallery.composeapp.generated.resources.tab_search
import immichgallery.composeapp.generated.resources.tab_timeline
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private const val BAR_ALPHA = 0.8f

@androidx.compose.runtime.Immutable
data class BottomNavItem(
    val labelRes: StringResource,
    val route: Any,
    val iconRes: DrawableResource
)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MainScreen(
    onAlbumClick: (albumId: String) -> Unit,
    onPersonClick: (personId: String, personName: String) -> Unit,
    onLogout: () -> Unit
) {
    val serverStatusRepository: ServerStatusRepository = koinInject()
    val isServerOnline by serverStatusRepository.isOnline.collectAsState()

    LaunchedEffect(Unit) {
        serverStatusRepository.startMonitoring(this)
    }

    val tabNavController = rememberNavController()
    val navBackStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val coroutineScope = rememberCoroutineScope()

    val bottomNavItems = remember {
        listOf(
            BottomNavItem(Res.string.tab_timeline, TimelineRoute, Res.drawable.ic_timeline),
            BottomNavItem(Res.string.tab_albums, AlbumsRoute, Res.drawable.ic_albums),
            BottomNavItem(Res.string.tab_people, PeopleRoute, Res.drawable.ic_people),
            BottomNavItem(Res.string.tab_search, SearchRoute, Res.drawable.ic_search)
        )
    }

    val selectedIndex = remember(currentDestination) {
        bottomNavItems.indexOfFirst { item ->
            currentDestination?.hierarchy?.any { it.hasRoute(item.route::class) } == true
        }.coerceAtLeast(0)
    }
    val currentTabTitle = bottomNavItems.getOrNull(selectedIndex)?.labelRes
        ?.let { stringResource(it) } ?: ""

    // Derive overlay active from nav state — no callback needed
    val overlayActive = remember(currentDestination) {
        currentDestination?.hasRoute(PhotoDetailRoute::class) == true
    }

    var timelineGroupSize by remember { mutableStateOf(TimelineGroupSize.MONTH) }

    val isTimelineTab = remember(currentDestination) {
        currentDestination?.hierarchy?.any { it.hasRoute(TimelineRoute::class) } == true
    }
    val isSearchTab = remember(currentDestination) {
        currentDestination?.hierarchy?.any { it.hasRoute(SearchRoute::class) } == true
    }

    // Per-tab refresh callbacks and syncing state — set by each screen
    var tabRefreshCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var tabIsSyncing by remember { mutableStateOf(false) }

    val barColor = MaterialTheme.colorScheme.background.copy(alpha = BAR_ALPHA)

    Box(modifier = Modifier.fillMaxSize()) {
        SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = tabNavController,
                startDestination = TimelineRoute
            ) {
                composable<TimelineRoute> {
                    TimelineScreen(
                        groupSize = timelineGroupSize,
                        onPhotoClick = { assetId ->
                            coroutineScope.launch {
                                tabNavController.navigate(PhotoDetailRoute("timeline", assetId))
                            }
                        },
                        onPersonClick = onPersonClick,
                        onRefreshCallback = { callback -> tabRefreshCallback = callback },
                        onSyncingState = { syncing -> tabIsSyncing = syncing },
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@composable
                    )
                }
                composable<AlbumsRoute> {
                    AlbumListScreen(
                        onAlbumClick = onAlbumClick,
                        onRefreshCallback = { callback -> tabRefreshCallback = callback },
                        onSyncingState = { syncing -> tabIsSyncing = syncing }
                    )
                }
                composable<PeopleRoute> {
                    PeopleScreen(
                        onPersonClick = onPersonClick,
                        onRefreshCallback = { callback -> tabRefreshCallback = callback },
                        onSyncingState = { syncing -> tabIsSyncing = syncing }
                    )
                }
                composable<SearchRoute> {
                    SearchScreen(
                        onPhotoClick = { assetId ->
                            tabNavController.navigate(PhotoDetailRoute("search", assetId))
                        },
                        onPersonClick = onPersonClick,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@composable
                    )
                }
                composable<PhotoDetailRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<PhotoDetailRoute>()
                    PhotoDetailScreen(
                        route = route,
                        tabNavController = tabNavController,
                        onBack = { tabNavController.popBackStack() },
                        onPersonClick = onPersonClick,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@composable
                    )
                }
            }
        }

        // Top bar overlay — animated hide/show
        AnimatedVisibility(
            visible = !overlayActive,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            TopBarOverlay(
                title = currentTabTitle,
                barColor = barColor,
                isServerOnline = isServerOnline,
                isSyncing = tabIsSyncing,
                showRefresh = !isSearchTab,
                onRefresh = { tabRefreshCallback?.invoke() },
                onLogout = onLogout,
                trailingContent = if (isTimelineTab) {
                    {
                        TimelineGroupDropdown(
                            selected = timelineGroupSize,
                            onSelected = { newSize ->
                                timelineGroupSize = newSize
                            }
                        )
                    }
                } else null
            )
        }

        // Bottom bar overlay — animated hide/show with slide
        AnimatedVisibility(
            visible = !overlayActive,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            BottomBarOverlay(
                items = bottomNavItems,
                selectedIndex = selectedIndex,
                barColor = barColor,
                onItemClick = { item ->
                    tabNavController.navigate(item.route) {
                        popUpTo(tabNavController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}

@Composable
private fun TopBarOverlay(
    title: String,
    barColor: androidx.compose.ui.graphics.Color,
    isServerOnline: Boolean,
    isSyncing: Boolean,
    showRefresh: Boolean,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    trailingContent: @Composable (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRefreshDialog by remember { mutableStateOf(false) }

    if (showRefreshDialog) {
        AlertDialog(
            onDismissRequest = { showRefreshDialog = false },
            title = { Text("Refresh data") },
            text = { Text("Refresh all $title data from server?") },
            confirmButton = {
                TextButton(onClick = {
                    showRefreshDialog = false
                    onRefresh()
                }) {
                    Text("Refresh")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRefreshDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(barColor)
            .statusBarsPadding()
            .height(Dimens.topBarHeight)
            .padding(horizontal = Dimens.screenPadding)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.align(Alignment.CenterStart)
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (trailingContent != null) {
                trailingContent()
            }

            // Refresh button (spinning when syncing)
            if (showRefresh) {
                if (isSyncing) {
                    val infiniteTransition = rememberInfiniteTransition()
                    val rotation by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        )
                    )
                    IconButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.size(Dimens.topBarHeight)
                    ) {
                        Icon(
                            painterResource(Res.drawable.ic_refresh),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            modifier = Modifier.graphicsLayer { rotationZ = rotation }
                        )
                    }
                } else {
                    IconButton(
                        onClick = { showRefreshDialog = true },
                        modifier = Modifier.size(Dimens.topBarHeight)
                    ) {
                        Icon(
                            painterResource(Res.drawable.ic_refresh),
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }

            // Server status indicator
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (isServerOnline) Color(0xFF4CAF50) else Color(0xFFEF5350))
            )

            Spacer(Modifier.width(4.dp))

            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(Dimens.topBarHeight)) {
                    Icon(
                        painterResource(Res.drawable.ic_more_vert),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.logout)) },
                        onClick = {
                            showMenu = false
                            onLogout()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineGroupDropdown(
    selected: TimelineGroupSize,
    onSelected: (TimelineGroupSize) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = stringResource(
                    when (selected) {
                        TimelineGroupSize.MONTH -> Res.string.timeline_group_month
                        TimelineGroupSize.DAY -> Res.string.timeline_group_day
                    }
                ),
                style = MaterialTheme.typography.labelLarge
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.timeline_group_month)) },
                onClick = {
                    onSelected(TimelineGroupSize.MONTH)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.timeline_group_day)) },
                onClick = {
                    onSelected(TimelineGroupSize.DAY)
                    expanded = false
                }
            )
        }
    }
}

private const val INDICATOR_ALPHA = 0.15f
private const val UNSELECTED_ALPHA = 0.6f

@Composable
private fun BottomBarOverlay(
    items: List<BottomNavItem>,
    selectedIndex: Int,
    barColor: androidx.compose.ui.graphics.Color,
    onItemClick: (BottomNavItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var barWidthPx by remember { mutableStateOf(0f) }
    val tabWidthPx = if (items.isNotEmpty()) barWidthPx / items.size else 0f

    val indicatorOffsetFraction by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = Spring.StiffnessMediumLow
        )
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(barColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* consume clicks so they don't pass through to content */ }
            .navigationBarsPadding()
            .onSizeChanged { barWidthPx = it.width.toFloat() }
    ) {
        // Sliding pill indicator
        if (tabWidthPx > 0f) {
            val indicatorWidthDp = with(density) { (tabWidthPx * 0.6f).toDp() }
            val indicatorPaddingPx = tabWidthPx * 0.2f // center the pill within the tab
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (indicatorOffsetFraction * tabWidthPx + indicatorPaddingPx).roundToInt(),
                            y = 0
                        )
                    }
                    .width(indicatorWidthDp)
                    .height(Dimens.bottomBarHeight)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = INDICATOR_ALPHA))
            )
        }

        // Tab icons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.bottomBarHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val label = stringResource(item.labelRes)
                val tint by animateColorAsState(
                    targetValue = if (index == selectedIndex) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = UNSELECTED_ALPHA)
                    }
                )
                val onClick = remember(index) { { onItemClick(item) } }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = onClick) {
                        Icon(
                            painterResource(item.iconRes),
                            contentDescription = label,
                            tint = tint
                        )
                    }
                }
            }
        }
    }
}
