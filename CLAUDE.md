# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Verify

- Quick build check: `./gradlew :composeApp:compileKotlinJvm`
- Android: Build/run via Android Studio or `./gradlew :composeApp:assembleDebug`
- iOS: Open `iosApp/iosApp.xcodeproj` in Xcode, build the `iosApp` scheme
- Desktop: `./gradlew :composeApp:run`
- Tests: `./gradlew :composeApp:allTests` (all platforms) or `./gradlew :composeApp:jvmTest` (JVM only)

## Architecture

- **KMP Compose Multiplatform** targeting Android, iOS (arm64 + simulator), JVM Desktop
- **Immich API** client — read-only gallery for self-hosted Immich photo servers. Auth via `x-api-key` header.
- **Ktor 3** HTTP client with per-platform engines (OkHttp/Darwin/Java)
- **Coil 3** for authenticated image loading via `KtorNetworkFetcherFactory`
- **Room 2.8.4** local DB with `BundledSQLiteDriver` — write-through cache for timeline data. Network fetches write to Room, UI reads from Room on demand.
- **Paging 3** for memory-efficient pagination in photo detail pager
- **Koin** DI: `sharedModule` in `di/AppModule.kt` + `platformModule()` (expect/actual per platform)
- **JetBrains Navigation Compose KMP** with `@Serializable` type-safe routes
- **Repository pattern**: Repositories wrap `ImmichApiService` (`data/repository/`)
- **UseCase pattern**: Reads → UseCase classes (`domain/usecase/`). UseCases expose `suspend operator fun invoke()` returning `Result<T>`.
- **Per-screen ViewModels** injected with UseCases (never repositories directly).
- **multiplatform-settings** for credential storage (server URL + API key)
- Platform-specific code uses `expect`/`actual`. Minimize platform code — prefer commonMain.

## Room Write-Through Cache

- Timeline data cached in Room: `TimelineBucketEntity`, `TimelineAssetEntity`
- Repository pattern: network → Room upsert → read from Room on demand
- ViewModel holds `cachedAssets: MutableMap` for in-memory Room query result cache
- Platform database builders via `expect`/`actual` using `BundledSQLiteDriver`
- `fallbackToDestructiveMigration(true)` for cache databases
- Android needs `Context` — use `initPlatformContext()` from `MainActivity`

## Justified Row Layout

- `packIntoRows()` shared utility in `domain/model/RowPacking.kt` — groups assets into justified rows
- `JustifiedPhotoRow` composable uses `Modifier.weight(aspectRatio)` for proportional widths in a `Row`
- `RowItem` in `TimelineDisplayItem` sealed interface wraps photos with pre-computed `rowHeight`
- ViewModels hold `targetRowHeight` + `availableWidth` for layout computation
- `BoxWithConstraints` + `LaunchedEffect(maxWidth)` measures width → passes to ViewModel
- `pinchToZoomRowHeight` shared modifier (`ui/util/PinchToZoom.kt`) for all grid screens
- Incomplete last rows use `aspectRatio` modifier instead of `weight` (no stretching)
- Compute rows atomically with data in single `_state.update {}`, not in separate call after

## Shared Element Transitions

- `SharedTransitionLayout` wraps screen content + overlay in each screen
- `AnimatedVisibility` for grid (`visible = !showOverlay`) and overlay (`visible = showOverlay`)
- `sharedBounds` modifier on `ThumbnailCell` (grid) and `AssetPage` (detail) with matching key `"thumb_${asset.id}"`
- Grid side: `ContentScale.Crop` resize mode; Detail side: `ContentScale.Fit` resize mode
- `lastSelectedAssetId` pattern keeps overlay content alive during exit animation
- Thumbnail in detail hidden after 500ms delay to prevent peek-through during zoom-out
- `DetailTopBar` wrapped in `AnimatedVisibility(visible = selectedAssetId == null)` to hide during overlay

## Slideshow

