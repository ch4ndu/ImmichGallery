# Mosaic Rendering Architecture

Load this when changing Mosaic assignment, projection, fallback, scheduling, cache artifacts, placeholders, or settings behavior for Timeline, Album Detail, or Person Detail.

For detailed asset-to-band assignment and projection behavior, load `docs/ai/mosaic-assignment.md`.
For runtime queueing, pause/resume, progressive chunks, and scheduler behavior, load `docs/ai/mosaic-runtime.md`.
For the broader Album Detail and Person Detail screen experience around cached-first loading, owner sync, pagination, shared layout coordination, and overlay return targeting, load `docs/ai/album-person-detail.md`.

## Ownership

- `MosaicRenderEngine` is the domain facade for Mosaic behavior. Assignment, progressive chunks, fallback-band projection, strict partial projection, section geometry, and aggregate geometry must go through this facade.
- Low-level helpers in `MosaicPacking.kt` are implementation details for the engine and focused tests. Screen packages and repositories must not call them to bypass the engine.
- Repositories persist, read, and clear artifacts only. They must not compute assignments, project display bands, choose fallback behavior, or calculate geometry.
- ViewModels/coordinators own screen state, scheduling, generation guards, cache-read requests, and display publication.
- Composables render `PhotoGridDisplayItem` values only. They must not group assets, assign Mosaic bands, or choose fallback rows.

## Shared Model

- `MosaicSectionRequest` is the input contract for assignment. It carries owner/section identity, assets, assignment/display layout specs, spacing, max row height, enabled families, and progress batch size.
- `MosaicKeyScope` provides stable owner/section/config/content identity. Owner scopes are `TIMELINE_BUCKET`, `ALBUM`, and `PERSON`.
- `MosaicSectionResult.Ready` contains assignments, projected display items, and section geometry.
- `MosaicSectionResult.Failed` is a compute failure. Screens decide retry/display state; failure must not imply repository writes.
- `MosaicSectionState` is the shared state vocabulary for pending, partial, ready, and failed section state.
- `ProgressChunk` owns a contiguous source-asset range and a set of assignments for that range. Chunks are memory-only runtime state.
- `SectionGeometry` is per section. `AggregateGeometry` is owner-level placeholder height across ordered sections for a grouping/config/content generation.

## Layout And Display Items

- Mosaic uses `ViewConfig.mosaicColumnCount` while enabled. Pinch or desktop zoom must not silently change Mosaic columns.
- `MosaicLayoutSpec.cellHeight` is derived from measured width divided by configured column count.
- `PhotoGridDisplayItem` is the shared render surface:
  - `HeaderItem` for group labels.
  - `PlaceholderItem` for unloaded, pending, invalid, failed, or unresolved ranges.
  - `MosaicBandItem` for Mosaic bands.
  - `RowItem` only when Mosaic is disabled, or for Search which remains row-packing only.
- Mosaic-enabled rendering must never fall back to justified `RowItem` packing.
- Stable keys must include owner/bucket/group/section identity so placeholder, partial, ready, and overlay transitions do not collide.

## Detailed Documents

- `docs/ai/mosaic-assignment.md` explains section grouping, source-order scanning, template candidates, scoring, assignment payloads, source-vs-visual order, display projection, and fallback Mosaic bands.
- `docs/ai/mosaic-runtime.md` explains Timeline and detail runtime queues, scroll pause/resume, scheduler priorities, checkpoint resume, progressive chunks, publication deferral, and retry behavior.

## Engine Projection Policies

- `projectReadySection(...)` projects a completed assignment set. It may fill unassigned or invalid source ranges with deterministic fallback `MosaicBandItem(kind = FALLBACK)` bands, but callers may use that output only after `MosaicRenderEngine.computeSection()` returns `Ready` for the active owner/section/config/fingerprint/generation or after a validated completed display-cache read.
- `projectSection(...)` is the compatibility alias for completed projection. New screen code should call `projectReadySection(...)` when it is intentionally publishing completed output.
- `projectPartialSection(...)` is the legacy/general partial projection that fills unresolved ranges with fallback Mosaic bands.
- `projectPartialSectionWithPlaceholders(...)` is the strict runtime projection for Timeline and detail screens:
  - Valid completed chunks render real `MosaicBandItem(kind = REAL)` bands.
  - Gaps and invalid chunks render `PlaceholderItem`s.
  - It must not emit `RowItem`.
  - It must not emit `MosaicBandItem(kind = FALLBACK)`.
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
- Timeline Mosaic requests are mergeable, not cancel-and-replace. Visible buckets and explicit scrollbar targets are ordered ahead of neighbors and previously deferred work. Offscreen work may wait, but it must not cancel or overtake visible work.
- While the Timeline is actively scrolling:
  - Runtime Mosaic compute is paused.
  - Persisted reads are limited to visible or explicitly targeted buckets.
  - Mosaic display replacement is deferred until scroll settles.
