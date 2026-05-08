# Mosaic Rendering Architecture

Load this when changing Mosaic assignment, projection, fallback, scheduling, cache artifacts, placeholders, or settings behavior for Timeline, Album Detail, or Person Detail.

For detailed asset-to-band assignment and projection behavior, load `docs/ai/mosaic-assignment.md`.
For runtime queueing, pause/resume, progressive chunks, and scheduler behavior, load `docs/ai/mosaic-runtime.md`.
For the broader Album Detail and Person Detail screen experience around cached-first loading, owner sync, pagination, shared layout coordination, and overlay return targeting, load `docs/ai/album-person-detail.md`.

## Ownership

- `MosaicRenderEngine` is the domain facade for Mosaic behavior. Assignment, progressive chunks, completed fallback-row projection, strict partial projection, section geometry, and aggregate geometry must go through this facade.
- Low-level helpers in `MosaicPacking.kt` are implementation details for the engine and focused tests. Screen packages and repositories must not call them to bypass the engine.
- Repositories persist, read, and clear artifacts only. They may parse and validate persisted rows against the current ordered assets, but they must not compute assignments, project display records, choose fallback behavior, derive artifact payloads, or calculate geometry.
- ViewModels/coordinators own screen state, scheduling, generation guards, cache-read requests, and display publication.
- Composables render `PhotoGridDisplayItem` values only. They must not group assets, assign Mosaic bands, or choose fallback policy.

## Shared Model

- `MosaicSectionRequest` is the input contract for assignment. It carries owner/section identity, assets, assignment/display layout specs, spacing, max row height, enabled families, and progress batch size.
- `MosaicKeyScope` provides stable owner/section/config/content identity. Owner scopes are `TIMELINE_BUCKET`, `ALBUM`, and `PERSON`.
- `MosaicSectionResult.Ready` contains assignments, projected display items, and section geometry.
- `MosaicSectionResult.Failed` is a compute failure. Screens decide retry/display state; failure must not imply repository writes.
- `MosaicSectionState` is the shared state vocabulary for pending, partial, ready, and failed section state.
- `ProgressChunk` owns a contiguous source-asset range and a set of assignments for that range. Chunks are memory-only runtime state.
- `SectionGeometry` is per section. It stores the section `placeholderHeight` plus `MosaicSectionGeometryRange(sourceStartIndex, sourceCount, height)` records for each final real Mosaic band or completed fallback row. `AggregateGeometry` is owner-level placeholder height across ordered sections for a grouping/config/content generation.

## Layout And Display Items

- Mosaic uses `ViewConfig.mosaicColumnCount` while enabled. Pinch or desktop zoom must not silently change Mosaic columns.
- `MosaicLayoutSpec.cellHeight` is derived from measured width divided by configured column count.
- `PhotoGridDisplayItem` is the shared render surface:
  - `HeaderItem` for group labels.
  - `PlaceholderItem` for unloaded, pending, invalid, failed, or unresolved ranges.
  - `MosaicBandItem` for Mosaic bands.
  - `RowItem` for standard row packing when Mosaic is disabled, for Search, or as completed `RowItemKind.MOSAIC_FALLBACK` output when a Mosaic-ready source range cannot be represented by a real template.
- Mosaic-enabled rendering must never fall back to standard justified `RowItemKind.STANDARD` packing.
- Stable keys must include owner/bucket/group/section identity so placeholder, partial, ready, and overlay transitions do not collide.

## Detailed Documents

- `docs/ai/mosaic-assignment.md` explains section grouping, source-order scanning, template candidates, scoring, assignment payloads, source-vs-visual order, display projection, and fallback Mosaic rows.
- `docs/ai/mosaic-runtime.md` explains Timeline and detail runtime queues, scroll pause/resume, scheduler priorities, checkpoint resume, progressive chunks, publication deferral, and retry behavior.

## Engine Projection Policies

- `projectReadySection(...)` projects a completed assignment set. It may fill unassigned or invalid source ranges with deterministic cropped full-width `RowItem(kind = MOSAIC_FALLBACK)` rows, but callers may use that output only after `MosaicRenderEngine.computeSection()` returns `Ready` for the active owner/section/config/fingerprint/generation or after a validated completed display-cache read.
- `projectSection(...)` is the compatibility alias for completed projection. New screen code should call `projectReadySection(...)` when it is intentionally publishing completed output.
- `projectPartialSection(...)` is the general partial projection. It fills unresolved ranges with placeholders.
- `projectPartialSectionWithPlaceholders(...)` is the strict runtime projection for Timeline and detail screens:
  - Valid completed chunks render real `MosaicBandItem(kind = REAL)` bands.
  - Gaps and invalid chunks render `PlaceholderItem`s.
  - It must not emit `RowItem`.
  - It must not emit `MosaicBandItem(kind = FALLBACK)`.
