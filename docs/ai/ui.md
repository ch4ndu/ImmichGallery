# UI And Compose Rules

Load this for Compose UI, screens, bottom sheets, previews, theme work, or recomposition-sensitive changes.

For Mosaic-specific rendering, placeholder, fallback, and scheduling rules, load `docs/ai/mosaic-rendering.md`.
For standard justified row packing and `RowItem` behavior, load `docs/ai/row-packing.md`.

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
- Photo-grid lazy lists should use `photoGridDisplayItemContentType(...)` so rows and Mosaic bands are reused only across compatible child shapes.
- Grid thumbnails should keep shared-element and per-cell `AnimatedVisibility` wiring limited to the active photo transition path; normal scrolling cells should use the plain thumbnail path.
- Do not wrap large repeated rows inside one lazy item with `Column { items.forEach { ... } }`; emit keyed lazy `items(...)` where virtualization matters.
- Stabilize lambdas created inside `items()` or `for` loops with `remember(key)` when they capture changing state or callbacks.
- Lambdas passed from Screen to Content composables that capture snapshot state should be wrapped in `remember { }` where practical.

## Grid And Overlay State

- Grid ViewModels own `targetRowHeight`, `availableWidth`, row packing, and load-more state.
- `setAvailableWidth` should return early when width has not changed.
- Compute rows atomically inside the state update that changes the related data.
- Use `lastSelectedAssetId` or equivalent retained state so overlay content survives dismiss transitions.
- Detail still-photo viewing should keep one stable zoomable full-resolution image path visible outside active transition/drag. Drag-dismiss transforms belong on the fitted media container, using tap-anchored layout size/offset so the touched media point stays under the finger; do not rely on draw-only `graphicsLayer` for the exit source rect. Pinch zoom takes precedence over drag before vertical dismiss commits. Freeze committed dismiss transforms until overlay unmount, use a short full-image alpha handoff for still-photo open/dismiss transitions, keep the shared thumbnail synchronously visible during real transitions, and only reset transforms to identity for snap-back/cancel. Video drag keeps the live video player composed and playing while the fitted media container follows the finger.
- Slideshow auto-advance loops must re-check slideshow and page state after suspending.
- Grid ViewModels own row-height bounds, effective target height, persisted view config, and photo-grid display items.
- Mosaic assignment, progress chunks, fallback projection, and geometry belong behind `MosaicRenderEngine`; composables should only render display items. Timeline is the exception for assignment timing: assignments are precomputed after sync and persisted, while the ViewModel loads those cached assignments.
- When Mosaic is enabled, keep it available at all zoom densities for every supported column count; do not add target-row-height or group-mode UI gates.
- Mosaic-enabled Timeline, Album, and Person grids use the global `ViewConfig.mosaicColumnCount` for `MosaicLayoutSpec`; Mosaic bands, placeholders, and fallback rows use `availableWidth / columnCount` as cell height. Row-height zoom must not silently change cached Mosaic columns.
- Mosaic-enabled Album and Person detail screens should render shared `PlaceholderItem` rows while foreground Mosaic assignment work is queued or while a cache miss falls back to runtime construction; do not leave the grid blank when assets and width are known.
- The Mosaic settings dialog owns Mosaic enablement, template families, column count, `Cache results`, and `Disable zoom`. When cache results is enabled, applying Mosaic changes is a blocking dialog operation: prepare the full applicable Timeline library or detail owner cache first, then persist/apply the config. If preparation fails, keep the old config.
- Detail screens report visible Mosaic group indexes from `LazyListState.layoutInfo` so `MosaicWorkScheduler` can prioritize visible groups before prefetch.
- Album/Person detail Mosaic rendering should use `MosaicRenderEngine` through `PhotoGridDetailLayoutCoordinator`. The coordinator owns layout state, generation guards, visible-group priority, progressive chunk publication, in-memory assignment checkpoints, retryable runtime failure state, scroll-state-aware runtime publishing, and deferred offscreen flushes; renderers own cached band mapping and cache-entry creation/writes.
- Mosaic-enabled Album and Person detail runtime rendering must not show fallback-thumbnail bands for pending, cancelled, failed, or partial-gap work. Completed real chunks render as Mosaic bands; unresolved or invalid ranges render `PlaceholderItem`s and remain resumable. Timeline fallback remains `MosaicBandItem` based until its runtime path is explicitly tightened.
- Use `PhotoGridLayoutRunner` for expensive zoom-driven row or detail Mosaic projection work. Cached Mosaic column changes come from the settings dialog apply path, not pinch/desktop zoom.
- Persist row-height changes through UseCases/Actions, but debounce gesture-driven writes in the ViewModel.
- Timeline scrollbar custom targeting must keep handle position, labels, year markers, and drag target mapping on the same fraction coordinate system.
- Timeline scroll and visibility effects should read the precomputed display index from `TimelineState` instead of scanning `displayItems` in `snapshotFlow`. Keep those effects keyed on stable owners such as `LazyListState`, and use `rememberUpdatedState` for the latest display lookup.
- Timeline scroll state is an input to Mosaic scheduling. While the list is actively scrolling, visible asset rows and thumbnails take priority: runtime Mosaic compute is paused, persisted Mosaic reads are limited to visible/target buckets, and Mosaic display replacement is deferred until scroll settles.
- Album and Person detail visible-group effects should follow the same stable display-index pattern. Visibility changes may reprioritize or preempt pending/offscreen Mosaic work, but must not restart layout projection or emit a fresh placeholder list just because the user scrolled. Runtime work may continue through `MosaicWorkScheduler` during scroll; completed visible or near-visible groups publish immediately and offscreen replacement is deferred until scrolling settles. When an incomplete group becomes eligible again, resume from its in-memory assignment checkpoint rather than discarding the group or rereading disk cache.

## Previews

- Keep previews focused on content composables with sample data.
- Preview code should not trigger network, database, Koin, or platform-only behavior.
