package com.udnahc.immichgallery.ui.navigation

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
sealed interface Route

@Immutable
@Serializable
object LoginRoute : Route

@Immutable
@Serializable
object MainRoute : Route

@Immutable
@Serializable
object TimelineRoute : Route

@Immutable
@Serializable
object AlbumsRoute : Route

@Immutable
@Serializable
object PeopleRoute : Route

@Immutable
@Serializable
object ExploreRoute : Route

@Immutable
@Serializable
object FavoritesRoute : Route

@Immutable
@Serializable
object MemoriesRoute : Route

@Immutable
@Serializable
data class AlbumDetailRoute(val albumId: String) : Route

@Immutable
@Serializable
data class PersonDetailRoute(
    val personId: String,
    val personName: String
) : Route
