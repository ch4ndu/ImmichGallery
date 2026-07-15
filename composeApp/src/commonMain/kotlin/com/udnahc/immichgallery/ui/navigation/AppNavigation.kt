package com.udnahc.immichgallery.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.udnahc.immichgallery.ui.screen.album.AlbumDetailScreen
import com.udnahc.immichgallery.ui.screen.login.LoginScreen
import com.udnahc.immichgallery.ui.screen.people.PersonDetailScreen
import org.koin.compose.viewmodel.koinViewModel
import org.lighthousegames.logging.logging

private val log = logging("AppNavigation")

@Composable
fun AppNavigation() {
    val viewModel: AppStartupViewModel = koinViewModel()
    val state by viewModel.state.collectAsState()

    when (state.destination) {
        AppStartupDestination.Loading -> AppNavigationLoading()
        AppStartupDestination.Login -> AppNavigationHost(
            startDestination = LoginRoute,
            showLocalNetworkPermissionMessage = state.showLocalNetworkPermissionMessage
        )

        AppStartupDestination.Main -> AppNavigationHost(startDestination = MainRoute)
    }
}

@Composable
private fun AppNavigationLoading() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun AppNavigationHost(
    startDestination: Any,
    showLocalNetworkPermissionMessage: Boolean = false
) {
    val navController = rememberNavController()
    log.d { "AppNavigation: startDestination=$startDestination" }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() }
    ) {
        composable<LoginRoute> {
            LoginScreen(
                showLocalNetworkPermissionMessage = showLocalNetworkPermissionMessage,
                onLoginSuccess = {
                    navController.navigate(MainRoute) {
                        popUpTo<LoginRoute> { inclusive = true }
                    }
                }
            )
        }
        composable<MainRoute> {
            MainScreen(
                onAlbumClick = { albumId ->
                    navController.navigate(AlbumDetailRoute(albumId))
                },
                onPersonClick = { personId, personName ->
                    navController.navigate(PersonDetailRoute(personId, personName))
                },
                onLogout = {
                    navController.navigate(LoginRoute) {
                        popUpTo<MainRoute> { inclusive = true }
                    }
                }
            )
        }
        composable<AlbumDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<AlbumDetailRoute>()
            AlbumDetailScreen(
                albumId = route.albumId,
                onBack = { navController.popBackStack() },
                onPersonClick = { personId, personName ->
                    navController.navigate(PersonDetailRoute(personId, personName))
                },
            )
        }
        composable<PersonDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<PersonDetailRoute>()
            PersonDetailScreen(
                personId = route.personId,
                personName = route.personName,
                onBack = { navController.popBackStack() },
                onPersonClick = { personId, personName ->
                    navController.navigate(PersonDetailRoute(personId, personName))
                },
            )
        }
    }
}
