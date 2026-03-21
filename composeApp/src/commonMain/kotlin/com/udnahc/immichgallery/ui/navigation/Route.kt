package com.udnahc.immichgallery.ui.navigation

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
object SearchRoute

@Serializable
data class AlbumDetailRoute(val albumId: String)

@Serializable
data class PersonDetailRoute(
    val personId: String,
    val personName: String
)
