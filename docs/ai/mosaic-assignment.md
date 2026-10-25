# Mosaic Assignment And Projection

Load this when changing how ordered assets are grouped into Mosaic bands, how templates are scored, how assignments are represented, or how complete assignments are projected into display items.

For runtime scheduling, pause/resume, and progressive chunk delivery, also read `docs/ai/mosaic-runtime.md`. For cross-screen architecture rules, read `docs/ai/mosaic-rendering.md`.

## Pipeline

Mosaic rendering has three distinct phases: section grouping, band assignment, and display projection.

The complete ready path is:

```text
ordered section assets
  -> source-order assignment scan
  -> best 4-through-8-photo template assignments
  -> completed projection
  -> REAL Mosaic bands plus completed fallback Mosaic rows for unassigned gaps
  -> display cache, section geometry, aggregate geometry
```

The strict partial runtime path is:

```text
ordered section assets
  -> progress chunks
  -> REAL bands for valid completed chunks
  -> placeholders for everything else
```

## Section Grouping

- Mosaic never assigns against the entire app-wide asset list as one unbounded stream.
- Timeline first scopes assets to a bucket, then to the requested Timeline grouping:
  - month grouping uses the bucket as one Mosaic section.
  - day grouping creates one Mosaic section per day inside the bucket.
- Album Detail and Person Detail first call the shared detail grouping logic, then compute one Mosaic section per detail group.
- Each section is submitted to `MosaicRenderEngine.computeSection(...)` as a `MosaicSectionRequest`.

## Source-Order Assignment Scan

- `buildMosaicAssignmentsWithProgress(...)` scans the section assets in source order.
- At each `sourceIndex`, the engine tries to build one real Mosaic band from the next 4, 5, 6, 7, or 8 assets.
- If the best candidate is valid, the engine emits one `MosaicBandAssignment`, advances `sourceIndex` by that band's `sourceCount`, and increments `bandIndex`.
- If no candidate is valid for that window, the engine advances `sourceIndex` by one and tries again.
- Skipped source ranges are not dropped. Completed projection later represents those ranges as cropped `RowItem(kind = MOSAIC_FALLBACK)` rows; strict partial projection represents them as placeholders.

The scan is:

```text
sourceIndex = 0
while sourceIndex <= assets.size - 4:
  candidate = best 4-through-8-photo template candidate at sourceIndex
  if candidate exists:
    assignments += candidate.assignment
    sourceIndex += candidate.sourceCount
    bandIndex += 1
  else:
    sourceIndex += 1
```

## Template Candidates

- A real Mosaic band owns one contiguous source range of 4, 5, 6, 7, or 8 assets.
- Candidate layouts come from fixed template families in `MosaicPacking.kt`.
- Templates are small layout trees:
  - `leaf(slot)` is one photo slot.
  - `h(...)` places child groups horizontally.
  - `v(...)` stacks child groups vertically.
- Every node reduces to an exact affine width equation, `width = slope * height + intercept`, including nested spacing. Projection solves that equation analytically in both directions; it does not approximate nested vertical aspect ratios or cap a binary search by the requested width.
- The engine tries every enabled template for the candidate source count using templates pre-indexed by count and family. For 4, 5, and 6 asset windows it reuses one precomputed exhaustive permutation table. For 7 and 8 asset windows it uses bounded deterministic candidates: source order, reverse source order, aspect ascending, aspect descending, and top widest/tallest assets placed in the feature slot. This lets larger variants remain available without factorial assignment cost.
- The built-in families include 4-photo feature/stack templates, 5-photo feature/cluster templates, 6-photo mixed, paired-column, and two-row templates, plus 7/8-photo feature-cluster and grid templates.

## Candidate Scoring

- Assignment scoring runs in normalized coordinates, not real screen pixels, so persisted assignment rows remain width-independent.
- The normalized width is `columnCount * MOSAIC_CANONICAL_CELL_SIZE`; the canonical cell size is currently `100f`. Progressive chunk stability checks (`isStableProgressChunk`) use `displayLayoutSpec` so progressive chunks validate against the measured display geometry before emission.
- A candidate is rejected before scoring when:
  - computed band height is non-positive or exceeds the assignment band-height limit.
  - any tile is below the minimum tile size.
  - any dimension is non-finite or the template geometry is degenerate.
  - tiles underfill or overflow the solved width/height, begin outside the origin bounds, or overlap.
- Valid candidates are scored by:
  - closeness to the target band height.
  - tile area balance.
  - how closely the layout fills the full normalized width.
- The lowest score wins across all enabled templates and permutations for that source index.

## Assignment Payload

- `MosaicBandAssignment` stores identity and source ownership, not final pixels:
  - `bandIndex`
  - `sourceStartIndex`
  - `sourceCount`
  - `templateId`
  - tile `assetId`
  - tile `visualOrder`
- The assignment deliberately does not persist `x`, `y`, `width`, or `height`.
- Final geometry is recalculated during projection from the current measured width, spacing, column count, asset aspect ratios, and template tree.

## Source Order Versus Visual Order

- Source order decides where the band sits in the section and which assets it owns.
- Visual order decides where each asset appears inside the chosen template.
- A band can own source assets `20..24` while asset `23` appears as the feature tile. The band still represents source range `20..24` exactly once.

## Display Projection

- `buildPhotoGridItemsWithMosaic(...)` replays assignments into real display geometry using the current `MosaicLayoutSpec`.
- Projection walks source assets from `0` to `assets.size`:
  - if a valid assignment starts at the current source index, render it as `MosaicBandItem(kind = REAL)`.
  - otherwise render the gap before the next valid assignment as cropped `RowItem(kind = MOSAIC_FALLBACK)` rows using the same source range.
- Projection must cover every source asset exactly once and preserve section source order.
- A replayed assignment is rejected if its real display band is too tall, has too-small tiles, does not fill the measured width, or has overlapping tiles.

## Fallback Mosaic Rows

- Fallback rows are `RowItem(kind = MOSAIC_FALLBACK)`, not standard row-packing rows and not `MosaicBandItem(kind = FALLBACK)`.
- `buildFallbackMosaicRows(...)` packs the unresolved source range through the shared row-packing helper with stable Mosaic fallback keys and source indices.
- Fallback thumbnails are cropped (`ContentScale.Crop`) and rendered full-width in UI so the completed fallback row reads as compact Mosaic fallback output rather than standard justified row packing. This full-width rendering applies even when the underlying row packer marks the final fallback row incomplete.
- Completed ready projection may contain fallback rows when a source range could not be represented by a valid 4-through-8-photo template.
- Pending, interrupted, failed, cache-miss, and partial-gap states must render placeholders instead of fallback rows.
