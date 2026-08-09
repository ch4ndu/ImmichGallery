package com.udnahc.immichgallery.ui.navigation

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
object LoginRoute

@Serializable
object MainRoute

@Serializable
object TimelineRoute

@Serializable
object AlbumsRoute

@Serializable
object PeopleRoute

@Serializable
object ExploreRoute

@Serializable
object FavoritesRoute

@Serializable
object MemoriesRoute

@Immutable
@Serializable
data class AlbumDetailRoute(val albumId: String)

@Immutable
@Serializable
data class PersonDetailRoute(
    val personId: String,
    val personName: String
)
