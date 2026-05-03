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
- Photo grids should use justified rows, not unrelated fixed-grid or staggered-grid layouts.
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

## Previews

- Keep previews focused on content composables with sample data.
- Preview code should not trigger network, database, Koin, or platform-only behavior.
