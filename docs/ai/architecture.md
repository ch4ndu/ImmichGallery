# ImmichGallery Architecture

Load this when working on architecture, source layout, dependency flow, or cross-layer behavior.

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
| Timeline scroll targeting | `domain/model/TimelineScrollTargeting.kt` |
| Timeline data/cache/rendering guide | `docs/timeline.md` |
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
- `SyncMetadataEntity` tracks cache freshness scopes such as timeline buckets.
- Platform database builders use `BundledSQLiteDriver`, `setQueryCoroutineContext(Dispatchers.IO)`, and `fallbackToDestructiveMigration(true)` because the database is a re-fetchable cache.

## Photo Grid And Overlay

- `packIntoRows()` in `domain/model/RowPacking.kt` groups assets into justified rows.
- `MosaicPacking.kt` computes optional Mosaic assignments for photo grids. Use the established Mosaic pipeline instead of adding unrelated ad hoc grid systems.
- Mosaic layout sizing flows through `MosaicLayoutSpec`: Album and Person detail use `targetRowHeight` to select the supported Mosaic column count, while Timeline uses the fixed Timeline Mosaic column policy. Mosaic bands, placeholders, and fallback rows use `availableWidth / columnCount` as the effective cell height.
- `PhotoGridDisplayItem` is the shared display model for headers, rows, placeholders, errors, and Mosaic bands.
- Grid ViewModels hold `targetRowHeight`, row-height bounds, `availableWidth`, persisted `ViewConfig`, and photo-grid display items. They compute layout atomically with data changes.
- `PhotoGridLayoutRunner` in `ui/util/` is the shared cancellable/debounced runner for expensive zoom-driven photo-grid projections. It coordinates coroutine timing only; domain layout math and screen display models stay in domain/ViewModel code.
- `MosaicWorkScheduler` in `ui/util/` is the shared priority gate for CPU-heavy Album Detail and Person Detail Mosaic assignment work. Foreground visible detail groups outrank foreground prefetch, and detail screens should reprioritize pending work on visible-group changes without treating scroll as a new layout generation.
- `BoxWithConstraints` plus `LaunchedEffect(maxWidth)` measures width and passes it to the ViewModel.
- `pinchToZoomRowHeight` in `ui/util/PinchToZoom.kt` is the shared grid zoom modifier.
- Timeline content invalidation is per bucket. Cached launch syncs bucket metadata only; cached Room refs and materialized grid rows are separate states. Keep bucket metadata available for scroll height, but read Room assets and build rows only for visible/nearby buckets unless this is the blocking first sync. A successful sync must not bump a global asset revision or repack unchanged buckets; only buckets whose ordered assets changed should invalidate derived rows and persisted Mosaic assignments. Removed buckets still clear cached refs immediately. Album and Person detail use the same ordered asset fingerprint idea for their single-screen asset revision.
- Timeline Mosaic assignments are precomputed after successful server sync for changed buckets, persisted in Room, and loaded by `TimelineViewModel` as display input. Persisted rows are keyed by bucket or section, group mode, fixed Timeline Mosaic column count, asset fingerprint, and enabled Mosaic families. Do not let assignments cross day-section boundaries. Runtime reads of persisted Mosaic rows should be scoped to materialized visible/nearby buckets and guarded by generation/config checks so stale cache reads cannot publish after the screen moves or the Mosaic config changes.
- Timeline Mosaic uses fixed columns while enabled: 4 on normal widths and 5 on large-width layouts. Pinch/desktop zoom should not change Timeline column count while Mosaic is enabled.
- Timeline width and Mosaic config changes must not recompute unchanged buckets. The ViewModel should load persisted rows for the requested config; if they are missing, keep the previous active Mosaic config when one exists, otherwise render Mosaic placeholders.
- Album and Person detail screens publish placeholder display items immediately while foreground Mosaic groups wait for the scheduler. Visible groups should be submitted before offscreen prefetch groups, pending work should be reprioritized when visibility changes, and stale layout generations must not publish results.
- Mosaic availability is controlled by persisted `ViewConfig`, supported column count, enabled template families, and asset count. Do not gate Mosaic by target row height or timeline group mode.
- Mosaic fallback rows are intentionally justified full-span rows, but they must use the named Mosaic fallback policy: disable wide-image promotion and require a minimum of two photos before row completion. Empty-assignment groups with 1-4 assets use a taller `2.0 * MosaicLayoutSpec.cellHeight` target, clamped by max row height, so small groups do not look like thumbnails. Larger empty-assignment groups use one cell height; fallback gaps around valid Mosaic bands use the larger of `0.75 * MosaicLayoutSpec.cellHeight` and `1.0` of the representative Mosaic band height. Do not clamp completed row height after packing because that breaks the row's aspect-ratio math; a final leftover single-photo row may remain incomplete and still visually span the row when its aspect ratio naturally fits.
- Mosaic fallback packing must respect valid Mosaic assignment boundaries. If a gap before the next Mosaic band cannot form a complete row, demote the next Mosaic band into fallback rows instead of emitting a non-final incomplete row.
- Timeline bucket-load display updates must stay immediate for shared-element return transitions; debounce only zoom/config layout work. `TimelineState` carries a precomputed display index for scroll targeting and visible-bucket detection so hot UI paths do not rescan `displayItems`; Album and Person detail use the same display-index pattern for visible Mosaic group detection. The Timeline detail overlay should collect a projected Timeline state containing only bucket/page data and keep using the shared `bucketAssetsCache` for transition continuity.
- Non-obvious cache, sync, and layout invalidation rules need nearby code comments that explain the invariant and the failure mode they prevent.
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
