# ImmichGallery Architecture

Load this when working on architecture, source layout, dependency flow, or cross-layer behavior.

For the system-level project map, module/platform ownership, build and version
surfaces, verification strategy, and architecture diagrams, read
`docs/ARCHI.md`. This document remains the focused source-layout and feature
ownership contract.

For Mosaic-specific rendering, cache artifact, scheduling, fallback, and placeholder rules, load `docs/ai/mosaic-rendering.md`.
For Album Detail and Person Detail screen architecture, cached-first loading, opened-owner sync, pagination, overlay return, and shared detail layout coordination, load `docs/ai/album-person-detail.md`.
For login, saved-session startup, local-network access, server monitoring, and logout, load `docs/ai/auth-session.md`.
For Album/People collection lists and Search, load `docs/ai/collection-lists-search.md`.
For the shared photo/video viewer, information sheet, slideshow, and return transitions, load `docs/ai/media-viewer.md`.

## Stack

- Kotlin Multiplatform Compose targeting Android, iOS, and JVM Desktop.
- Immich API client for a read-only authenticated gallery. Normal API auth uses
  an explicitly attached `x-api-key` header; automatic redirects are disabled.
- Ktor 3 HTTP client with per-platform engines: OkHttp, Darwin, and Java.
- Coil 3 authenticated image loading via an OkHttp fetcher on Android and
  `KtorNetworkFetcherFactory` on iOS/JVM.
- Koin and its singleton Coil controller are process-owned on every platform.
  Android starts them from `Application`; iOS and JVM start them before their
  Compose host. Recreated Compose roots therefore cannot open competing disk
  caches or retain stale controller dependencies. Android also normalizes the
  captured platform context to the application context, so Activity recreation
  cannot be retained.
- Room database with `BundledSQLiteDriver` and KSP code generation for local
  cache tables. iOS stores that re-fetchable cache in backup-excluded
  Application Support and relocates or removes the legacy Documents database
  file set before Room opens.
- Koin DI with `sharedModule` in common code and per-platform `platformModule` database builders.
- JetBrains Navigation Compose KMP with `@Serializable` type-safe routes.
- `multiplatform-settings` for server URL, API key, and lightweight settings;
  JVM uses an app-specific Java Preferences node with a guarded legacy-key
  migration.
- `kotlinx-datetime` for date/time logic.
- Android foreground work notifications are driven by common
  `SyncActivityTracker` keys for active sync and explicit Mosaic preparation.
  Tracker count changes and notifier callbacks are serialized, and count zero
  owns normal foreground removal. Android starts a `dataSync` foreground
  service for that state; an Android system timeout is terminal and immediately
  cancels service-owned manual jobs/demotes the timed-out instance. Manual
  Timeline refresh is service-owned on Android through the shared
  `TimelineManualSyncCoordinator`; other platforms run that coordinator from
  the ViewModel.
- Logout and changed-identity login share an application-wide session mutation
  boundary. It advances the generation, cancels and joins registered old work,
  rotates/clears Coil, clears server-scoped Room/Mosaic/runtime state, and only
  then clears or replaces configuration. A successful mutation also advances a
  long-lived owner epoch so callbacks from the old mounted Timeline/detail owner
  cannot submit post-clear work before navigation disposes it; a failed mutation
  keeps that owner epoch so the current UI can retry with the new generation.
  `MainScreenViewModel` owns logout action state and navigation success; a
  cache-clear failure keeps the current configuration, stays on Main, and
  surfaces a retryable message.
- Application and HTTP logging are disabled on every platform/build; retained
  call sites use non-sensitive operation labels only.
- iOS declares local-network access and allows local-network ATS traffic so self-hosted HTTP servers on the LAN remain usable; arbitrary remote HTTP is not enabled.

## Layering

- Data layer owns API response models, Room entities, DAOs, repositories, database setup, HTTP setup, and cache write-through behavior.
- Domain layer owns read UseCases and write Actions.
- ViewModels are per screen and inject UseCases/Actions, never repositories.
- UI renders state and sends events. Filtering, sorting, grouping, mapping, and row packing belong in UseCases or ViewModels, not composables.
- Expensive screen projections such as timeline display items, grouped rows, and detail-pager source lists belong in screen ViewModels.

## UseCases And Actions

- Reads use UseCase classes. Existing read UseCases generally expose `suspend operator fun invoke()` returning `Result<T>` or a direct value, or `observe*()` flows for Room-backed collections.
- Standalone writes and side-effecting operations use one Action class per operation. Actions expose `suspend operator fun invoke(...)`.
- Cached-resource `Get*UseCase` classes (`GetAlbumsUseCase`, `GetAlbumDetailUseCase`, `GetPeopleUseCase`, `GetPersonAssetsUseCase`, `GetPersonAssetsPageUseCase`, `GetTimelineBucketsUseCase`) may also expose repository `sync*` and cold-sync completion entry points alongside their read/observe methods when a single cached resource owns both observation and refresh.
- New features should create or reuse UseCases/Actions first, then wire them into the relevant ViewModel.
- Keep repositories behind UseCases/Actions. Screens should not inject repositories or UseCases directly unless the existing navigation/auth bootstrap pattern requires it.

