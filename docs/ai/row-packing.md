# RowPacking Architecture

Load this when changing justified row packing, row-height zoom behavior, `RowItem` projection, or screens that render non-Mosaic photo rows. This is the canonical architecture document for standard RowPacking.

For Mosaic-specific assignment and scheduling, use `docs/ai/mosaic-rendering.md`, `docs/ai/mosaic-assignment.md`, and `docs/ai/mosaic-runtime.md`.

## Ownership

- Standard row packing is implemented by `packIntoRows(...)` in `domain/model/RowPacking.kt`.
- Row packing is deterministic domain projection logic. ViewModels and coordinators may call it; composables must only render the resulting `RowItem`s.
- `JustifiedPhotoRow` renders `RowItem` values. It does not choose row membership, row height, or asset ordering.
- Search always uses standard row packing. Timeline, Album Detail, and Person Detail use row packing when Mosaic is disabled. Mosaic-enabled display must not fall back to `RowItem` packing.
- RowPacking has no Room cache table, no scheduler, and no persisted artifact shape. It is recomputed from ordered assets plus measured layout inputs when those inputs change.

## Data Flow

RowPacking is a screen projection path:

```text
Repository/Room/API -> ordered Asset list -> ViewModel/coordinator grouping
  -> packIntoRows(...) -> RowItem display items -> JustifiedPhotoRow
```

- Repositories provide ordered assets and should not pack rows.
- ViewModels/coordinators decide the section or bucket boundary and call `packIntoRows(...)`.
- `packIntoRows(...)` returns display-ready `RowItem`s, each containing `PhotoItem`s.
- Compose renders the returned rows and should not regroup assets, recompute row heights, or inspect future assets.
- Timeline row projection uses only already-materialized in-memory assets. It must not read Room while building display items; cached but unmaterialized buckets render placeholders until the materialization queue publishes assets.
- Timeline caches derived display rows per bucket, asset revision, and render materialization revision so unchanged background refreshes do not repack rows.

## Inputs

`packIntoRows(...)` receives:

- ordered `assets`
- `bucketIndex` and `sectionLabel` for display identity
- measured `availableWidth`
- requested `targetRowHeight`
- inter-photo `spacing`
- `maxRowHeight`
- `promoteWideImages`
- `minCompleteRowPhotos`

If `availableWidth <= 0` or the asset list is empty, it returns an empty list.

## Row Height Bounds

- `DEFAULT_TARGET_ROW_HEIGHT` is `150f`.
- `rowHeightBoundsForViewport(viewportHeightDp)` clamps user-controlled row height between:
  - `viewportHeight * MIN_TARGET_ROW_HEIGHT_FRACTION`
  - `viewportHeight * MAX_TARGET_ROW_HEIGHT_FRACTION`
- Current fractions are `0.10f` and `0.35f`.
- If viewport height is not measured yet, bounds are `0f..Float.MAX_VALUE`, so saved row height is not clamped prematurely.
- `targetRowHeight` is a density preference. Complete justified rows may compute a different actual row height so the row fills the measured width exactly.

## Core Algorithm

The packer is a single source-order pass:

```text
rows = []
currentRow = []
currentSumAR = 0

for asset in assets:
  if currentRow is empty and asset can be promoted as wide:
    rows += single-photo complete row at full-width height
    continue

  currentRow += asset
  currentSumAR += asset.aspectRatio

  neededWidth = targetRowHeight * currentSumAR
                + spacing * (currentRow.size - 1)

  if neededWidth >= availableWidth
     and currentRow.size >= minCompleteRowPhotos:
    actualHeight = (availableWidth - spacing * (currentRow.size - 1))
                   / currentSumAR
    rows += complete row(currentRow, actualHeight)
    currentRow = []
    currentSumAR = 0

if currentRow is not empty:
  rows += incomplete row(currentRow, targetRowHeight)
```

This preserves source order and represents every input asset exactly once.

## Complete Rows

- A row becomes complete when the assets collected at `targetRowHeight` would meet or exceed the available width.
- The computed row height is:

```text
actualHeight = (availableWidth - spacing * (photoCount - 1)) / sum(aspectRatio)
```

- Complete rows render full width.
- `JustifiedPhotoRow` renders complete rows with weighted children, using each asset aspect ratio as the weight. That distributes row width proportionally while all cells share the same row height.

## Incomplete Final Row