- `projectPartialSectionWithGeometry(...)` is the exact-height strict runtime projection:
  - It accepts cached section geometry ranges and completed progress chunks.
  - Real chunk bands may publish only when their projected height matches the corresponding geometry range within tolerance.
  - Unresolved or invalid geometry ranges collapse into exact-height placeholders.
  - The projected partial section must preserve the final ready section height within `0.5f`, otherwise callers keep the exact full-section placeholder.
- A chunk is valid for strict display only when the projected output is all real Mosaic bands and covers the chunk source range without source overlap.
- Empty-assignment progress chunks are not completed visual chunks; strict projection renders their range as placeholders.

## Timeline Runtime And Cache Behavior

- Timeline is bucket-oriented. Bucket metadata, materialized asset rows, Mosaic assignments, display cache, section geometry, and aggregate bucket geometry are separate states.
- When `cacheMosaicResults = true`, Timeline reads persisted current-config artifacts for materialized visible/nearby buckets. Cache reads are guarded by generation, grouping, column count, families, content fingerprint, and geometry identity.
- A current-config Timeline cache miss for a materialized requested bucket must compute runtime Mosaic for that same requested config. It must not read old-config artifacts and must not leave the bucket as placeholders indefinitely.
- When `cacheMosaicResults = false`, Timeline must not read persisted Mosaic artifacts for rendering. Materialized buckets compute runtime Mosaic for the active config after active scroll settles.
- Cache-off Timeline sync is runtime-only from the UI perspective: cold sync, any enabled warm changed-bucket sync, and manual sync must not prepare persisted Mosaic artifacts or fall through to runtime compute. Runtime compute is allowed only from explicit render-demand requests such as visible buckets, targeted buckets, and scroll-settled resume. Under cache-only warm policy, warm changed-bucket sync does not run because non-manual Timeline server refresh is disabled.
- Manual Timeline sync with cache results off clears Timeline and Detail Mosaic artifacts before refreshing buckets, then clears in-memory Timeline Mosaic state so stale assignments or geometry cannot remain active.
- Runtime Timeline Mosaic uses an ordered single-bucket worker and the dedicated Mosaic dispatcher so assignment work does not compete with image loading.
- Runtime Timeline Mosaic stores complete `Ready` assignments plus validated mixed display records when section projection can produce full source coverage. Assignments remain canonical; invalid or missing resolved records fall back to assignment projection during rendering.
- Timeline persisted cache reads distinguish assignment+geometry readiness from display-cache completeness. Matching assignments plus matching section and aggregate geometry are renderable even when mixed display records are missing; the ViewModel publishes `Ready(assignments, displayRecords = emptyList())` and rendering projects from assignments. Missing assignments, missing section geometry, or missing aggregate geometry remain true cache misses.
- Timeline Mosaic requests are mergeable, not cancel-and-replace. Visible buckets and explicit scrollbar targets are ordered ahead of neighbors and previously deferred work. Offscreen work may wait, but it must not cancel or overtake visible work.
- While the Timeline is actively scrolling:
  - Runtime Mosaic compute is paused.
  - Persisted reads are limited to visible or explicitly targeted buckets.
  - Mosaic display replacement is deferred until scroll settles.
- Complete ready rows supersede partial chunks. Stale chunks are ignored by owner/config/fingerprint/generation.
- Partial chunks remain transient progress state; they are not converted into resolved display-band records.
- Timeline pending sections, partial gaps, cache misses, interrupted chunks, and failed sections render placeholders while Mosaic is enabled. They must not render fallback-thumbnail bands or standard row-packing rows.
- Timeline may display `RowItem(kind = MOSAIC_FALLBACK)` only as completed ready output for the active config, or from a completed mixed display-cache row that independently validates source coverage against the current ordered assets.
- Persisted Timeline display cache rows must cover `0 until assets.size` with no gaps, overlaps, duplicate ids, unknown ids, non-positive real-tile dimensions, invalid fallback row heights, or mismatched source slices. Invalid or missing display rows are a display-cache miss only when matching assignments, section geometry, and aggregate geometry are present; otherwise the bucket is a true cache miss and render-demand work may enqueue runtime compute for the requested config.

