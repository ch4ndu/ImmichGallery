---
name: audit
description: Audit the ImmichGallery codebase against architecture rules defined in CLAUDE.md. Reports violations with file paths, line numbers, and fixes.
allowed-tools: Read, Grep, Glob, Agent
---

# Architecture Audit

Audit the ImmichGallery codebase against the rules in **CLAUDE.md**. Report violations only — do not fix them unless asked.

## Audit Checks

Launch up to 3 Explore agents in parallel to check:

### Agent 1: ViewModel & Domain Layer
- **Per-screen ViewModels**: No screen uses another screen's ViewModel
- **UseCase/Action pattern**: ViewModels inject UseCases + Actions, never repositories
- **No UseCases in Screens**: Screens must not inject UseCases via `koinInject()` — all UseCases flow through ViewModels
- **Domain layer**: Every read goes through a UseCase, every write through an Action
- **DI wiring**: `AppModule.kt` registers all UseCases, Actions, ViewModels correctly
- **Dispatchers**: `Dispatchers.IO` for DB/network writes, `.flowOn(Dispatchers.Default)` for flow transforms. `Dispatchers.Default` for CPU-heavy work (filtering, row packing). Do NOT wrap trivial `MutableStateFlow.update` in `Dispatchers.IO` — it adds unnecessary context-switch latency
- **StateFlow**: `WhileSubscribed(5000)` for all UI state flows
- **Room cache**: Timeline data uses Room write-through cache. Check `TimelineRepository` writes to Room, reads from Room.
- **Room query caching**: ViewModel should cache Room query results (e.g., `cachedAssets`) to avoid re-querying on layout rebuilds
- **Actions for writes**: Write operations use Action classes (e.g., `LoadBucketAssetsAction`), not direct repository calls
- **Row packing in ViewModel**: `packIntoRows()` from `domain/model/RowPacking.kt` must be called in ViewModel, never in composables
- **Atomic state updates**: Row computation should happen inline with data in single `_state.update {}`, not in separate `repackRows()` after state update

Files: `viewmodel/`, `domain/`, `di/AppModule.kt`, `App.kt`

