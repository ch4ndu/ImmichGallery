# Mosaic Assignment And Projection

Load this when changing how ordered assets are grouped into Mosaic bands, how templates are scored, how assignments are represented, or how complete assignments are projected into display items.

For runtime scheduling, pause/resume, and progressive chunk delivery, also read `docs/ai/mosaic-runtime.md`. For cross-screen architecture rules, read `docs/ai/mosaic-rendering.md`.

## Pipeline

Mosaic rendering has three distinct phases: section grouping, band assignment, and display projection.

The complete ready path is:

```text
ordered section assets
  -> source-order assignment scan
  -> best 4/5/6-photo template assignments
  -> completed projection
  -> REAL Mosaic bands plus completed fallback Mosaic bands for unassigned gaps
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
- At each `sourceIndex`, the engine tries to build one real Mosaic band from the next 4, 5, or 6 assets.
- If the best candidate is valid, the engine emits one `MosaicBandAssignment`, advances `sourceIndex` by that band's `sourceCount`, and increments `bandIndex`.
- If no candidate is valid for that window, the engine advances `sourceIndex` by one and tries again.
- Skipped source ranges are not dropped. Completed projection later represents those ranges as fallback `MosaicBandItem(kind = FALLBACK)` bands; strict partial projection represents them as placeholders.

The scan is:

```text
sourceIndex = 0
while sourceIndex <= assets.size - 4:
  candidate = best 4/5/6-photo template candidate at sourceIndex
  if candidate exists:
    assignments += candidate.assignment
    sourceIndex += candidate.sourceCount
    bandIndex += 1
  else:
    sourceIndex += 1
```

## Template Candidates

- A real Mosaic band owns one contiguous source range of 4, 5, or 6 assets.
- Candidate layouts come from fixed template families in `MosaicPacking.kt`.
- Templates are small layout trees:
  - `leaf(slot)` is one photo slot.
  - `h(...)` places child groups horizontally.
  - `v(...)` stacks child groups vertically.
- The engine tries every enabled template for the candidate source count and every permutation of those source assets. This lets the same source window choose which photo becomes the feature tile without changing source ownership.
- The built-in families include 4-photo feature/stack templates, 5-photo feature/cluster templates, and 6-photo mixed, paired-column, and two-row templates.

## Candidate Scoring

- Assignment scoring runs in normalized coordinates, not real screen pixels.
- The normalized width is `columnCount * MOSAIC_CANONICAL_CELL_SIZE`; the canonical cell size is currently `100f`.
- A candidate is rejected before scoring when:
  - computed band height is non-positive or exceeds the assignment band-height limit.
  - any tile is below the minimum tile size.
  - the template geometry is degenerate.
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
  - otherwise render the gap before the next valid assignment as fallback `MosaicBandItem(kind = FALLBACK)` bands.
- Projection must cover every source asset exactly once and preserve section source order.
- A replayed assignment is rejected if its real display band is too tall, has too-small tiles, does not fill the measured width, or has overlapping tiles.

## Fallback Mosaic Bands

- Fallback bands are still `MosaicBandItem`s, never `RowItem`s.
- `buildFallbackMosaicBands(...)` lays unresolved source ranges into full-width bands with evenly sized tiles.
- Completed ready projection may contain fallback bands when a source range could not be represented by a valid 4/5/6-photo template.
- Pending, interrupted, failed, cache-miss, and partial-gap states must render placeholders instead of fallback bands.