## Album And Person Detail Runtime Behavior

- Album Detail and Person Detail share `PhotoGridDetailLayoutCoordinator`.
- Detail screens render cached Room assets immediately and sync the opened owner in the background. Mosaic layout rebuilds only when ordered asset content, config, width, or grouping changes require it.
- CPU-heavy detail assignments run through `MosaicWorkScheduler`. Visible groups outrank prefetch work.
- During active scroll, detail runtime Mosaic may compute only currently visible groups. Offscreen and prefetch groups are recorded as resumable incomplete work and resume after scroll settles.
- Completed visible groups publish immediately; offscreen replacements are deferred and flushed after scroll settles.
- Runtime group state is memory-only:
  - `InFlight` means a group has started or been queued and can restart from an empty checkpoint if cancelled early.
  - `Partial` means a group has checkpoint/chunk progress.
  - `RetryableFailure` means compute failed and may retry when the group is visible.
- Scheduler cancellation is resumable, not final failure. Cancellation before the first checkpoint must still leave resumable state.
- Pending, cancelled, failed, invalid, and partial-gap detail runtime states render placeholders. They must not render fallback-thumbnail bands.
- Detail runtime thumbnails may appear only from real ready assignments, valid completed progressive chunks, or fallback rows produced by a completed ready projection for the current group.
- Repeated runtime failure must not become final fallback display. It remains placeholder plus retry when visible.
- `groupDisplayCache` stores final ready group items only. It must not store placeholders, partial output, or retry state; completed fallback Mosaic rows are allowed because they are part of ready projection.
- `groupDisplayBandCache` stores validated resolved real display-band records for the same detail group/config/fingerprint keys. It is seeded from completed runtime ready output and from strict persistent display-cache reads, but only when every display item is a real `MosaicBandItem` and the records cover the current ordered group assets. It is cleared with `groupDisplayCache`.
- Full in-memory `displayCache` may be set only when every current group has final ready output, no current runtime state remains, deferred updates are empty, and the display contains no placeholders while Mosaic is enabled.

## Persistent Cache Artifacts

- Timeline artifacts:
  - Assignments are width-independent.
  - Mixed display records, section geometry, and aggregate bucket geometry are width/config/version dependent.
  - Rows are scoped by bucket/section, grouping, column count, families, ordered asset fingerprint, and geometry identity as appropriate.
  - Section geometry rows store both the aggregate `placeholderHeight` and serialized geometry ranges. Geometry ranges record each final real band's or fallback row's source range and layout height.
  - `PrepareTimelineMosaicCacheAction` owns Timeline precompute orchestration. It reads bucket snapshots through the Timeline bucket snapshot reader, computes sections through `MosaicRenderEngine`, asks `TimelineMosaicArtifactBuilder` for assignment/display/geometry artifacts, and hands complete bucket artifacts to `TimelineMosaicCacheRepository` for atomic replacement.
  - Timeline display-cache validation is order-sensitive. Stored records must be contiguous in source order. Real-band tile ids may be visually reordered but must cover the expected source slice with valid unique `visualOrder` values; fallback-row `assetIds` must match the expected source slice in order.
- Detail artifacts:
  - Owner-scoped assignments, mixed display records, section geometry, and aggregate geometry are keyed by owner type/id, grouping, section index/key, column count, families, ordered asset fingerprint, geometry keys, and artifact versions.
  - Detail section geometry uses the same geometry-range payload as Timeline so Album/Person partial chunks can reserve exact unresolved heights.
  - Album Detail may read/write owner cache when cache is enabled and the album owner snapshot is complete.
  - Person Detail may read/write owner cache only after the full person asset set is known (`hasMore == false`).
- Full-owner cache readiness requires matching assignments, section geometry, and aggregate geometry. Per-section display recovery may use valid matching assignment, section geometry, and mixed display rows even when the owner aggregate geometry row is missing after an interrupted cache build.
- Cache-disabled runtime rendering must not read or reuse disk Mosaic artifacts even if matching rows exist.
- Applying Mosaic settings with cache enabled is blocking: prepare the applicable Timeline/detail cache first, then persist/apply the config. On preparation failure, keep the previous `ViewConfig`.
- Cache schema changes may use destructive migration while this remains a pre-alpha cache-only project.

## Geometry And Placeholders

