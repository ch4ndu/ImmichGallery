# UI And Compose Rules

Load this for Compose UI, screens, bottom sheets, previews, theme work, or recomposition-sensitive changes.

## Theme And Resources

- Use Material3 with the custom theme in `ui/theme/`.
- Use `Dimens` from `ui/theme/Dimensions.kt` for `dp` values, except 0-2dp inline spacing and preview containers.
- Use `stringResource()` for user-visible strings.
- Use icons from `composeResources/drawable/`.
- Keep UI text, spacing, and behavior consistent with existing screens.

## Layout

- Bottom navigation and top app bars overlay content with translucent alpha. Screens must pad for them.
- For `MainScreen` tabs, account for status bar plus `Dimens.topBarHeight` and navigation bar plus `Dimens.bottomBarHeight`.
- For scaffold-style screens, use `innerPadding.calculateTopPadding()` and `innerPadding.calculateBottomPadding()` when wiring scrollbars.
- `ScrollbarOverlay` requires explicit `topPadding` and `bottomPadding`; do not rely on hidden defaults.
- Photo grids should use justified rows or the established Mosaic layout, not unrelated fixed-grid or staggered-grid layouts.
- Text must fit its container on mobile and desktop.
- Do not add nested cards or unrelated decorative surfaces.

## Photo And Media UI

- Use `rememberAsyncImagePainter` plus `Image` for thumbnails when the request specifies an image size.
- Thumbnail requests should use `Precision.EXACT` and a bounded `Size`, typically `Size(256, 256)`.
- Thumbnail URLs should request `size=thumbnail` unless a larger preview is intentionally needed.
- Detail pages use fit-style media presentation and should preserve shared element transition behavior.
- Videos should use the existing platform media player wrappers and avoid auto-playing off-screen pager pages.

## Composable Architecture

- One composable should represent one UI component.
- Screens and bottom sheets should extract inner content into a separate content composable that receives state and callbacks.
- Before creating reusable UI, check `ui/component/` and `ui/util/`.
- If a pattern appears in two or more screens, extract or reuse a shared composable.

## State And Performance

- Use `@Immutable` on data classes passed to composables.
- Do not transform data in composables. Filtering, sorting, mapping, grouping, and row packing belong in UseCases or ViewModels.
- Pass `StateFlow` to children when useful and collect at the lowest practical scope.
- Keep `LaunchedEffect` keys narrow and intentional.
- Use `snapshotFlow` for frequently-changing state such as scroll position.
- Wrap composition-time `layoutInfo` reads in `remember { derivedStateOf { ... } }`.
- Use stable keys in lazy layouts, usually `key = { it.id }`.
- Do not wrap large repeated rows inside one lazy item with `Column { items.forEach { ... } }`; emit keyed lazy `items(...)` where virtualization matters.
- Stabilize lambdas created inside `items()` or `for` loops with `remember(key)` when they capture changing state or callbacks.
- Lambdas passed from Screen to Content composables that capture snapshot state should be wrapped in `remember { }` where practical.

## Grid And Overlay State

- Grid ViewModels own `targetRowHeight`, `availableWidth`, row packing, and load-more state.
- `setAvailableWidth` should return early when width has not changed.
- Compute rows atomically inside the state update that changes the related data.
- Use `lastSelectedAssetId` or equivalent retained state so overlay content survives dismiss transitions.
- Slideshow auto-advance loops must re-check slideshow and page state after suspending.
- Grid ViewModels own row-height bounds, effective target height, persisted view config, and photo-grid display items.
- Mosaic assignment and row packing belong in domain/ViewModel code; composables should only render display items. Timeline is the exception for assignment timing: assignments are precomputed after sync and persisted, while the ViewModel loads those cached assignments.
- When Mosaic is enabled, keep it available at all zoom densities for every supported column count; do not add target-row-height or group-mode UI gates.
- Mosaic-enabled Timeline, Album, and Person grids use the global `ViewConfig.mosaicColumnCount` for `MosaicLayoutSpec`; Mosaic bands, placeholders, and fallback rows use `availableWidth / columnCount` as cell height. Row-height zoom must not silently change cached Mosaic columns.
- Mosaic-enabled Album and Person detail screens should render shared `PlaceholderItem` rows while foreground Mosaic assignment work is queued or while a cache miss falls back to runtime construction; do not leave the grid blank when assets and width are known.
- The Mosaic settings dialog owns Mosaic enablement, template families, column count, `Cache results`, and `Disable zoom`. When cache results is enabled, applying Mosaic changes is a blocking dialog operation: prepare the full applicable Timeline library or detail owner cache first, then persist/apply the config. If preparation fails, keep the old config.
- Detail screens report visible Mosaic group indexes from `LazyListState.layoutInfo` so `MosaicWorkScheduler` can prioritize visible groups before prefetch.
- Album/Person detail Mosaic rendering should use the extracted cached/runtime renderer classes in the UI detail package. `PhotoGridDetailLayoutCoordinator` owns layout state, generation guards, visible-group priority, scroll-state-aware runtime publishing, and deferred offscreen flushes; renderers own cached row mapping, runtime Mosaic row construction, and cache-entry creation/writes.
- Timeline Mosaic-enabled fallback must render `MosaicBandItem` bands, not justified `RowItem`s. Missing, invalid, failed, or unassigned ranges use deterministic full-width fallback bands with fit-style thumbnails and accepted blank space. Album and Person detail Mosaic fallback may keep their existing justified-row policy unless that screen family is explicitly changed.
- Use `PhotoGridLayoutRunner` for expensive zoom-driven row or detail Mosaic projection work. Cached Mosaic column changes come from the settings dialog apply path, not pinch/desktop zoom.
- Persist row-height changes through UseCases/Actions, but debounce gesture-driven writes in the ViewModel.
- Timeline scrollbar custom targeting must keep handle position, labels, year markers, and drag target mapping on the same fraction coordinate system.
- Timeline scroll and visibility effects should read the precomputed display index from `TimelineState` instead of scanning `displayItems` in `snapshotFlow`. Keep those effects keyed on stable owners such as `LazyListState`, and use `rememberUpdatedState` for the latest display lookup.
- Album and Person detail visible-group effects should follow the same stable display-index pattern. Visibility changes may reprioritize or preempt pending/offscreen Mosaic work, but must not restart layout projection or emit a fresh placeholder list just because the user scrolled. While active scrolling is in progress, publish completed visible or near-visible runtime Mosaic groups immediately and defer offscreen group replacement until scrolling settles.

## Previews

- Keep previews focused on content composables with sample data.
- Preview code should not trigger network, database, Koin, or platform-only behavior.
