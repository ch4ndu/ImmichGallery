package com.udnahc.immichgallery.ui.navigation

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavHostController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.udnahc.immichgallery.LocalAppActive
import com.udnahc.immichgallery.domain.model.TimelineGroupSize
import com.udnahc.immichgallery.domain.model.ViewConfig
import com.udnahc.immichgallery.ui.component.MosaicViewConfigMenuItem
import com.udnahc.immichgallery.ui.component.mosaicColumnOptionsForWindow
import com.udnahc.immichgallery.ui.screen.album.AlbumListScreen
import com.udnahc.immichgallery.ui.screen.people.PeopleScreen
import com.udnahc.immichgallery.ui.screen.search.SearchScreen
import com.udnahc.immichgallery.ui.screen.timeline.TimelineScreen
import com.udnahc.immichgallery.ui.model.asTextOrNull
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.systemBarFadeIn
import com.udnahc.immichgallery.ui.util.systemBarFadeOut
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.cancel
import immichgallery.composeapp.generated.resources.ic_albums
import immichgallery.composeapp.generated.resources.ic_more_vert
import immichgallery.composeapp.generated.resources.ic_people
import immichgallery.composeapp.generated.resources.ic_refresh
import immichgallery.composeapp.generated.resources.ic_search
import immichgallery.composeapp.generated.resources.ic_timeline
import immichgallery.composeapp.generated.resources.logout
import immichgallery.composeapp.generated.resources.more_options
import immichgallery.composeapp.generated.resources.refresh
import immichgallery.composeapp.generated.resources.refresh_dialog_message
import immichgallery.composeapp.generated.resources.refresh_dialog_title
import immichgallery.composeapp.generated.resources.server_offline
import immichgallery.composeapp.generated.resources.server_online
import immichgallery.composeapp.generated.resources.tab_albums
import immichgallery.composeapp.generated.resources.tab_people
import immichgallery.composeapp.generated.resources.tab_search
import immichgallery.composeapp.generated.resources.tab_timeline
import immichgallery.composeapp.generated.resources.timeline_group_day
import immichgallery.composeapp.generated.resources.timeline_group_month
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

private const val BAR_ALPHA = 0.8f

@androidx.compose.runtime.Immutable
data class BottomNavItem(
    val labelRes: StringResource,
    val route: Any,
    val iconRes: DrawableResource
)