- Triggered from `DetailTopBarOverlay` dropdown menu in `StaticPhotoOverlay`
- `KenBurnsImage` composable: 10 animation presets (zoom+pan combinations), random per photo
- Auto-advance via `while(isSlideshow) { delay(5000); animateScrollToPage(next) }` in single `LaunchedEffect`
- Tap pauses, manual swipe pauses, back dismisses
- Videos show thumbnail with Ken Burns (no video playback during slideshow)
- `ScreenWakeLock` expect/actual: Android `FLAG_KEEP_SCREEN_ON`, iOS `idleTimerDisabled`, Desktop no-op

## Pagination

- Search: `loadMore()` with page tracking in `SearchState`, `snapshotFlow` on `layoutInfo` for near-end detection
- People: `GetPersonAssetsPageUseCase` for single-page fetch, `derivedStateOf` for load-more trigger
- Guard: `isLoadingMore` flag prevents duplicate requests; `hasMore` stops at end

## Scrollbar

- `ScrollbarOverlay` with overloads for `LazyListState`, `LazyGridState`, `LazyStaggeredGridState`
- Year markers with overlap filtering (`YEAR_MARKER_MIN_SPACING_DP = 28dp`)
- Handle + markers clamped with edge padding to avoid overlap with top/bottom bars
- `clipToBounds()` on container; pixel values pre-computed in `remember`
- `labelProvider` callback for date labels during drag; haptic feedback on label change

## Source Layout

```
composeApp/src/commonMain/kotlin/com/udnahc/immichgallery/
├── data/
│   ├── local/     # Room DB: AppDatabase, entities, DAOs
│   ├── model/     # API response models
│   ├── remote/    # ImmichApiService, HTTP client
│   └── repository/# Repositories (API + Room write-through)
├── domain/
│   ├── model/     # Domain models, mappers (API→entity, entity→domain)
│   ├── usecase/   # One class per read, organized by entity
│   └── action/    # One class per write, organized by entity
├── ui/
│   ├── component/ # Reusable composables (ThumbnailCell, ScrollbarOverlay)
│   ├── navigation/# Routes, AppNavigation, MainScreen
│   ├── screen/    # Per-feature screens + ViewModels
│   └── theme/     # Material3 theme, Dimens
└── di/            # Koin modules (AppModule + PlatformModule expect/actual)
```

Platform dirs: `androidMain/`, `iosMain/`, `jvmMain/` — Room DB builders, HTTP client engines, entry points.

## Key Conventions

### Date/Time
- Use `kotlinx-datetime` for all date math

