---
name: implement-feature
description: Use when implementing a new feature, screen, or significant change in the ImmichGallery KMP app. Guides through proper architecture patterns, platform considerations, and verification.
argument-hint: [feature description]
allowed-tools: Read, Write, Edit, Grep, Glob, Bash, Agent
---

# Implement Feature in ImmichGallery

Refer to **CLAUDE.md** for architecture rules and conventions. This file is the implementation checklist.

## Before Writing Code

1. **Read CLAUDE.md** for conventions
2. **Explore existing code** — reuse existing UseCases, Actions, composables, and utilities
3. **Identify scope**: commonMain only, or platform-specific code needed?

## Implementation Checklist

### Data Layer (if new entity or schema change)
- [ ] Entity in `data/model/` with `@Immutable`
- [ ] DAO in `data/dao/` — `Flow<>` for reads, `suspend` for writes
- [ ] Repository interface + impl in `data/repository/`
- [ ] Update `AppDatabase.kt`: entities list, DAO accessor, bump version
- [ ] Register repository in `di/AppModule.kt`
- [ ] If feature needs persistent data, add Room entities + DAO queries + repository write-through
- [ ] Add `toEntity()` mapper in `Mappers.kt` for API->Room conversion
- [ ] Register DAO in platform modules (Android needs `Context` via `initPlatformContext()`)

### Domain Layer
- [ ] UseCase per read in `domain/usecase/{entity}/`
- [ ] Action per write in `domain/action/{entity}/`
- [ ] Derived/filtered flows go in UseCases, not ViewModels
- [ ] Register in `di/AppModule.kt` — `single {}` for most, `factory {}` if UseCase takes runtime params

### ViewModel
- [ ] New screen -> new ViewModel in `viewmodel/`, injected with UseCases + Actions
- [ ] Never add to another screen's ViewModel
- [ ] All `viewModelScope.launch` calls that perform I/O use `Dispatchers.IO`
- [ ] Register in `di/AppModule.kt` via `viewModel { ... }`

### UI Layer
- [ ] Screen composable in `ui/screens/`
- [ ] Obtain ViewModel via `koinViewModel()` at call site in `App.kt`
- [ ] Wire in `App.kt` if new tab or destination
- [ ] Reuse existing components from `ui/component/` before creating new ones: `PhotoRow`, `LoadingErrorContent`, `DetailTopBar`, `PlaceholderCell`, `ScrollbarOverlay`, `ThumbnailCell`, `StaticPhotoOverlay`, `DetailTopBarOverlay`, `DetailBottomHandle`
- [ ] If feature shows photo grid: use `packIntoRows()` + `JustifiedPhotoRow` + `BoxWithConstraints` + `pinchToZoomRowHeight`
- [ ] If feature navigates to photo detail: wire `SharedTransitionLayout` + `sharedBounds` + `lastSelectedAssetId` pattern
- [ ] If API supports pagination: implement `loadMore()` + `snapshotFlow`/`derivedStateOf` for near-end detection
- [ ] Use `rememberAsyncImagePainter` (not `AsyncImage`) for thumbnail cells with `Precision.EXACT`

### Platform
- [ ] Prefer commonMain — use `expect`/`actual` only when necessary

## Post-Code Audit

Run these checks before considering the task complete:

### Correctness
- [ ] All dp values use theme dimension constants
- [ ] All strings use `stringResource()`, icons use `Res.drawable.*`
- [ ] Bottom/top padding accounts for translucent overlay bars

### Recomposition
- [ ] No unstable lambdas in loops — stabilize with `remember(id) { { callback(item) } }`
- [ ] No data transformation in composables — filtering, sorting, mapping, grouping all belong in UseCases or ViewModels
- [ ] `collectAsState()` at lowest possible scope
- [ ] `LaunchedEffect` keys are narrow — no unrelated state changes triggering effects
- [ ] Use `snapshotFlow` for frequently-changing state (scroll position)
- [ ] Magic numbers extracted to `const val`
- [ ] Allocations hoisted to file-level constants

### Previews
- [ ] Every major composable (screens, bottom sheets, cards, rows) must have a `@Preview` function

## Build Verification

1. `./gradlew :composeApp:compileKotlinJvm`
2. Grep for issues: hardcoded strings, hardcoded dp values

## Post-Implementation: Update Architecture Docs

After completing the feature, **prompt the user** with suggested updates to:
1. **CLAUDE.md** — new patterns, tech stack changes, key files, conventions discovered
2. **This skill file** — new gotchas, checklist items, or lessons learned

Present a concise list and ask which to apply.

## Gotchas & Lessons Learned

### Compose Multiplatform
- `Material Icons` (`Icons.Default.*`) are NOT available — use drawable XML resources from `composeResources/drawable/`
- `HorizontalPager` pre-composes adjacent pages — video players will auto-play on off-screen pages. Fix: use `MediaPlayerHost(autoPlay = false)` + `LaunchedEffect(pagerState.settledPage == page)` to control play/pause
- `pagerState.settledPage` (not `currentPage`) is the correct signal for "page is fully centered" — `currentPage` updates during swipe animation
- `stickyHeader` pins to viewport top — with translucent top bar overlay, headers need `padding(top = statusBarHeight + topBarHeight)` to snap below the bar
- No cross-platform scrollbar API — must build custom via `LazyListState.layoutInfo`
- Draggable scrollbar thumb: track drag position as independent `mutableFloatStateOf` — don't derive from scroll fraction during drag (it's async/stale)

