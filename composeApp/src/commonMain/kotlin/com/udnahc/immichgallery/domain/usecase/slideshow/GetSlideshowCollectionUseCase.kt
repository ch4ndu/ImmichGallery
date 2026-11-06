package com.udnahc.immichgallery.domain.usecase.slideshow

import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.SlideshowWindowSource
import com.udnahc.immichgallery.domain.usecase.album.GetAlbumDetailUseCase
import com.udnahc.immichgallery.domain.usecase.people.GetPersonAssetsUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetCurrentUserUseCase
import com.udnahc.immichgallery.domain.usecase.search.GetFavoriteAssetsUseCase
import com.udnahc.immichgallery.domain.usecase.search.SearchAssetsUseCase
import com.udnahc.immichgallery.runSuspendCatching

class GetSlideshowCollectionUseCase(
    private val album: GetAlbumDetailUseCase,
    private val person: GetPersonAssetsUseCase,
    private val search: SearchAssetsUseCase,
    private val favorites: GetFavoriteAssetsUseCase,
    private val currentUser: GetCurrentUserUseCase
) {
    suspend operator fun invoke(source: SlideshowWindowSource): Result<List<Asset>> = runSuspendCatching {
        val assets = when (source) {
            is SlideshowWindowSource.Album -> checkNotNull(album.syncAll(source.id).getOrThrow().completeAssets)
            is SlideshowWindowSource.Person -> checkNotNull(person.syncAll(source.id).getOrThrow().completeAssets)
            is SlideshowWindowSource.Snapshot -> source.assets
            is SlideshowWindowSource.Search -> buildList {
                val consumed = mutableSetOf<Int>()
                var page = 1
                while (true) {
                    check(consumed.add(page))
                    val result = search(source.endpoint, source.criteria, page).getOrThrow()
                    addAll(result.assets)
                    val next = result.nextPage ?: break
                    check(next > page && next !in consumed)
                    page = next
                }
            }
            SlideshowWindowSource.Favorites -> buildList {
                val owner = currentUser().getOrThrow().id
                var consumed = emptySet<Int>()
                var page = 1
                while (true) {
                    val result = favorites(owner, page, consumed).getOrThrow()
                    addAll(result.assets)
                    consumed = result.consumedPages
                    page = result.nextPage ?: break
                }
            }
            is SlideshowWindowSource.Timeline -> error("Timeline requires lazy cached buckets")
        }
        assets.distinctBy(Asset::id).toList()
    }
}
