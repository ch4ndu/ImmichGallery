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
