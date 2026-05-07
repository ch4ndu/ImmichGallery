# ImmichGallery Architecture

Load this when working on architecture, source layout, dependency flow, or cross-layer behavior.

For Mosaic-specific rendering, cache artifact, scheduling, fallback, and placeholder rules, load `docs/ai/mosaic-rendering.md`.
For Album Detail and Person Detail screen architecture, cached-first loading, opened-owner sync, pagination, overlay return, and shared detail layout coordination, load `docs/ai/album-person-detail.md`.

## Stack

- Kotlin Multiplatform Compose targeting Android, iOS, and JVM Desktop.
- Immich API client for a read-only authenticated gallery. API auth uses the `x-api-key` header.
- Ktor 3 HTTP client with per-platform engines: OkHttp, Darwin, and Java.
- Coil 3 authenticated image loading via `KtorNetworkFetcherFactory`.
- Room database with `BundledSQLiteDriver` and KSP code generation for local cache tables.
- Koin DI with `sharedModule` in common code and per-platform `platformModule` database builders.
- JetBrains Navigation Compose KMP with `@Serializable` type-safe routes.
- `multiplatform-settings` for server URL, API key, and lightweight settings.
- `kotlinx-datetime` for date/time logic.

## Layering

- Data layer owns API response models, Room entities, DAOs, repositories, database setup, HTTP setup, and cache write-through behavior.
- Domain layer owns read UseCases and write Actions.
- ViewModels are per screen and inject UseCases/Actions, never repositories.
- UI renders state and sends events. Filtering, sorting, grouping, mapping, and row packing belong in UseCases or ViewModels, not composables.
- Expensive screen projections such as timeline display items, grouped rows, and detail-pager source lists belong in screen ViewModels.

## UseCases And Actions

- Reads use one UseCase class per read operation. Existing read UseCases generally expose `suspend operator fun invoke()` returning `Result<T>` or a direct value.
- Writes and side-effecting operations use one Action class per operation. Actions expose `suspend operator fun invoke(...)`.
- New features should create or reuse UseCases/Actions first, then wire them into the relevant ViewModel.
- Keep repositories behind UseCases/Actions. Screens should not inject repositories or UseCases directly unless the existing navigation/auth bootstrap pattern requires it.

## Source Layout

```text
composeApp/src/commonMain/kotlin/com/udnahc/immichgallery/
+-- data/
|   +-- local/      # Room DB, entities, DAOs, sync metadata
|   +-- model/      # Immich API response models
|   +-- remote/     # ImmichApiService, HTTP client factory and engines
|   +-- repository/ # Repositories wrapping API, Room, settings
+-- domain/
|   +-- model/      # Domain models, mappers, row packing utilities
|   +-- usecase/    # One class per read, organized by feature/entity
|   +-- action/     # One class per write or side effect
+-- ui/
|   +-- component/  # Reusable composables
|   +-- navigation/ # Routes, AppNavigation, MainScreen
|   +-- screen/     # Per-feature screens and ViewModels
|   +-- theme/      # Material3 theme and dimensions
|   +-- util/       # Gesture, transition, platform UI helpers
+-- di/             # Koin modules
```

Platform directories are `androidMain/`, `iosMain/`, and `jvmMain/`. Use `expect`/`actual` only when shared common code cannot reasonably handle the behavior.

## Key Files

| Purpose | Path |
| --- | --- |
| App entry and Koin/Coil setup | `App.kt` |
| Navigation routes and shell | `ui/navigation/Route.kt`, `ui/navigation/AppNavigation.kt`, `ui/navigation/MainScreen.kt` |
| ViewModels | `ui/screen/{feature}/*ViewModel.kt`, `ui/navigation/MainScreenViewModel.kt` |
| UseCases and Actions | `domain/usecase/`, `domain/action/` |
| Immich API service | `data/remote/ImmichApiService.kt` |
| API response models | `data/model/` |
| Domain models and mappers | `domain/model/` |
| Room database | `data/local/AppDatabase.kt` |
| Room DAOs and entities | `data/local/dao/`, `data/local/entity/` |
| Repositories | `data/repository/` |
| DI modules | `di/AppModule.kt`, `di/PlatformModule.kt` |
| Image loader factory | `di/ImageLoaderFactory.kt` |
| Photo grid layout utilities | `domain/model/RowPacking.kt`, `domain/model/MosaicPacking.kt`, `domain/model/TimelineDisplayItem.kt` |
| RowPacking architecture | `docs/ai/row-packing.md` |
| Timeline scroll targeting | `domain/model/TimelineScrollTargeting.kt` |
| Timeline rendering guide | `docs/timeline.md` |
| Timeline sync architecture | `docs/ai/timeline-sync.md` |
| Album/Person detail architecture | `docs/ai/album-person-detail.md` |
| Persisted view settings | `domain/model/ViewConfig.kt`, `data/repository/ServerConfigRepository.kt` |
| Scrollbar component | `ui/component/ScrollbarOverlay.kt` |
| Photo overlay/detail components | `ui/component/StaticPhotoOverlay.kt`, `ui/component/AssetPageContent.kt` |
| Version catalog | `gradle/libs.versions.toml` |