@Composable
fun MainScreen(
    onAlbumClick: (albumId: String) -> Unit,
    onPersonClick: (personId: String, personName: String) -> Unit,
    onLogout: () -> Unit,
    viewModel: MainScreenViewModel = koinViewModel()
) {
    val isAppActive = LocalAppActive.current
    DisposableEffect(viewModel, isAppActive) {
        if (isAppActive) viewModel.startServerStatusMonitoring()
        onDispose { viewModel.stopServerStatusMonitoring() }
    }
    val isServerOnline by viewModel.isServerOnline.collectAsState()
    val timelineGroupSize by viewModel.timelineGroupSize.collectAsState()
    val viewConfig by viewModel.viewConfig.collectAsState()
    val screenState by viewModel.screenState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val logoutMessage = screenState.message.asTextOrNull()

    LaunchedEffect(viewModel) {
        viewModel.logoutCompleted.collect { onLogout() }
    }
    LaunchedEffect(screenState.message) {
        val message = logoutMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    val tabNavController = rememberNavController()
    val navBackStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

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

    // Overlay active reported up from Timeline / Search via callback
    var overlayActive by remember { mutableStateOf(false) }

    val isTimelineTab = remember(currentDestination) {
        currentDestination?.hierarchy?.any { it.hasRoute(TimelineRoute::class) } == true
    }
    // Mosaic is only offered on screens that render photo assets directly.
    // AlbumList and PeopleList are collection grids, not photo-asset grids.
    val isSearchTab = remember(currentDestination) {
        currentDestination?.hierarchy?.any { it.hasRoute(SearchRoute::class) } == true
    }

    // Per-tab refresh callbacks and syncing state — set by each screen
    var tabRefreshCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var timelineMosaicPrepareCallback by remember {
        mutableStateOf<(suspend (ViewConfig) -> Result<Unit>)?>(null)
    }
    var tabIsSyncing by remember { mutableStateOf(false) }
    var timelineColdSyncBlocking by remember { mutableStateOf(false) }
    val onTabRefreshCallback = remember {
        { callback: (() -> Unit)? -> tabRefreshCallback = callback }
    }
    val onTabSyncingState = remember {
        { syncing: Boolean -> tabIsSyncing = syncing }
    }
    val onTimelineColdSyncBlockingState = remember {
        { blocking: Boolean -> timelineColdSyncBlocking = blocking }
    }
    val onTimelineMosaicPrepareCallback = remember {
        { callback: (suspend (ViewConfig) -> Result<Unit>)? ->
            timelineMosaicPrepareCallback = callback
        }
    }
    val onOverlayActiveChange = remember {
        { active: Boolean -> overlayActive = active }
    }

    val barColor = MaterialTheme.colorScheme.background.copy(alpha = BAR_ALPHA)
    val showShellOverlays = !overlayActive && !(isTimelineTab && timelineColdSyncBlocking)

    Box(modifier = Modifier.fillMaxSize()) {
        MainTabNavHost(
            navController = tabNavController,
            onAlbumClick = onAlbumClick,
            onPersonClick = onPersonClick,
            onRefreshCallback = onTabRefreshCallback,
            onSyncingState = onTabSyncingState,
            onColdSyncBlockingState = onTimelineColdSyncBlockingState,
            onMosaicPrepareCallback = onTimelineMosaicPrepareCallback,
            onOverlayActiveChange = onOverlayActiveChange
        )

        MainShellOverlays(
            navController = tabNavController,
            items = bottomNavItems,
            selectedIndex = selectedIndex,
            title = currentTabTitle,
            barColor = barColor,
            visible = showShellOverlays,
            isServerOnline = isServerOnline,
            isSyncing = tabIsSyncing,
            showRefresh = !isSearchTab,
            onRefresh = { tabRefreshCallback?.invoke() },
            onLogout = viewModel::logout,
            logoutEnabled = !screenState.isLoggingOut,
            mosaicViewConfig = viewConfig.takeIf { isTimelineTab },
            onMosaicViewConfigChanged = viewModel::setViewConfig,
            onPrepareMosaicViewConfig = { config ->
                timelineMosaicPrepareCallback?.invoke(config) ?: Result.success(Unit)
            },
            timelineGroupSize = timelineGroupSize,
            timelineGroupEnabled = !viewConfig.mosaicEnabled,
            onTimelineGroupSelected = viewModel::setTimelineGroupSize,
            showTimelineGroup = isTimelineTab
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = Dimens.bottomBarHeight)
        )
    }
}

@Composable
private fun MainTabNavHost(
    navController: NavHostController,
    onAlbumClick: (String) -> Unit,
    onPersonClick: (String, String) -> Unit,
    onRefreshCallback: (((() -> Unit)?) -> Unit),
    onSyncingState: (Boolean) -> Unit,
    onColdSyncBlockingState: (Boolean) -> Unit,
    onMosaicPrepareCallback: (((suspend (ViewConfig) -> Result<Unit>)?) -> Unit),
    onOverlayActiveChange: (Boolean) -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = TimelineRoute
    ) {
        composable<TimelineRoute> {
            TimelineScreen(
                onPersonClick = onPersonClick,
                onRefreshCallback = onRefreshCallback,
                onSyncingState = onSyncingState,
                onColdSyncBlockingState = onColdSyncBlockingState,
                onMosaicPrepareCallback = onMosaicPrepareCallback,
                onOverlayActiveChange = onOverlayActiveChange,
            )
        }
        composable<AlbumsRoute> {
            AlbumListScreen(
                onAlbumClick = onAlbumClick,
                onRefreshCallback = onRefreshCallback,
                onSyncingState = onSyncingState
            )
        }
        composable<PeopleRoute> {
            PeopleScreen(
                onPersonClick = onPersonClick,
                onRefreshCallback = onRefreshCallback,
                onSyncingState = onSyncingState
            )
        }
        composable<SearchRoute> {
            SearchScreen(
                onPersonClick = onPersonClick,
                onOverlayActiveChange = onOverlayActiveChange,
            )
        }
    }
}