- Section geometry is the placeholder height for one Mosaic section at a specific config/geometry identity. It stores Compose layout-unit heights, not physical pixels, so density is not part of the cache key.
- Section geometry ranges record final source ranges and heights for real Mosaic bands and completed fallback rows. They are required for exact partial projection because heights alone cannot tell which unresolved source range a placeholder replaces.
- Aggregate geometry is the owner or bucket placeholder height across ordered sections for the current grouping/config/content generation.
- Exact persisted geometry should be used when it matches the active config. Stale geometry must miss.
- Count-only placeholders are the last fallback when exact geometry and materialized asset data are unavailable.
- Placeholder-to-ready replacement should preserve scroll position, shared element return targets, and hidden-asset behavior as much as possible.
- Timeline and detail screens render one aggregate `PlaceholderItem` per pending Mosaic section when exact section geometry is available. Partial rendering may replace that placeholder only if the mixed real-band/placeholder output preserves the exact section height.
- Timeline loaded buckets may render the matching aggregate bucket-geometry placeholder while no section has a renderable `Ready` or `Partial` Mosaic state. This preserves the exact persisted bucket height through Room materialization; count-only placeholders are allowed only when no matching exact geometry exists. When cache results are enabled but no geometry has been computed yet, screens may show a provisional count-based placeholder and accept one first-time resize. Once exact geometry exists, rough placeholders must not overwrite it.
- Timeline `TimelineViewModel` reads bucket-aggregate + per-section geometry in a visible-first phase at warm launch and after cache invalidation. A `timelineGeometryReady` gate stays closed only for visible/target buckets plus radius neighbors; the gate opens even when that priority read fails so visible Mosaic work cannot stall indefinitely. Offscreen cached geometry hydrates afterward in small background chunks.

## Invalidation And Staleness

- Ordered asset fingerprints drive Mosaic invalidation. Counts and timestamps alone are not sufficient.
- Config identity includes Mosaic enablement, column count, normalized template families, grouping where applicable, and geometry keys where display/geometry artifacts are width-dependent.
- Asset/order/content changes clear matching assignments, mixed display rows, section geometry, aggregate geometry, in-memory runtime state, and progressive chunks for the affected owner/bucket/section.
- Width or settings changes must not recompute unchanged Timeline buckets opportunistically. They should read matching requested-config artifacts or show placeholders according to the screen policy; they must not fetch or display older Mosaic configs as substitutes.
- Stale async work must be ignored when owner, generation, config, fingerprint, bucket generation, or geometry identity no longer matches. Timeline queued Mosaic publishes are guarded by both a global runtime generation and per-bucket generations.
- `Ready` supersedes partial/in-flight/retry state. `Failed` or retry state must not replace an existing valid ready state for the active config.

## Audit Rules

- Flag any Mosaic assignment, progress, fallback, display projection, section geometry, or aggregate geometry that bypasses `MosaicRenderEngine`.
- Flag repository-owned Mosaic math or artifact derivation. Repositories should only persist/read/clear artifacts and validate persisted reads.
- Flag Mosaic-enabled `RowItemKind.STANDARD` fallback outside Search.
- Flag fallback Mosaic bands anywhere in Mosaic-enabled Timeline/detail output; completed fallback must be `RowItemKind.MOSAIC_FALLBACK`.
- Flag Timeline Mosaic fallback rows for pending, partial gaps, cache misses, interrupted runtime work, failed sections, or unvalidated display-cache rows. Completed ready output and validated completed display-cache rows may contain fallback rows.
- Flag Album/Person detail runtime fallback-thumbnail bands for pending, failed, cancelled, invalid, or partial-gap states. Completed ready output may contain fallback rows.
- Flag display-cache writes that include placeholders, partial runtime output, stale config rows, invalid source coverage, or incomplete owner snapshots.
- Flag cache-disabled Timeline/detail runtime paths that read persisted Mosaic artifacts for rendering.

## Verification Expectations

- Domain tests should cover progressive chunks, strict partial projection, fallback-row output, source coverage, stable geometry, and cancellation/staleness behavior.
- Cache tests should cover all artifact types, owner/config/fingerprint/geometry/version misses, scoped clear operations, and DB reset behavior.
- Detail coordinator tests should cover cancellation before and after checkpoint, visible retry, invalid/empty chunks as placeholders, no fallback bands in runtime incomplete display, and display-cache poisoning prevention.
- Timeline tests should cover cache-disabled runtime compute, scroll pause/resume, visible/target prioritization, strict placeholder projection without fallback bands, stale chunk rejection, and no persisted reads when cache is disabled.