- Complete ready rows supersede partial chunks. Stale chunks are ignored by owner/config/fingerprint/generation.
- Timeline pending sections, partial gaps, cache misses, interrupted chunks, and failed sections render placeholders while Mosaic is enabled. They must not render fallback-thumbnail bands or `RowItem`s.
- Timeline may display `MosaicBandItem(kind = FALLBACK)` only as completed ready output for the active config, or from a completed display-cache row that independently validates source coverage against the current ordered assets.
- Persisted Timeline display cache rows must cover `0 until assets.size` with no gaps, overlaps, duplicate ids, unknown ids, non-positive tile dimensions, or mismatched source slices. Invalid display rows make the bucket a cache miss and runtime compute is enqueued for the requested config.

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
- Detail runtime thumbnails may appear only from real ready assignments, valid completed progressive chunks, or fallback Mosaic bands produced by a completed ready projection for the current group.
- Repeated runtime failure must not become final fallback display. It remains placeholder plus retry when visible.
- `groupDisplayCache` stores final ready group items only. It must not store placeholders, partial output, or retry state; completed fallback Mosaic bands are allowed because they are part of ready projection.
- Full in-memory `displayCache` may be set only when every current group has final ready output, no current runtime state remains, deferred updates are empty, and the display contains no placeholders while Mosaic is enabled.

## Persistent Cache Artifacts

- Timeline artifacts:
  - Assignments are width-independent.
  - Display bands, section geometry, and aggregate bucket geometry are width/config/version dependent.
  - Rows are scoped by bucket/section, grouping, column count, families, ordered asset fingerprint, and geometry identity as appropriate.
- Detail artifacts:
  - Owner-scoped assignments, display bands, section geometry, and aggregate geometry are keyed by owner type/id, grouping, section index/key, column count, families, ordered asset fingerprint, geometry keys, and artifact versions.
  - Album Detail may read/write owner cache when cache is enabled and the album owner snapshot is complete.
  - Person Detail may read/write owner cache only after the full person asset set is known (`hasMore == false`).
- Cache readiness requires matching assignments, section geometry, and aggregate geometry. Display-band rows are additionally required only when `cacheMosaicResults = true`.
- Cache-disabled runtime rendering must not read or reuse disk Mosaic artifacts even if matching rows exist.
- Applying Mosaic settings with cache enabled is blocking: prepare the applicable Timeline/detail cache first, then persist/apply the config. On preparation failure, keep the previous `ViewConfig`.
- Cache schema changes may use destructive migration while this remains a pre-alpha cache-only project.

## Geometry And Placeholders

- Section geometry is the placeholder height for one Mosaic section at a specific config/geometry identity.
- Aggregate geometry is the owner or bucket placeholder height across ordered sections for the current grouping/config/content generation.
- Exact persisted geometry should be used when it matches the active config. Stale geometry must miss.
- Count-only placeholders are the last fallback when exact geometry and materialized asset data are unavailable.
- Placeholder-to-ready replacement should preserve scroll position, shared element return targets, and hidden-asset behavior as much as possible.

## Invalidation And Staleness

- Ordered asset fingerprints drive Mosaic invalidation. Counts and timestamps alone are not sufficient.
- Config identity includes Mosaic enablement, column count, normalized template families, grouping where applicable, and geometry keys where display/geometry artifacts are width-dependent.
- Asset/order/content changes clear matching assignments, display bands, section geometry, aggregate geometry, in-memory runtime state, and progressive chunks for the affected owner/bucket/section.
- Width or settings changes must not recompute unchanged Timeline buckets opportunistically. They should read matching requested-config artifacts or show placeholders according to the screen policy; they must not fetch or display older Mosaic configs as substitutes.
- Stale async work must be ignored when owner, generation, config, fingerprint, or geometry identity no longer matches.
- `Ready` supersedes partial/in-flight/retry state. `Failed` or retry state must not replace an existing valid ready state for the active config.

## Audit Rules

- Flag any Mosaic assignment, progress, fallback, display projection, section geometry, or aggregate geometry that bypasses `MosaicRenderEngine`.
- Flag repository-owned Mosaic math. Repositories should only persist/read/clear artifacts.
- Flag Mosaic-enabled `RowItem` fallback outside Search.
- Flag Timeline Mosaic fallback bands for pending, partial gaps, cache misses, interrupted runtime work, failed sections, or unvalidated display-cache rows. Completed ready output and validated completed display-cache rows may contain fallback Mosaic bands.
- Flag Album/Person detail runtime fallback-thumbnail bands for pending, failed, cancelled, invalid, or partial-gap states. Completed ready output may contain fallback Mosaic bands.
- Flag display-cache writes that include placeholders, partial runtime output, stale config rows, invalid source coverage, or incomplete owner snapshots.
- Flag cache-disabled Timeline/detail runtime paths that read persisted Mosaic artifacts for rendering.

## Verification Expectations

- Domain tests should cover progressive chunks, strict partial projection, fallback-band output, source coverage, stable geometry, and cancellation/staleness behavior.
- Cache tests should cover all artifact types, owner/config/fingerprint/geometry/version misses, scoped clear operations, and DB reset behavior.
- Detail coordinator tests should cover cancellation before and after checkpoint, visible retry, invalid/empty chunks as placeholders, no fallback bands in runtime incomplete display, and display-cache poisoning prevention.
- Timeline tests should cover cache-disabled runtime compute, scroll pause/resume, visible/target prioritization, strict placeholder projection without fallback bands, stale chunk rejection, and no persisted reads when cache is disabled.