### Agent 2: UI & Composable Architecture
- **Single responsibility**: Each composable renders one UI component
- **Screen/Content split**: Screens and bottom sheets extract inner content into a separate composable that receives state and supports @Preview
- **No business logic in Screen callbacks**: Screen composable callbacks (`onPhotoClick`, etc.) should be thin wrappers calling ViewModel methods. Complex index calculations, iteration, or conditional logic belongs in the ViewModel.
- **Previews**: Every major composable (screens, bottom sheets, cards, rows) must have a `@Preview` function
- **Deferred reads**: `collectAsState()` at lowest possible scope, not App.kt level
- **Strings**: All user-visible text via `stringResource()`, no hardcoded strings
- **Dimensions**: All dp via theme dimension constants (exception: 0-2dp inline, previews)
- **Overlay padding**: Screens account for translucent bottom nav bar and top app bar
- **LazyColumn**: Used with `key = { it.id }` for scrollable lists
- **Lambdas in loops**: Stabilized with `remember(key)` where key includes **all captured mutable references** (not just the item ID). Verify that every variable captured in the lambda closure is included as a remember key. This includes lambdas inside `for` loops in composables (e.g., `JustifiedPhotoRow`), not just `items()` blocks.
- **Unstable lambdas passed to content composables**: Lambdas like `onPhotoClick = { assetId -> selectedAssetId = assetId }` that capture snapshot state must be wrapped in `remember { }` when passed to child composables to prevent unnecessary recompositions.
- **No data transformation in composables**: No `.filter{}`, `.map{}`, `.sortedBy{}`, `.groupBy{}`, `.chunked()`, `.associate{}` in composable bodies — even inside `remember()`. These belong in ViewModels. Exception: `derivedStateOf` blocks that read Compose snapshot state (e.g., `gridState.layoutInfo`) which is unavailable outside composables.
- **No object allocations in composition**: Avoid `listOf()`, `mapOf()`, data class constructors in composable bodies without `remember {}`. Static lists of items (nav items, tab configs) should be wrapped in `remember` or declared outside the composable.
- **Composition-time layout reads**: `layoutInfo.totalItemsCount` or similar reads at composition scope (outside `derivedStateOf`) trigger recomposition on every scroll frame. Wrap in `remember { derivedStateOf { ... } }`.
- **Composable reuse**: No duplicate definitions of `PhotoRow`, loading/error states, top bars, or IconButton patterns across screens. All shared UI must live in `ui/component/`. Check for: duplicate `private fun PhotoRow`, duplicate loading `CircularProgressIndicator` + error `TextButton` blocks, duplicate top bar `Box` with back button + title.
- **Justified rows**: All grid screens use `JustifiedPhotoRow` via `LazyColumn`, not staggered/fixed grids
- **Width measurement**: `BoxWithConstraints` + `LaunchedEffect(maxWidth)` -> `setAvailableWidth()` with guard `if (width == current) return`
- **Pinch-to-zoom**: Grid screens apply `.pinchToZoomRowHeight()` modifier from `ui/util/PinchToZoom.kt`
- **Load-more detection**: Uses `snapshotFlow` or `derivedStateOf` on `layoutInfo`, NOT composition-time reads
- **Shared element transitions**: `lastSelectedAssetId` pattern for exit animation content. `DetailTopBar` hidden during overlay via `AnimatedVisibility`
- **Thumbnail loading**: Uses `rememberAsyncImagePainter` (not `AsyncImage`) with `Precision.EXACT` and `Size(256, 256)` to control decode size
- **Slideshow**: `StaticPhotoOverlay` supports slideshow via menu item. `KenBurnsImage` for animation. Wake lock for screen-on.
- **LaunchedEffect race conditions**: Verify every `LaunchedEffect` in composables for race conditions. Check for:
  - **Missing keys**: `LaunchedEffect(Unit)` or `LaunchedEffect(true)` when the block reads mutable state — effect won't re-run on state change, captures stale values. Use the actual dependencies as keys.
  - **Stale reads after suspend**: Reading state after `delay()`, `animateScrollToPage()`, `awaitPointerEventScope()` or other suspend calls without re-checking conditions. Post-suspend state may have changed (e.g., pager moved, user navigated away, flag flipped). Re-read state after each suspend.
  - **Callbacks captured as keys**: Passing an unstable lambda (`onComplete`, `onTick`) as a key causes the effect to restart every recomposition. Use `rememberUpdatedState(callback)` and reference the `.value` inside the effect instead.
  - **Competing effects on same state**: Two `LaunchedEffect` blocks that mutate the same `MutableState`/`StateFlow` without coordination — last-writer-wins, order non-deterministic. Merge into one effect or gate with a single source of truth.
  - **Missing cancellation of collectors**: `launch { flow.collect { ... } }` inside `LaunchedEffect` without structured cancellation — when key changes, the outer effect restarts but the child job leaks if it escapes the effect scope (e.g., stored in a class field or `rememberCoroutineScope`). Use the effect's own `CoroutineScope`.
  - **Auto-advance loops without pause gating**: `while(condition) { delay(); advance() }` where `condition` is captured by value at launch, not re-read each iteration. Re-read state inside the loop so pause/stop takes effect.
  - **State reads outside snapshotFlow**: `LaunchedEffect(key1) { if (someState) ... }` reads `someState` once at launch. If `someState` should drive the effect, either include it in keys or wrap in `snapshotFlow { someState }.collect { ... }`.
  - **Debounce swallowing final emission**: `snapshotFlow {}.debounce(300).collect {}` where the last emission before cancellation may be dropped. For prefetch/save on leave, ensure the effect survives long enough or use a final flush.
  - **Navigation/lifecycle races**: Triggering nav or async ViewModel calls from `LaunchedEffect(Unit)` without guarding against re-entry when screen is popped and re-pushed — produces duplicate requests or double-navigation.
  - **Paging/loadMore races**: Near-end detection launching loadMore without `isLoadingMore` guard, or guard flipped before request completes — see SearchState pattern. Verify `isLoadingMore` is set before launch and cleared in `finally`.

Files: `ui/screen/`, `ui/component/`, `ui/navigation/`, `App.kt`

### Agent 3: Data Layer
- **@Immutable**: All data classes passed to composables must be annotated — check `domain/model/`, `ui/screen/*/` (state classes), **and `ui/navigation/`** (UI data classes like nav items)
- **Date/Time**: Use kotlinx-datetime and utility functions for all date math — no raw millis literals
- **UTC storage**: Repository converts UTC -> local millis on read
- **Room entities**: Check `TimelineBucketEntity` and `TimelineAssetEntity` exist with proper indices
- **Entity mappers**: `toEntity()` mappers exist in `Mappers.kt` alongside `toDomain()`
- **Thumbhash fallback**: Both `toDomain()` and `toEntity()` mappers use thumbhash aspect ratio as fallback when exifInfo unavailable
- **Edited URLs**: All asset URLs include `edited=true` query parameter for non-destructive editing support

Files: `data/model/`, `data/repository/`, `data/local/`, `domain/model/`, `ui/navigation/`

## Report Format

For each violation:
```
**[RULE]** — file:line
Description of violation.
Fix: what should change.
```

End with a summary: total violations by category, and a list of compliant areas.