## Feature Architecture Map

| Feature area | UI owner | State/domain boundary | Data boundary | Detailed contract |
| --- | --- | --- | --- | --- |
| Startup and login | `AppNavigation`, `LoginScreen` | `AppStartupViewModel`, `LoginViewModel`, auth UseCases/Actions | `ServerConfigRepository`, `AuthRepository`, platform local-network controller | `docs/ai/auth-session.md` |
| Main shell and session | `MainScreen` | `MainScreenViewModel` | server-status Actions/UseCase and cache-first logout Action | `docs/ai/auth-session.md` |
| Timeline | `TimelineScreen` | `TimelineViewModel`, Timeline UseCases/Actions | `TimelineRepository`, `TimelineMosaicCacheRepository`, Room | `docs/timeline.md`, `docs/ai/timeline-sync.md` |
| Albums and People lists | `AlbumListScreen`, `PeopleScreen` | list ViewModels and read UseCases | `AlbumRepository`, `PeopleRepository`, Room | `docs/ai/collection-lists-search.md` |
| Album/Person detail | detail screens | detail ViewModels and `PhotoGridDetailLayoutCoordinator` | detail UseCases, repositories, Room, Mosaic artifacts | `docs/ai/album-person-detail.md` |
| Explore/Search | `SearchScreen` | `SearchViewModel`, search UseCases, RowPacking | live `SearchRepository` API calls; results are not stored in Room | `docs/ai/collection-lists-search.md` |
| Favorites | `FavoritesScreen` | `FavoritesViewModel`, owner/Favorites UseCases, RowPacking | live auth and metadata-search calls; no Room collection | `docs/ai/collection-lists-search.md` |
| Memories | `MemoriesScreen` | `MemoriesViewModel`, date-scoped Memories UseCase | live `/api/memories` call; no Room collection | `docs/ai/collection-lists-search.md` |
| Photo/video viewing | overlay hosts and media components | `StaticPhotoOverlay` or `TimelineOverlayCoordinator` | asset-detail UseCase, Coil, platform video player | `docs/ai/media-viewer.md` |
| Viewer stack browsing | `StackSessionOverlay` inside existing viewer owners | `GetAssetStackUseCase` plus viewer-local session state | live `AssetRepository` asset/stack reads | `docs/ai/media-viewer.md` |
| Standard rows | shared grid components | `packIntoRows(...)` and screen projection owners | no persisted layout artifacts | `docs/ai/row-packing.md` |
| Mosaic grids | shared grid components | `MosaicRenderEngine` and screen schedulers/coordinators | Timeline/detail Mosaic artifact repositories | `docs/ai/mosaic-rendering.md` |

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
|   +-- usecase/    # Read and cached-resource sync UseCases, organized by feature/entity
|   +-- action/     # Standalone write or side-effect Actions
+-- ui/
|   +-- component/  # Reusable composables
|   +-- navigation/ # Routes, AppNavigation, MainScreen
|   +-- screen/     # Per-feature screens and ViewModels
|   +-- theme/      # Material3 theme and dimensions
|   +-- util/       # Gesture, transition, platform UI helpers
+-- di/             # Koin modules
```

Platform directories are `androidMain/`, `iosMain/`, and `jvmMain/`. Use `expect`/`actual` only when shared common code cannot reasonably handle the behavior.

`composeApp` is a KMP Android library, not the installable Android application. The thin `androidApp` module owns application ID/version/signing/packaging and the launcher manifest, then depends on `composeApp`. Android actuals, `MainActivity`, `ImmichGalleryApplication`, foreground sync service code/declaration, Room, and reusable Android resources remain in `composeApp`. `composeApp` uses AGP's dedicated `com.android.kotlin.multiplatform.library` target; Android resources, host tests, and device tests are explicitly enabled, and Room KSP runs for Android, JVM, and both iOS targets.

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
| Auth/session architecture | `docs/ai/auth-session.md` |
| Collection lists and Search architecture | `docs/ai/collection-lists-search.md` |
| Media viewer architecture | `docs/ai/media-viewer.md` |
| Persisted view settings | `domain/model/ViewConfig.kt`, `data/repository/ServerConfigRepository.kt` |
| Scrollbar component | `ui/component/ScrollbarOverlay.kt` |
| Photo overlay/detail components | `ui/component/StaticPhotoOverlay.kt`, `ui/component/AssetPageContent.kt` |
| Version catalog | `gradle/libs.versions.toml` |

## Room Write-Through Cache

- Timeline assets/buckets, Album and People lists, Album/Person asset
  relationships, sync metadata, and Mosaic artifacts are cached in Room.
  Explore Search results, Favorites, current-date Memories, the expanded
  asset-information response, and complete stack member lists are fetched live
  and are not persisted as their own Room records.
- Album Detail and Person Detail use cached-first loading: if Room already has detail assets, render them immediately, refresh the opened detail in the background, and invalidate row/Mosaic layout only when ordered persisted asset content changes.
- Person detail asset caching starts when a person is opened. The People list sync must not preload every person's detail assets.
- Repository flow is network fetch, Room upsert or replace, then UI reads from Room or updated domain state.
- Cache-producing logical work registers before its first read/request and
  propagates one immutable session stamp through deferred descendants. The
  stamp combines the admitted generation with the long-lived owner epoch where
  applicable. Repository writes validate both at the commit boundary so
  old-session work cannot repopulate cleared cache.
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
- Android foreground service state mirrors common foreground-work tracking for sync and explicit Mosaic preparation. Manual Timeline refresh is service-owned on Android through `SyncForegroundService`; cold sync, cached launch, visible refresh, detail sync, and render-demand Mosaic remain owned by their common ViewModels/coordinators.
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
- Their shared `SlideshowPlaybackController`/`SlideshowBinding` serializes
  playback decisions and publishes immutable snapshots; Compose owns pager and
  transition presentation. Device preferences pass through dedicated settings
  UseCase/Action and `ViewerPreferencesViewModel`, while media/history stays
  viewer-local. The desktop host owns fullscreen placement and scoped restoration.
- Viewer date actions finish host return preparation and exit before root/Main
  navigation delivers a unique ephemeral request to the existing Timeline date
  resolver. No second Timeline route or persistent navigation state is created.
- `StackSessionOverlay` is an ephemeral nested viewer. It reuses
  `StaticPhotoOverlay` with recursion disabled, while the covered base viewer
  keeps its initiating asset and source-grid return identity unchanged.
- `PhotoOverlayHost` defaults to returning to the settled asset. Memories opts
  into origin return so its cover remains the hidden/exit source while the
  static pager continues to report current assets for media behavior.
- `KenBurnsImage` powers slideshow animation. `ScreenWakeLock` is expect/actual
  for keeping the screen on. JVM implements that contract with balanced OS
  inhibition and closes its owned native thread/process on application exit.
  Desktop native packages require a validated builder-supplied VLC resource
  bundle from the current `<os>-<arch>` directory; development runs may fall
  back to an installed VLC. Native package launchers fail closed without the
  bundle, while normal compile/test tasks neither require nor download it.

## Pagination And Scrollbar

- Explore retains Search page tracking in `SearchState` and near-end detection from lazy layout state. Query/type edits invalidate active request generations and clear the executed result set; search, load-more, and row-projection results publish only when their query, type, and generation still match. Each generation/page pair is attempted automatically at most once, so a failed page remains explicitly retryable through the Load more button without entering an automatic retry loop. A server `nextPage` remains explicitly actionable even when API filtering yields zero supported assets.
- Favorites resolves the authenticated owner when its root destination opens.
  One logical load may consume multiple partner-only or unsupported-only server
  pages. Success and typed failure both carry consumed pages; failure also
  carries the first unconsumed page so an explicit retry never replays an
  already consumed sparse page. Repeated, backward, malformed, or consumed
  next tokens terminate pagination after retaining current owned assets.
- People detail uses page-fetch UseCases and load-more state.
- Guard duplicate pagination with `isLoadingMore`; stop at `hasMore == false`.
- Main-screen server-status polling runs only while Main is composed and the app lifecycle reports foreground. Disposing Main, backgrounding Android/iOS, or unfocusing the desktop window stops the repository job; returning restarts the idempotent monitor.
- `ScrollbarOverlay` supports lazy list, grid, and staggered grid states. Screens must pass explicit top and bottom padding that accounts for translucent bars.
- Timeline scrollbar labels, year markers, handle position, and drag targets use `TimelinePageIndex` and photo-page fractions. Keep those mappings aligned; do not mix page fractions with raw LazyColumn item-count fractions.
- Timeline-specific cache, sync, Mosaic, scrollbar, overlay, or rendering changes must update `docs/timeline.md`.

## Key Tech Stack

- Kotlin 2.4.10, Compose Multiplatform 1.11.1.
- Material3 for theming.
- Room 2.8.4, SQLite Bundled 2.7.0.
- KSP 2.3.10.
- Ktor 3.5.1.
- Coil 3.5.0 with OkHttp networking on Android and Ktor3 networking on iOS/JVM.
- Koin 4.2.2.
- Android: minSdk 24, targetSdk 37, JVM target 11.
- Package: `com.udnahc.immichgallery`.