### Coil 3 KMP Image Loading
- `AsyncImage` ignores `ImageRequest.size()` — it overrides with `ConstraintsSizeResolver` from layout constraints
- Use `rememberAsyncImagePainter` + `Image` composable instead — it respects the request size
- Add `Precision.EXACT` to ensure Coil uses the specified size, not layout-derived size
- Decoder parallelism: limit to 4 via `decoderCoroutineContext(Dispatchers.Default.limitedParallelism(4))`
- Thumbnail URL: use `size=thumbnail` (not `size=preview`) for smaller server response (~250px vs ~720px)

### Shared Element Transitions
- `AnimatedVisibility` content with nullable state needs `lastSelectedAssetId` pattern
- When dismiss sets `selectedAssetId = null`, the overlay content returns early — keep last value in separate state
- Grid side uses `sharedBounds` with `ContentScale.Crop`; detail side uses `ContentScale.Fit`
- Thumbnail in detail: hidden after 500ms delay via opaque cover Box (not alpha 0, which breaks transition)

### Room KMP
- Android Room requires `Context` — use `expect fun platformModule(): Module` pattern with `initPlatformContext(context)` called in `MainActivity.onCreate`
- All platforms: `BundledSQLiteDriver()` + `setQueryCoroutineContext(Dispatchers.IO)` + `fallbackToDestructiveMigration(true)`
- `@ConstructedBy(AppDatabaseConstructor::class)` with `@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")` on the expect object
- KSP targets for this project: `kspAndroid`, `kspJvm`, `kspIosSimulatorArm64`, `kspIosArm64`
- KSP version must match Kotlin version — for Kotlin 2.3.0, use KSP 2.3.x (new independent versioning)
- Don't store URLs in Room entities — reconstruct from `assetId + baseUrl` in entity->domain mappers (avoids stale URLs on server config change)
- `@Database` needs `fallbackToDestructiveMigration(true)` for cache databases (data can be re-fetched)
- Android Room needs `Context` — register via `initPlatformContext()` called from `MainActivity.onCreate()`
- Platform DB builders in `{platform}Main/.../DatabaseBuilder.{platform}.kt` using `BundledSQLiteDriver`
- Room suspend functions handle their own threading — safe to call from any dispatcher

### Immich API Pagination
- Person assets: API paginates via `nextPage` field in `SearchResponse.assets` — must loop until `nextPage == null`
- Album detail: API returns all assets at once — no pagination loop needed
- Timeline: buckets loaded lazily; for scrollbar stability use `estimatedItemCount` from bucket metadata (`sum of ceil(count/3) + 1`)

### Data Flow Patterns
- `PhotoPagerHolder` (Koin singleton) bridges screen->detail navigation: callers set up pager data, PhotoDetailScreen reads it
- Room write-through: every API fetch atomically replaces corresponding data (`@Transaction` delete + insert)
- Stale data strategy: `DELETE WHERE bucket/album/person = X` then `INSERT` — handles server-side deletions

### Row Packing
- `packIntoRows()` must be called with current `availableWidth` — guard with `if (width <= 0) return emptyList()`
- Compute rows atomically with asset data in single `_state.update {}` — not in separate `repackRows()` after
- Incomplete last rows: use `isComplete = false` flag, render with `aspectRatio` modifier instead of `weight`
- Placeholder height: estimate from `bucket.count / photosPerRow * targetRowHeight` to prevent scroll jumps

### Thumbhash Aspect Ratio
- Thumbhash encodes image dimensions in first 5 bytes (base64 decoded)
- `thumbhashToAspectRatio()` in `ThumbhashUtils.kt` extracts `lx/ly` DCT component ratio
- Used as fallback in `AssetResponse.toDomain()` when `ratio` and `exifInfo` are both null
- Approximate but sufficient for layout — exact ratio not needed for row packing

### UI Overlay Pattern
- Top bar and bottom bar both use `background.copy(alpha = 0.8f)` and overlay content
- Screens must account for BOTH bars in their padding:
  - Top: `statusBarPadding + Dimens.topBarHeight` (for content padding or sticky header padding)
  - Bottom: `Dimens.bottomBarHeight + navBarPadding`
- The top bar is defined once in `MainScreen.kt`, not per-screen

### Dispatcher Discipline
- ALL ViewModel work (guard checks, state reads, mutable collection access) must be inside `viewModelScope.launch(Dispatchers.*)` — never on the calling thread
- Use `Dispatchers.Default` for critical-path operations that block user interaction (e.g., photo click -> navigation)
- Use `Dispatchers.IO` for background API/DB work that doesn't block the UI
- `searchJob?.cancel()` is the one exception — must stay before `launch` to cancel the previous coroutine

## Feature Request

$ARGUMENTS