@Composable
private fun BoxScope.MainShellOverlays(
    navController: NavHostController,
    items: List<BottomNavItem>,
    selectedIndex: Int,
    title: String,
    barColor: Color,
    visible: Boolean,
    isServerOnline: Boolean,
    isSyncing: Boolean,
    showRefresh: Boolean,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    logoutEnabled: Boolean,
    mosaicViewConfig: ViewConfig?,
    onMosaicViewConfigChanged: (ViewConfig) -> Unit,
    onPrepareMosaicViewConfig: suspend (ViewConfig) -> Result<Unit>,
    timelineGroupSize: TimelineGroupSize,
    timelineGroupEnabled: Boolean,
    onTimelineGroupSelected: (TimelineGroupSize) -> Unit,
    showTimelineGroup: Boolean
) {
    AnimatedVisibility(
        visible = visible,
        enter = systemBarFadeIn,
        exit = systemBarFadeOut
    ) {
        TopBarOverlay(
            title = title,
            barColor = barColor,
            isServerOnline = isServerOnline,
            isSyncing = isSyncing,
            showRefresh = showRefresh,
            onRefresh = onRefresh,
            onLogout = onLogout,
            logoutEnabled = logoutEnabled,
            mosaicViewConfig = mosaicViewConfig,
            onMosaicViewConfigChanged = onMosaicViewConfigChanged,
            onPrepareMosaicViewConfig = onPrepareMosaicViewConfig,
            trailingContent = if (showTimelineGroup) {
                {
                    TimelineGroupDropdown(
                        selected = timelineGroupSize,
                        enabled = timelineGroupEnabled,
                        onSelected = onTimelineGroupSelected
                    )
                }
            } else null
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = systemBarFadeIn,
        exit = systemBarFadeOut,
        modifier = Modifier.align(Alignment.BottomCenter)
    ) {
        BottomBarOverlay(
            items = items,
            selectedIndex = selectedIndex,
            barColor = barColor,
            onItemClick = { item ->
                navController.navigate(item.route) {
                    popUpTo(navController.graph.startDestinationId) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        )
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
    logoutEnabled: Boolean,
    mosaicViewConfig: ViewConfig? = null,
    onMosaicViewConfigChanged: (ViewConfig) -> Unit = {},
    onPrepareMosaicViewConfig: suspend (ViewConfig) -> Result<Unit> = { Result.success(Unit) },
    trailingContent: @Composable (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRefreshDialog by remember { mutableStateOf(false) }
    val mosaicColumnOptions = mosaicColumnOptionsForWindow()

    if (showRefreshDialog) {
        AlertDialog(
            onDismissRequest = { showRefreshDialog = false },
            title = { Text(stringResource(Res.string.refresh_dialog_title)) },
            text = { Text(stringResource(Res.string.refresh_dialog_message, title)) },
            confirmButton = {
                TextButton(onClick = {
                    showRefreshDialog = false
                    onRefresh()
                }) {
                    Text(stringResource(Res.string.refresh))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRefreshDialog = false }) {
                    Text(stringResource(Res.string.cancel))
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
                            contentDescription = stringResource(Res.string.refresh),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }

            // Server status indicator
            val statusDescription = stringResource(
                if (isServerOnline) Res.string.server_online else Res.string.server_offline
            )
            Box(
                modifier = Modifier
                    .size(Dimens.serverStatusIndicatorSize)
                    .clip(CircleShape)
                    .background(if (isServerOnline) Color(0xFF4CAF50) else Color(0xFFEF5350))
                    .semantics { contentDescription = statusDescription }
            )

            Spacer(Modifier.width(Dimens.smallSpacing))

            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(Dimens.topBarHeight)
                ) {
                    Icon(
                        painterResource(Res.drawable.ic_more_vert),
                        contentDescription = stringResource(Res.string.more_options),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    if (mosaicViewConfig != null) {
                        MosaicViewConfigMenuItem(
                            viewConfig = mosaicViewConfig,
                            mosaicColumnOptions = mosaicColumnOptions,
                            onViewConfigChanged = onMosaicViewConfigChanged,
                            onPrepareViewConfig = onPrepareMosaicViewConfig,
                            onDismissMenu = { showMenu = false }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.logout)) },
                        enabled = logoutEnabled,
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
    enabled: Boolean,
    onSelected: (TimelineGroupSize) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            enabled = enabled
        ) {
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
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
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
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitPointerEvent(PointerEventPass.Final).changes.forEach { change ->
                        if (!change.isConsumed) change.consume()
                    }
                }
            }
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
                val onClick = remember(item, onItemClick) { { onItemClick(item) } }
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
