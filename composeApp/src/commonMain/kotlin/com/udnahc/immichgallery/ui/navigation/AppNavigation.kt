package com.udnahc.immichgallery.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.compose.NavHost
import kotlinx.coroutines.launch
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.udnahc.immichgallery.domain.action.auth.ClearServerConfigAction
import com.udnahc.immichgallery.domain.usecase.auth.GetLoginStatusUseCase
import com.udnahc.immichgallery.ui.screen.album.AlbumDetailScreen
import com.udnahc.immichgallery.ui.screen.login.LoginScreen
import com.udnahc.immichgallery.ui.screen.people.PersonDetailScreen
import org.koin.compose.koinInject
import org.lighthousegames.logging.logging

private val log = logging("AppNavigation")

@Composable
fun AppNavigation() {
    val getLoginStatusUseCase: GetLoginStatusUseCase = koinInject()
    val clearServerConfigAction: ClearServerConfigAction = koinInject()
    val coroutineScope = rememberCoroutineScope()
    val navController = rememberNavController()
    val isLoggedIn = getLoginStatusUseCase()
    log.d { "AppNavigation: isLoggedIn=$isLoggedIn" }
    val startDestination: Any = if (isLoggedIn) MainRoute else LoginRoute

    NavHost(
        navController = navController,
        startDestination = startDestination,
//        enterTransition = { fadeIn() },
//        exitTransition = { fadeOut() },
//        popEnterTransition = { fadeIn() },
//        popExitTransition = { fadeOut() }
    ) {
        composable<LoginRoute> {
            LoginScreen(
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
                    coroutineScope.launch {
                        clearServerConfigAction()
                    }
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
