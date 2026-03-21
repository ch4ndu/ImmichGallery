package com.udnahc.immichgallery.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.udnahc.immichgallery.ui.screen.album.AlbumListScreen
import com.udnahc.immichgallery.ui.screen.people.PeopleScreen
import com.udnahc.immichgallery.ui.screen.search.SearchScreen
import com.udnahc.immichgallery.ui.screen.timeline.TimelineScreen
import com.udnahc.immichgallery.ui.theme.Dimens
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.ic_albums
import immichgallery.composeapp.generated.resources.ic_people
import immichgallery.composeapp.generated.resources.ic_search
import immichgallery.composeapp.generated.resources.ic_more_vert
import immichgallery.composeapp.generated.resources.ic_timeline
import immichgallery.composeapp.generated.resources.logout
import immichgallery.composeapp.generated.resources.tab_albums
import immichgallery.composeapp.generated.resources.tab_people
import immichgallery.composeapp.generated.resources.tab_search
import immichgallery.composeapp.generated.resources.tab_timeline

private const val BAR_ALPHA = 0.8f

data class BottomNavItem(
    val labelRes: StringResource,
    val route: Any,
    val iconRes: DrawableResource
)

@Composable
fun MainScreen(
    onAlbumClick: (albumId: String) -> Unit,
    onPersonClick: (personId: String, personName: String) -> Unit,
    onLogout: () -> Unit
) {
    val tabNavController = rememberNavController()
    val navBackStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val bottomNavItems = listOf(
        BottomNavItem(Res.string.tab_timeline, TimelineRoute, Res.drawable.ic_timeline),
        BottomNavItem(Res.string.tab_albums, AlbumsRoute, Res.drawable.ic_albums),
        BottomNavItem(Res.string.tab_people, PeopleRoute, Res.drawable.ic_people),
        BottomNavItem(Res.string.tab_search, SearchRoute, Res.drawable.ic_search)
    )

    val currentTabTitle = bottomNavItems
        .firstOrNull { item ->
            currentDestination?.hierarchy?.any { it.hasRoute(item.route::class) } == true
        }
        ?.labelRes
        ?.let { stringResource(it) }
        ?: ""

    var overlayActive by remember { mutableStateOf(false) }

    val barColor = MaterialTheme.colorScheme.background.copy(alpha = BAR_ALPHA)

    Box(modifier = Modifier.fillMaxSize()) {
        // Tab content — fills entire screen edge-to-edge
        NavHost(
            navController = tabNavController,
            startDestination = TimelineRoute
        ) {
            composable<TimelineRoute> {
                TimelineScreen(onOverlayActiveChanged = { overlayActive = it })
            }
            composable<AlbumsRoute> {
                AlbumListScreen(onAlbumClick = onAlbumClick)
            }
            composable<PeopleRoute> {
                PeopleScreen(onPersonClick = onPersonClick)
            }
            composable<SearchRoute> {
                SearchScreen(onOverlayActiveChanged = { overlayActive = it })
            }
        }

        // Top bar overlay — hide when photo overlay is active
        if (!overlayActive) {
            TopBarOverlay(
                title = currentTabTitle,
                barColor = barColor,
                onLogout = onLogout
            )
        }

        // Bottom bar overlay — hide when photo overlay is active
        if (!overlayActive) {
            NavigationBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                containerColor = barColor
            ) {
                bottomNavItems.forEach { item ->
                    val label = stringResource(item.labelRes)
                    val onClick = remember(item.route) {
                        {
                            tabNavController.navigate(item.route) {
                                popUpTo(tabNavController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                    NavigationBarItem(
                        icon = { Icon(painterResource(item.iconRes), contentDescription = label) },
                        label = null,
                        selected = currentDestination?.hierarchy?.any { it.hasRoute(item.route::class) } == true,
                        onClick = onClick
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBarOverlay(
    title: String,
    barColor: androidx.compose.ui.graphics.Color,
    onLogout: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

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
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            IconButton(onClick = { showMenu = true }) {
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