- The final row is incomplete when the loop ends before the accumulated row reaches the width threshold.
- Incomplete rows use `rowHeight = targetRowHeight`.
- `RowItem.isComplete` is `false` for this row.
- `JustifiedPhotoRow` renders incomplete rows with each cell's natural `aspectRatio` instead of weighted full-width distribution. This avoids stretching a short final row across the whole width.
- `minCompleteRowPhotos` does not prevent a final incomplete row; it only controls when a row may close as complete during the scan.

## Wide Image Promotion

Wide image promotion is a special case for an asset at the start of an empty row.

Promotion is allowed only when:

- `promoteWideImages = true`
- `currentRow` is empty
- `asset.aspectRatio >= WIDE_FULL_WIDTH_ASPECT_RATIO`
- full-width height fits the configured caps

Current constants:

- `WIDE_FULL_WIDTH_ASPECT_RATIO = 1.5f`
- `WIDE_FULL_WIDTH_TARGET_MULTIPLIER = 1.5f`

Promotion computes:

```text
fullWidthHeight = availableWidth / asset.aspectRatio
maxPromotedHeight = min(maxRowHeight, targetRowHeight * WIDE_FULL_WIDTH_TARGET_MULTIPLIER)
```

If `fullWidthHeight <= maxPromotedHeight`, the asset becomes a single-photo complete row and the scan continues with the next asset.

Wide promotion is skipped when:

- another photo is already pending in the row.
- the full-width height would exceed `maxPromotedHeight`.
- `promoteWideImages = false`.

## Minimum Complete Row Photos

- `minCompleteRowPhotos` prevents a row from closing as complete until it contains enough photos.
- The default is `1`, which allows a single very wide asset to close a justified row when promotion is disabled.
- A larger value, such as `2`, forces the scanner to collect at least two photos before it can emit a complete row.
- If the asset list ends before the minimum is reached, the remaining photos still emit as one incomplete final row.

## Display Identity

Each asset becomes a `PhotoItem` with:

```text
gridKey = "p_${asset.id}"
bucketIndex = bucketIndex
sectionLabel = sectionLabel
asset = asset
```

Each row gets:

```text
gridKey = "row_${firstPhoto.gridKey}"
bucketIndex = bucketIndex
sectionLabel = sectionLabel
photos = current row photos
rowHeight = computed or target height
isComplete = true/false
```

The first photo id anchors the row key. If row membership changes because width, row height, ordering, or aspect ratios change, the row key may change naturally with the new first photo.

## Screen Usage

- Search calls `packIntoRows(...)` for search result rows and does not use Mosaic.
- Timeline calls `packIntoRows(...)` when Mosaic is disabled, and through the Mosaic engine for completed `RowItemKind.MOSAIC_FALLBACK` rows only.
- Album Detail and Person Detail call `packIntoRows(...)` when Mosaic is disabled.
- When Mosaic is enabled, Timeline, Album Detail, and Person Detail should render real `MosaicBandItem`s, `PlaceholderItem`s, or completed cropped `RowItem(kind = MOSAIC_FALLBACK)` rows. They must not render standard row-packing rows as a Mosaic fallback.
- `RowItemKind.MOSAIC_FALLBACK` reuses the row-packing data shape, but it is Mosaic-owned completed output. `JustifiedPhotoRow` renders it as full-width weighted cropped cells even if `RowItem.isComplete == false`; standard incomplete rows still keep natural-width layout.
- Timeline keeps bucket-level asset revisions so background sync does not repack standard rows for unchanged already-loaded buckets. A server bucket refresh that matches ordered visible Room content and has no unresolved edit enrichment should not rewrite asset/ref rows, bump the bucket revision, or republish loaded state.

## Invalidations

Repack rows when any of these change:

- ordered asset list
- asset aspect ratios
- measured available width
- target row height
- row-height bounds or max row height
- grouping/bucket/section identity
- row-packing options such as `promoteWideImages` or `minCompleteRowPhotos`

Do not repack for metadata-only changes that do not affect ordered row content or row identity.
Do not repack Timeline rows for unchanged warm/manual bucket refreshes that only confirm server content matches the cached ordered assets.

## Testing Expectations

Row-packing tests should cover:

- unmeasured viewport bounds preserving saved row height.
- wide image promotion when it fits target and viewport caps.
- wide image promotion rejection when it exceeds caps.
- wide images after pending photos packing normally.
- `promoteWideImages = false`.
- single-photo complete rows when `minCompleteRowPhotos = 1`.
- delayed completion when `minCompleteRowPhotos > 1`.
- final incomplete rows preserving `targetRowHeight`.