## Room Write-Through Cache

- Timeline, album, people, and asset detail data are cached in Room where the repository supports it.
- Album Detail and Person Detail use cached-first loading: if Room already has detail assets, render them immediately, refresh the opened detail in the background, and invalidate row/Mosaic layout only when ordered persisted asset content changes.
- Person detail asset caching starts when a person is opened. The People list sync must not preload every person's detail assets.
- Repository flow is network fetch, Room upsert or replace, then UI reads from Room or updated domain state.
- Timeline uses `TimelineBucketEntity`, `AssetEntity`, and `TimelineAssetCrossRef`.
- Album and people relationships use `AlbumAssetCrossRef` and `PersonAssetCrossRef`.
- Album and Person Mosaic display-cache rows use `DetailMosaicDisplayCacheEntity`.
  They are owner-scoped by type/id and keyed by group size, section index/key,
  enabled families, ordered asset fingerprint, rounded width/cell/max-row/
  spacing geometry keys, and display version. This table is separate from the
  Timeline Mosaic tables because Timeline cache is sync-bucket oriented.
- `SyncMetadataEntity` tracks cache freshness scopes such as timeline buckets.
- Platform database builders use `BundledSQLiteDriver`, `setQueryCoroutineContext(Dispatchers.IO)`, and `fallbackToDestructiveMigration(true)` because the database is a re-fetchable cache.

## Photo Grid And Overlay

- RowPacking is the standard justified-row architecture. `packIntoRows()` groups ordered assets into `RowItem`s, and the full algorithm, invalidation model, and screen rules live in `docs/ai/row-packing.md`.
- Mosaic is the shared non-standard-row layout architecture. `MosaicRenderEngine` owns assignment, progressive chunks, display projection, completed fallback rows, section geometry, and aggregate geometry. Cross-screen rules live in `docs/ai/mosaic-rendering.md`; assignment details live in `docs/ai/mosaic-assignment.md`; runtime scheduling lives in `docs/ai/mosaic-runtime.md`.
- Timeline sync is bucket-oriented and intentionally separate from Timeline rendering. Cold sync, warm launch, manual refresh, no-op refresh, and cache-off Mosaic policy live in `docs/ai/timeline-sync.md`.
- Timeline rendering, scrollbar targeting, display indexes, overlay return targeting, and Timeline-specific cache/rendering behavior live in `docs/timeline.md`.
- Album Detail and Person Detail cached-first loading, owner sync, pagination, shared layout coordination, Mosaic scheduling, persistent owner artifacts, and overlay return targeting live in `docs/ai/album-person-detail.md`.
- `PhotoGridDisplayItem` is the shared display model for headers, rows, placeholders, errors, and Mosaic bands.
- Grid ViewModels own measured width, row-height bounds, persisted `ViewConfig`, display items, and expensive projection orchestration. Composables render display items and report layout/visibility events.
- `PhotoGridLayoutRunner` coordinates cancellable/debounced zoom-driven projection work. It does not own domain layout math.
- `BoxWithConstraints` plus screen callbacks measure width and pass it to the ViewModel.
- `pinchToZoomRowHeight` in `ui/util/PinchToZoom.kt` is the shared grid zoom modifier.
- Mosaic controls belong only on screens that display photo assets directly, such as Timeline, Album Detail, and Person Detail.
- Shared element transitions use `SharedTransitionLayout`, matching `sharedBounds` keys, and the `lastSelectedAssetId` pattern so overlay content survives exit animation.
- `StaticPhotoOverlay` and `TimelinePhotoOverlay` own detail paging, slideshow, and drag-to-dismiss behavior.
- `KenBurnsImage` powers slideshow animation. `ScreenWakeLock` is expect/actual for keeping the screen on.

## Pagination And Scrollbar

- Search uses page tracking in `SearchState` and near-end detection from lazy layout state.
- People detail uses page-fetch UseCases and load-more state.
- Guard duplicate pagination with `isLoadingMore`; stop at `hasMore == false`.
- `ScrollbarOverlay` supports lazy list, grid, and staggered grid states. Screens must pass explicit top and bottom padding that accounts for translucent bars.
- Timeline scrollbar labels, year markers, handle position, and drag targets use `TimelinePageIndex` and photo-page fractions. Keep those mappings aligned; do not mix page fractions with raw LazyColumn item-count fractions.
- Timeline-specific cache, sync, Mosaic, scrollbar, overlay, or rendering changes must update `docs/timeline.md`.

## Key Tech Stack

- Kotlin 2.3.0, Compose Multiplatform 1.10.0.
- Material3 for theming.
- Room 2.8.4, SQLite Bundled 2.6.2.
- KSP 2.3.6.
- Ktor 3.1.3.
- Coil 3.1.0 with Ktor3 networking.
- Koin 4.1.0.
- Android: minSdk 24, targetSdk 36, JVM target 11.
- Package: `com.udnahc.immichgallery`.