### UI
- Material3 with custom theme (`ui/theme/`)
- Bottom NavigationBar and top app bars **overlay** content (translucent) — screens must pad accordingly
- All dp values via theme dimension constants (exception: 0–2dp inline spacing, preview containers)
- All strings via `stringResource()`, icons from `composeResources/drawable/`
- `ScrollbarOverlay` requires explicit `topPadding` and `bottomPadding` — no defaults. For MainScreen tabs use `statusBarPadding + Dimens.topBarHeight` / `Dimens.bottomBarHeight + navBarPadding`. For Scaffold screens use `innerPadding.calculateTopPadding()` / `innerPadding.calculateBottomPadding()`.
- `rememberAsyncImagePainter` + `Image` for thumbnails (bypasses Coil's `ConstraintsSizeResolver` that ignores explicit size)
- `Precision.EXACT` + `Size(256, 256)` on thumbnail `ImageRequest` to control decode size
- Coil decoder parallelism limited to 4 via `decoderCoroutineContext(Dispatchers.Default.limitedParallelism(4))`
- Thumbnail URL uses `size=thumbnail` (not `size=preview`) for smaller server response
- Thumbhash aspect ratio decoding as fallback when exifInfo unavailable (`ThumbhashUtils.kt`)

### Composable Architecture
- **Single responsibility**: One composable = one UI component
- **Screen/Content split**: Screens and bottom sheets extract inner content into a separate composable that receives state, renders, and supports `@Preview`
- **Deferred reads**: Pass `StateFlow` to children, collect at lowest possible scope

### Domain Layer
- **UseCases**: One class per read. Constructor-injected with repository. Derived/filtered flows (groupBy, filter, combine) belong here.
- **Actions**: One class per write. Constructor-injected with repository.
- New features: create UseCases/Actions first, then wire into a screen-specific ViewModel.

### Performance
- `@Immutable` on all data classes passed to composables
- No data transformation in composables — filtering, sorting, mapping, grouping all belong in UseCases or ViewModels
- `Dispatchers.IO` for DB/network writes, `.flowOn(Dispatchers.Default)` for flow transforms, `Dispatchers.Default` for CPU-heavy work (filtering, row packing). Do NOT wrap trivial `MutableStateFlow.update` in a coroutine — it's thread-safe and instant.
- `LazyColumn` with `key = { it.id }` for scrollable lists
- Lambdas in `items()`/`for` loops: stabilize with `remember(key)` to prevent reallocation on every recomposition
- Lambdas passed from Screen to Content composables that capture snapshot state: wrap in `remember { }` to keep stable reference
- Composition-time `layoutInfo` reads (e.g., `totalItemsCount`): wrap in `remember { derivedStateOf { ... } }` to avoid recomposition on every scroll frame
- `setAvailableWidth`/`setTargetRowHeight`: compute rows atomically inside a single `_state.update {}` — never in a separate `repackRows()` call
- Timeline prefetch debounced to 300ms via `snapshotFlow + debounce`, range ±2 buckets
- `availableWidth` guard: `if (width == current) return` to prevent unnecessary row repacks
- Room query results cached in `cachedAssets: MutableMap` — persist across layout rebuilds
- Placeholder height estimated from bucket count + target row height to prevent scroll jumps

## Key Tech Stack

- Kotlin 2.3.0, Compose Multiplatform 1.10.0
- Material 3 for theming
- Room 2.8.4, SQLite Bundled 2.6.2 — now actively used for timeline cache
- KSP 2.3.6
- Paging 3.4.0-alpha04 (common + compose)
- Compose Hot Reload plugin enabled
- Gradle configuration cache and build cache enabled
- Android: minSdk 24, targetSdk 36, JVM target 11
- Package: `com.udnahc.immichgallery`

## Key Files

| Purpose                     | Path                                               |
|-----------------------------|----------------------------------------------------|
| App entry + Koin/Coil init  | `App.kt`                                           |
| Navigation routes           | `ui/navigation/Route.kt`                           |
| Root nav + tab shell        | `ui/navigation/AppNavigation.kt`, `MainScreen.kt`  |
| Immich API service          | `data/remote/ImmichApiService.kt`                  |
| API response models         | `data/model/*.kt`                                  |
| Domain models + mappers     | `domain/model/*.kt`                                |
| DI module                   | `di/AppModule.kt`                                  |
| Image loader factory        | `di/ImageLoaderFactory.kt`                         |
| Server config (credentials) | `data/repository/ServerConfigRepository.kt`        |
| Room database               | `data/local/AppDatabase.kt`                        |
| Room DAOs                   | `data/local/dao/*.kt`                              |
| Room entities               | `data/local/entity/*.kt`                           |
| Platform DB builders        | `{platform}Main/.../DatabaseBuilder.{platform}.kt` |
| Platform DI modules         | `di/PlatformModule.kt` (expect) + actuals          |
| Photo pager holder          | `data/repository/PhotoPagerHolder.kt`              |
| Scrollbar component         | `ui/component/ScrollbarOverlay.kt`                 |
| Screen ViewModels           | `ui/screen/{feature}/*ViewModel.kt`                |
| Version catalog             | `gradle/libs.versions.toml`                        |
| Row packing utility         | `domain/model/RowPacking.kt`                       |
| Thumbhash utils             | `domain/model/ThumbhashUtils.kt`                   |
| Justified photo row         | `ui/component/JustifiedPhotoRow.kt`                |
| Ken Burns image             | `ui/component/KenBurnsImage.kt`                    |
| Pinch-to-zoom modifier      | `ui/util/PinchToZoom.kt`                           |
| Screen wake lock            | `ui/util/ScreenWakeLock.kt`                        |
| Load bucket assets action   | `domain/action/timeline/LoadBucketAssetsAction.kt` |
