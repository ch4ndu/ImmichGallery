# Album And Person Detail Architecture

Load this when changing Album Detail or Person Detail screen behavior, cached-first loading, opened-owner sync, load-more, display projection, visible group reporting, overlay return targeting, or the shared `PhotoGridDetailLayoutCoordinator`.

For Mosaic assignment internals, use `docs/ai/mosaic-assignment.md`. For Mosaic scheduler/chunk policy, use `docs/ai/mosaic-runtime.md`. For standard justified rows, use `docs/ai/row-packing.md`. For persisted cache rules, use `docs/ai/data-cache-time.md`.

## Purpose

Album Detail and Person Detail are direct photo-grid screens backed by Room, Immich detail sync, shared row/Mosaic layout orchestration, and `StaticPhotoOverlay`.

Both screens share the same display architecture:

- A screen ViewModel owns owner-specific data loading, sync state, title/snackbar state, and overlay return state.
- `PhotoGridDetailLayoutCoordinator` owns layout state transitions, grouping, row-packing, Mosaic runtime work, persistent Mosaic artifact reads/writes, generation guards, and display publication.
- `PhotoGridDetailContent` owns Compose measurement and scroll/visibility reporting.
- Domain models own the display surface: `HeaderItem`, `RowItem`, `MosaicBandItem`, `PlaceholderItem`, and `ErrorItem`.
- Repositories and UseCases own network/Room synchronization. They must not pack rows or compute Mosaic assignments.

```mermaid
flowchart TD
    Screen[AlbumDetailScreen / PersonDetailScreen]
    VM[AlbumDetailViewModel / PersonDetailViewModel]
    Coordinator[PhotoGridDetailLayoutCoordinator]
    Content[PhotoGridDetailContent]
    Overlay[StaticPhotoOverlay]
    UseCases[Album / Person / Asset UseCases]
    Room[(Room cache)]
    API[Immich API]
    Scheduler[MosaicWorkScheduler]
    Engine[MosaicRenderEngine]
    Cache[Detail Mosaic artifact tables]

    Screen --> VM
    Screen --> Content
    Screen --> Overlay
    VM --> UseCases
    UseCases --> Room
    UseCases --> API
    VM --> Coordinator
    Content --> VM
    Coordinator --> Scheduler
    Scheduler --> Engine
    Coordinator --> Cache
    Coordinator --> VM
```

## Screen Responsibilities

`AlbumDetailScreen` and `PersonDetailScreen` are thin Compose shells:

- create and hold the `LazyListState`;
- activate/deactivate foreground Mosaic ownership through the ViewModel lifecycle;
- render `PhotoGridDetailContent`;
- render `DetailTopBar` with `PhotoGridDetailActions`;
- host `StaticPhotoOverlay`;
- reset their non-saveable `LazyListState` to index `0` after the first nonempty display list for each composition;
- use `PhotoOverlayHost.onOverlayDismissed` to map the current asset id through the current `displayIndex` and restore only a valid grid source after that screen's overlay closes.

The screens do not group assets, pack rows, compute Mosaic bands, read cache artifacts, or decide Mosaic fallback policy.

`PhotoGridDetailContent` reports:

- available grid width from `BoxWithConstraints`;
- visible grid height after top/bottom chrome padding;
- visible group indexes from `visibleBucketIndexesForDisplayIndexes(...)`;
- active scroll state from `LazyListState.isScrollInProgress`;
- an explicit localized **Load more** footer plus guarded near-end pagination for Album and Person Detail.

`PhotoGridDetailContent` renders the same shared photo-grid display surface as
Timeline. Its `LazyColumn` must use stable `gridKey` values and
`photoGridDisplayItemContentType(...)` so `RowItem` and `MosaicBandItem` slots
are reused only across compatible child shapes. Shared grid thumbnails should
keep the normal scroll path plain; per-cell `AnimatedVisibility` and
`sharedElement` wiring belong only to the active open/dismiss transition or the
currently hidden asset.

Mosaic detail placeholders follow the Timeline exact-height contract: pending,
in-flight, retry, and partial states may render Mosaic placeholders or chunks
only with matching section geometry. Cache-off runtime compute stores section
geometry in memory; it does not read/write disk artifacts, but it still must not
publish rough partial Mosaic output.

Zoom behavior is shared:

- row-packing mode supports pinch and desktop wheel zoom;
- Mosaic mode disables zoom when `disableZoomWhenMosaicEnabled` is true or when `cacheMosaicResults` is true;
- measured width and viewport height flow back to the coordinator so row bounds and Mosaic cell size are derived from actual layout.

## ViewModel Responsibilities

Album and Person ViewModels are owner-specific adapters around the shared coordinator. Opened-owner syncs are wrapped in `SyncActivityTracker` so Android can mirror active sync work with the shared foreground sync notification. The Android service does not execute Album or Person sync logic. Each ViewModel uses one owner-local `DetailSyncGate` to serialize page-1 refresh, near-end paging, and Mosaic-triggered full sync; work must re-read `hasMore` and `nextPage` only after acquiring that gate.

Album Detail owns:

- `albumId` and album title;
- cached album-name lookup before network sync;
- `GetAlbumDetailUseCase.observeAssets(albumId)` collection;
- page-1 refresh and near-end pagination through `GetAlbumDetailUseCase`;
- server-provided album order and `nextPage` tracking;
- banner versus full-screen error state;
- `DetailMosaicCacheOwnerType.ALBUM`;
- persistent Mosaic cache eligibility only when the full owner is known (`hasMore == false`).

Person Detail owns:

- `personId` and display name from navigation;
- `GetPersonAssetsUseCase.observe(personId)` collection;
- page-1 refresh on cold and warm opens;
- `loadMore()` pagination through `GetPersonAssetsPageUseCase`;
- duplicate load-more guards and sync-in-progress snackbar;
- `DetailMosaicCacheOwnerType.PERSON`;
- persistent Mosaic cache eligibility only when the full owner is known (`hasMore == false`).

Both ViewModels expose the same layout callbacks:

- `setAvailableWidth(...)`;
- `setAvailableViewportHeight(...)`;
- `setTargetRowHeight(...)`;
- `setGroupSize(...)`;
- `setViewConfig(...)`;
- `prepareMosaicViewConfig(...)`;
- `setVisibleBucketIndexes(...)`;
- `setScrollInProgress(...)`.

## Loading And Sync

Detail screens are cached-first. Room emissions are the render source of truth; network sync updates Room and returns whether ordered visible content changed.

Album Detail:

```mermaid
sequenceDiagram
    participant UI as AlbumDetailScreen
    participant VM as AlbumDetailViewModel
    participant UC as GetAlbumDetailUseCase
    participant Room as Room
    participant API as Immich API
    participant Coord as PhotoGridDetailLayoutCoordinator

    UI->>VM: create(albumId)
    VM->>UC: getCachedAlbumName(albumId)
    VM->>UC: observeAssets(albumId)
    UC-->>VM: cached Room assets
    VM->>Coord: handleAssetEmission(assets)
    Coord-->>VM: displayItems
    VM->>UC: sync page 1
    UC->>API: fetch album metadata and albumIds search page
    UC->>Room: update page refs/assets
    UC-->>VM: albumName + order + hasMore,nextPage,changed
    VM->>Coord: handleSyncedContentChange(changed)
    Coord-->>VM: rebuild only if ordered content changed
    UI->>VM: near end
    VM->>UC: sync nextPage with album order
```

Person Detail:

```mermaid
sequenceDiagram
    participant UI as PersonDetailScreen
    participant VM as PersonDetailViewModel
    participant UC as Person UseCases
    participant Room as Room
    participant API as Immich API
    participant Coord as PhotoGridDetailLayoutCoordinator

    UI->>VM: create(personId)
    VM->>UC: observe(personId)
    UC-->>VM: cached Room assets
    VM->>Coord: handleAssetEmission(assets)
    VM->>UC: get page 1
    UC->>API: fetch first page
    UC->>Room: update page refs
    UC-->>VM: hasMore,nextPage,changed
    VM->>Coord: handleSyncedContentChange(changed)
    UI->>VM: near end
    VM->>UC: get page nextPage
```

Sync success and ordered content change are separate signals. Album-name updates, pagination flags, last-sync timestamps, and cache bookkeeping must not repack rows or rebuild Mosaic unless ordered assets or layout-affecting metadata changed.

Album and Person load-more rules:

- ignore when sync/build is active; Person Detail emits `SyncInProgress` for that user action;
- ignore when already loading more;
- ignore when `hasMore == false`;
- acquire the owner sync gate, then atomically claim `isLoadingMore` and read the current server-provided `nextPage`;
- update `hasMore` and `nextPage` only from the successful page response; `nextPage` is the strictly parsed server-returned token, never `page + 1`;
- require a positive numeric token that moves strictly forward and has not
  already been consumed; malformed, equal, backward, cyclic, or schema-overflow
  tokens fail the sync before Room writes and clear the screen's rejected cursor
  (`hasMore=false`, `nextPage=null`) so automatic pagination cannot retry it;
- preserve the current cursor after a transient page failure so the explicit
  retry path remains available;
- after a successful terminal page or full sync, read and publish the new detail `lastSyncedAt` from Room;
- call `handleSyncedContentChange(result.changed)` after page writes.

Album and Person repositories enumerate the raw page before filtering
unsupported media. Supported refs retain `baseRawOffset + rawIndex`, so
`sortOrder` can be sparse. Incremental comparison uses exact persisted
`(assetId, sortOrder)` windows: a nonterminal page owns its full requested raw
range, while a terminal page owns the entire persisted tail. This both removes
stale rows from short pages and self-heals refs written by the former compact
ordering without a Room migration. If a valid server token skips numeric pages,
an unchanged current window clears only the skipped raw range; a changed window
still truncates its tail to avoid mixing snapshots. Correcting refs alone is not
an ordered visible-content change and does not trigger layout/Mosaic
invalidation. A returned token must also resolve to a raw page window whose
start/end and per-item raw ordinals fit the current Room `Int sortOrder` schema;
otherwise the response is treated as an API compatibility failure and the
previous refs remain intact.

The footer appears only while `hasMore` is true. It displays a **Load more** button while idle and the existing progress indicator while loading. Automatic pagination is eligible only after the list can scroll backward, the user is near the end, and no request is active; this prevents a short first page or placeholder layout from starting a request. The explicit button remains available for short or unscrollable first pages.

Opened-owner sync is separate from Mosaic settings. Sync can change the ordered
asset list, which may invalidate layout, but sync does not decide whether
Mosaic, Mosaic cache results, families, columns, or zoom behavior are enabled.
Those choices come from persisted `ViewConfig` and the Mosaic settings dialog.

## Layout Coordinator

`PhotoGridDetailLayoutCoordinator` is generic over the screen state type and receives state accessors from the owner ViewModel.

It owns:

- measured width and viewport-height derived row bounds;
- saved target row-height preference and debounced persistence;
- `GroupSize` and normalized `ViewConfig`;
- `PhotoGridLayoutRunner` for cancellable display projection;
- `assetRevision` and group fingerprints;
- full display cache for final complete output;
- per-group ready display cache;
- per-group resolved real Mosaic display-band cache;
- runtime Mosaic group states;
- deferred offscreen Mosaic publications;
- resume/retry jobs;
- persistent Mosaic artifact reads and writes.

```mermaid
flowchart LR
    Inputs[assets + width + viewport + groupSize + ViewConfig]
    Fingerprint[group fingerprints]
    CacheKey[display/group cache keys]
    Mode{Mosaic enabled?}
    Rows[packIntoRows]
    Mosaic[Mosaic group pipeline]
    State[ViewModel displayItems + displayIndex]

    Inputs --> Fingerprint
    Fingerprint --> CacheKey
    CacheKey --> Mode
    Mode -- no --> Rows
    Mode -- yes --> Mosaic
    Rows --> State
    Mosaic --> State
```

Layout invalidation rules:

- Width, viewport height, row-height, group size, or view config changes enter the coordinator's runtime-state transaction, clear old layout/runtime/deferred caches, advance the Mosaic layout generation, and schedule the replacement layout in that order. Cache reads and Mosaic assignment compute happen later, outside that short transaction.
- Asset emissions bump `assetRevision` only when the current grouped fingerprint differs from the last rendered fingerprint.
- Content-change signals set `pendingSyncedContentRevision` when Room has not emitted the changed assets yet.
- If group order changes, all per-group display/runtime caches are cleared inside the same transaction because `bucketIndex` is part of item identity.
- If only some groups changed, only those group caches and runtime states are removed inside the same transaction before the replacement layout is scheduled.
- The resolved display-band cache is invalidated with the matching per-group display cache. It is keyed by the same group/config/fingerprint identity and is never allowed to outlive width, grouping, config, or ordered-content changes.

## Mosaic Settings

Album Detail and Person Detail use the shared `MosaicViewConfigIconMenu` through
`PhotoGridDetailActions`. The menu edits a draft `ViewConfig` and calls the
screen's `prepareMosaicViewConfig(...)` only when the draft has
`cacheMosaicResults = true`.

Settings owned by `ViewConfig`:

- `mosaicEnabled`: chooses Mosaic versus standard row-packing display.
- `mosaicFamilies`: chooses which Mosaic template families the engine may use.
- `mosaicColumnCount`: fixes Mosaic cell count and therefore cell height.
- `cacheMosaicResults`: chooses persistent artifact usage and blocking apply behavior.
- `disableZoomWhenMosaicEnabled`: disables row-height zoom while Mosaic is enabled.

Apply behavior:

```mermaid
flowchart TD
    Draft[User edits Mosaic settings draft]
    Changed{Draft differs from current ViewConfig?}
    Cache{cacheMosaicResults true?}
    Prepare[prepareMosaicViewConfig]
    Cancel[Cancel obsolete runtime/geometry work]
    CacheWork[Prepare persistent artifacts]
    Success{Prepare success?}
    Persist[Persist ViewConfig]
    Rebuild[Coordinator clears layout caches and rebuilds display]
    Error[Keep dialog open and show apply error]

    Draft --> Changed
    Changed -- no --> Draft
    Changed -- yes --> Cache
    Cache -- no --> Persist
    Cache -- yes --> Prepare
    Prepare --> Cancel
    Cancel --> CacheWork
    CacheWork --> Success
    Success -- yes --> Persist
    Success -- no --> Error
    Persist --> Rebuild
```

When `cacheMosaicResults = false`:

- the dialog applies immediately without calling `prepareMosaicViewConfig(...)`;
- the normalized config is persisted through `SetViewConfigAction`;
- the coordinator clears in-memory layout caches and schedules a display rebuild;
- persistent detail Mosaic artifacts are not read because `persistentCacheLookup(...)` returns `null`;
- completed runtime groups are not written to persistent detail Mosaic artifacts because there is no lookup;
- Mosaic-enabled display still computes runtime Mosaic bands for visible/current groups through `MosaicWorkScheduler`;
- incomplete, failed, cancelled, or offscreen runtime groups render placeholders until real runtime Mosaic output exists;
- row-packing is used only when `mosaicEnabled = false`.

When `cacheMosaicResults = true`:

- the dialog enters an applying state and blocks dismissal;
- `prepareMosaicViewConfig(draft)` must complete before the config is persisted;
- if preparation fails, the current `ViewConfig` remains active and the dialog shows an apply error;
- if preparation succeeds, the draft config is persisted and the coordinator rebuilds against the prepared artifact identity.

Detail preparation algorithm:

1. Normalize the draft config.
2. Return success immediately if Mosaic is disabled or cache results are disabled.
3. Return success immediately if there are no assets.
4. Cancel obsolete runtime/geometry work across live Timeline, Album Detail, and Person Detail owners through `MosaicConfigPreparationCoordinator`.
5. Require a complete owner snapshot.
6. Require measured Mosaic layout metrics from current width and draft column count.
7. Build a `DetailMosaicCacheLookup` for owner, group size, draft columns, families, width/cell/max-row/spacing keys, and artifact versions.
8. Read existing artifacts and accept only strict ready display rows for the current owner fingerprint.
9. For each grouped section, reuse ready artifacts when valid.
10. Compute missing groups through `MosaicRenderEngine.computeSection(...)`.
11. Upsert assignment, display-band, and section-geometry artifacts for computed groups.
12. Once every group has output, upsert aggregate owner geometry.
13. Return success only after required preparation work completes.

Config-prep cancellation cancels the coordinator's `PhotoGridLayoutRunner`, pending resume/deferred-flush jobs, and `MosaicWorkScheduler` work for the owner. It clears incomplete runtime/deferred state only; current display items, ready group caches, section geometry caches, and persisted artifacts are preserved. A failed preparation does not automatically resume cancelled old-config work.

```mermaid
flowchart LR
    Config[Draft ViewConfig]
    Complete{Complete owner?}
    OwnerSync[Album or Person ViewModel syncAll if hasMore]
    Failure[Coordinator returns failure if owner incomplete]
    Lookup[DetailMosaicCacheLookup]
    Artifacts[Read artifacts]
    Groups[Grouped asset sections]
    Ready{Strict ready row?}
    Engine[MosaicRenderEngine.computeSection]
    Write[Upsert assignment/display/section geometry]
    Aggregate[Upsert aggregate geometry]

    Config --> Complete
    Complete -- owner hasMore --> OwnerSync
    Complete -- still incomplete --> Failure
    Complete -- yes --> Lookup
    OwnerSync --> Lookup
    Lookup --> Artifacts
    Artifacts --> Groups
    Groups --> Ready
    Ready -- yes --> Aggregate
    Ready -- no --> Engine
    Engine --> Write
    Write --> Aggregate
```

Album and Person cache preparation cannot use partial paged data. If the user
enables cached Mosaic results while `hasMore == true`, the owner ViewModel first
waits for any active owner sync or page request, rechecks `hasMore`, performs its full sync,
updates assets, sets `hasMore = false`, handles any content change, and only
then prepares detail Mosaic artifacts. The full sync must clear its temporary
building/sync state on failure and return that failure to the settings action.

Cache results affect runtime reads and writes, not opened-owner sync. Normal
album/person sync may update ordered assets; it does not build Mosaic artifacts
unless the settings path or runtime cache-enabled path needs them for the active
owner/config.

## Row-Packing Path

When Mosaic is disabled, the coordinator builds standard grouped justified rows:

1. Return empty display when assets are empty or width is not ready.
2. Group assets with `groupAssets(assets, groupSize)`.
3. Emit a `HeaderItem` for each non-empty group label. Section identity uses a stable date key, while the UI resolves its typed month/day/Unknown-date display label from Compose resources. Missing or malformed asset timestamps remain visible in a final Unknown-date group rather than being discarded.
4. Call `packIntoRows(...)` for each group with:
   - current measured width;
   - current target row height;
   - `GRID_SPACING_DP`;
   - `rowHeightBounds.max`;
   - normal wide-image promotion.
5. Store final output in the full display cache when the runner generation and asset revision still match.

Row-packing never uses Mosaic placeholders or Mosaic cache artifacts. Full algorithm details live in `docs/ai/row-packing.md`.

## Mosaic Display Path

When Mosaic is enabled and width/assets are ready, the coordinator builds one Mosaic section per grouped detail bucket.

Initial group item selection is ordered by group index:

1. Use in-memory `groupDisplayCache` if final ready output exists for the exact group/config/fingerprint.
2. Else rebuild final ready output from in-memory resolved real display-band records if they validate against the current ordered group assets.
3. Else use validated persistent display artifacts if cache results are enabled and the owner snapshot is complete. Persistent reads also seed the in-memory resolved-band cache when the stored bands are all real and fully cover the group.
4. Else use valid section geometry, from memory or persistence, to size placeholders exactly.
5. Else use runtime partial/in-flight state if available.
6. Else render blocking/loading placeholders for the group; these must not be replaced by partial Mosaic chunks.

```mermaid
flowchart TD
    Group[Keyed detail group]
    Memory{Final groupDisplayCache?}
    MemoryBands{Resolved real band records?}
    Persistent{Strict persistent artifacts?}
    Runtime{Runtime state?}
    Geometry{Section geometry?}
    Placeholder[PlaceholderItem rows]
    ExactPlaceholder[Exact-height PlaceholderItem]
    Ready[MosaicBandItem ready output]
    Partial[Real partial Mosaic bands + placeholders]

    Group --> Memory
    Memory -- yes --> Ready
    Memory -- no --> MemoryBands
    MemoryBands -- yes --> Ready
    MemoryBands -- no --> Persistent
    Persistent -- yes --> Ready
    Persistent -- no --> Geometry
    Geometry -- yes --> ExactPlaceholder
    Geometry -- no --> Runtime
    Runtime -- InFlight/Failure --> Placeholder
    Runtime -- Partial --> Partial
    Runtime -- none --> Placeholder
```

The runtime group pipeline:

1. Build `KeyedAssetGroup` values from grouped assets, section labels, asset fingerprints, and exact layout/config keys.
2. Remove runtime states whose keys no longer exist.
3. Mark groups outside the visible-plus-one-neighbor window as `InFlight` and compute only that window.
4. Reorder runnable work with visible groups first.
5. Run CPU work through `MosaicWorkScheduler`.
6. Call `MosaicRenderEngine.computeSection(...)` with checkpoint, progress chunk, and cancellation callbacks.
7. Publish valid partial chunks through `projectPartialSectionWithGeometry(...)` only when section geometry exists; otherwise keep exact placeholders or the current placeholder/old display.
8. Publish completed ready output immediately for visible groups or defer offscreen replacement.
9. Store completed ready group items in `groupDisplayCache` and section geometry in `groupSectionGeometryCache`. If the ready output is made entirely of real `MosaicBandItem` records and covers the current ordered group assets, also store portable `MosaicDisplayBandRecord` values in `groupDisplayBandCache` as a real-band memory optimization.
10. Write assignments, mixed display records, section geometry, and eventually aggregate geometry when persistent cache is eligible.
11. Cache the full display list only after every current group has final ready output and no placeholders remain.

Mosaic-enabled incomplete runtime states must render placeholders, not standard row-packing fallback and not fallback-thumbnail bands. If exact section geometry exists, placeholders use the stored section height; partial output may publish only when real chunks plus unresolved placeholders preserve the final section height within tolerance. Completed ready projection may contain cropped full-width `RowItem(kind = MOSAIC_FALLBACK)` rows if the engine produced them for the active config.

`groupDisplayBandCache` is a real-band optimization only. Assignments and ready display items remain the canonical state for the current render pass, and fallback rows are deliberately excluded from the resolved-band cache. If resolved records are missing or fail coverage validation, the coordinator continues through persistent cache, runtime state, or placeholders rather than trusting stale geometry.

## Mosaic Scheduling

Album and Person Detail use `MosaicWorkScheduler`, not the Timeline queue actor.

Scheduler ownership:

- the screen activates its owner key on composition and clears/cancels it on disposal;
- work is keyed by `ownerKey` and `group_$index`;
- `ForegroundVisible` is used for visible groups;
- `ForegroundPrefetch` is used for non-visible groups in the active owner;
- pending work is reprioritized whenever visible group indexes change;
- initial construction, resumed work, and retry eligibility use the visible group plus one neighboring group window;
- running prefetch can be cancelled when visible work exists.

```mermaid
stateDiagram-v2
    [*] --> None
    None --> InFlight: group queued or first runtime attempt
    InFlight --> Partial: progress chunk + checkpoint
    Partial --> Ready: computeSection Ready
    InFlight --> Ready: computeSection Ready before chunk
    InFlight --> RetryableFailure: failure
    Partial --> RetryableFailure: failure
    RetryableFailure --> InFlight: visible retry after backoff
    InFlight --> InFlight: cancelled before chunk
    Partial --> Partial: cancelled after chunk
    Ready --> [*]
```

Scroll behavior:

- `setScrollInProgress(true)` cancels deferred publish flushes but keeps runtime checkpoints/chunks.
- Only the visible group plus one neighboring group are runnable; all other missing groups become resumable `InFlight` placeholders.
- `setScrollInProgress(false)` schedules deferred publish flush and incomplete Mosaic resume.
- Resume snapshots `runtimeGroupStates` before iterating so mutation during resume cannot crash iteration.
- Retryable failures resume only when the group is in the visible-plus-neighbor window.
- Offscreen resume yields after each group so visible work can regain control.

Retry behavior:

- failure remains placeholder plus retryable runtime state;
- retry delay is exponential: `250ms`, `500ms`, `1000ms`, `2000ms`, `4000ms`, capped at `5000ms`;
- retry has no fixed attempt limit;
- ready output supersedes retry/partial/in-flight state for the active config.

## Persistent Mosaic Artifacts

Detail Mosaic persistence is owner-scoped:

- owner type: `ALBUM` or `PERSON`;
- owner id: album id or person id;
- group size;
- section index and section key;
- Mosaic column count;
- normalized families key;
- ordered asset fingerprint;
- width/cell-height/max-row-height/spacing geometry keys;
- artifact/display/geometry versions.

Artifacts:

- assignment rows store engine assignments;
- display rows store serialized mixed ready records: real `MosaicBandItem` bands and completed `RowItem(kind = MOSAIC_FALLBACK)` rows;
- section geometry stores per-group placeholder height plus geometry ranges. Geometry ranges record source range and layout height for each final real Mosaic band or completed fallback row, enabling exact-height partial placeholders;
- aggregate geometry stores owner-level placeholder height for the complete ordered display.

Per-group display readiness requires matching assignment, section geometry, and, when `cacheMosaicResults = true`, display rows for that group/config/fingerprint. Aggregate geometry is not allowed to invalidate otherwise valid per-group display artifacts; an interrupted cache build may have useful section rows even when the owner aggregate row is missing.

Strict persistent display read:

1. Assignment keys and section geometry keys must match the group key.
2. Display row column count must match.
3. Display records must cover current ordered assets with no gaps, overlaps, duplicate ids, unknown ids, invalid dimensions/heights, or mismatched source slices.
4. Invalid rows are treated as cache misses and runtime placeholders/compute take over.

Album cache policy:

- Album Detail can render partial paged data at runtime;
- persistent owner Mosaic artifacts may be read/written only when `hasMore == false`;
- `prepareMosaicViewConfig(...)` with cache enabled performs full album sync first if pages remain.

Person cache policy:

- People list must not preload per-person detail assets;
- Person Detail can render partial paged data at runtime;
- persistent owner Mosaic artifacts may be read/written only when `hasMore == false`;
- `prepareMosaicViewConfig(...)` with cache enabled performs full person sync first if pages remain.

```mermaid
flowchart TD
    Prepare[prepareMosaicViewConfig]
    CacheEnabled{Mosaic + cache enabled?}
    OwnerComplete{Owner snapshot complete?}
    OwnerFullSync[Album or Person syncAll if hasMore]
    Lookup[Build DetailMosaicCacheLookup]
    Read[Read artifacts]
    Missing{Group artifact ready?}
    Compute[Compute group via MosaicRenderEngine]
    Upsert[Upsert assignment/display/section geometry]
    Aggregate[Upsert aggregate geometry]
    Apply[Persist/apply ViewConfig]

    Prepare --> CacheEnabled
    CacheEnabled -- no --> Apply
    CacheEnabled -- yes --> OwnerComplete
    OwnerComplete -- yes --> Lookup
    OwnerComplete -- no --> OwnerFullSync
    OwnerFullSync --> Lookup
    Lookup --> Read
    Read --> Missing
    Missing -- yes --> Compute
    Missing -- no --> Aggregate
    Compute --> Upsert
    Upsert --> Aggregate
    Aggregate --> Apply
```

## Overlay And Return Targeting

Both screens use `PhotoOverlayHost` and `StaticPhotoOverlay`.

Open flow:

1. Display item click passes asset id to `PhotoOverlayHost`.
2. `resolveInitialIndex` maps id into the current ordered asset list.
3. `StaticPhotoOverlay` pages through the same asset list and fetches asset details on demand.
4. The clicked grid cell is hidden through `hiddenAssetId` while shared transition is active.

Dismiss flow:

1. Every host-level Back and `StaticPhotoOverlay` dismissal routes through `PhotoOverlayHost`'s one dismissal callback before the host hides the overlay.
2. Album/Person maps the returned current asset id through `displayIndex.assetDisplayIndexById`.
3. If the mapping is valid and the item is not fully visible, the `LazyListState` scrolls to the mapped display item.
4. A null or unmapped id does not change the detail list position.

Display projection must keep `assetDisplayIndexById` accurate for row-packing, placeholders, partial Mosaic, ready Mosaic, cache reads, and hidden-asset transitions. Placeholder-only groups cannot provide an asset cell target, but once a real asset display item exists, overlay dismiss must land on the current asset.

## Failure And Empty States

Initial uncached failure:

- show full-screen error with retry;
- do not publish partial grid state with no cached assets.

Cached-owner refresh failure:

- keep cached grid visible;
- show dismissible banner;
- do not clear display items or layout caches.

Mosaic runtime failure:

- keep/restore placeholders for the group;
- store retryable runtime state;
- retry when visible or in the visible-plus-neighbor window;
- never poison `groupDisplayCache` or full `displayCache` with placeholders or partial output.

Load-more failure:

- clear `isLoadingMore`;
- keep current album/person assets and display output;
- a later near-end trigger may retry.

## Invariants

- ViewModels use UseCases and Actions, never repositories.
- Composables render `PhotoGridDisplayItem`s and report measurements/visibility only.
- Detail layout math lives in `PhotoGridDetailLayoutCoordinator`.
- Album/Person runtime Mosaic goes through `MosaicWorkScheduler` and `MosaicRenderEngine`.
- Timeline Mosaic queue rules must not be copied into detail screens.
- Search remains row-packing only and is not part of this architecture.
- Cache-disabled detail rendering must not read persistent Mosaic artifacts.
- Persistent Album and Person Mosaic artifacts require a complete owner snapshot.
- Final display caches must not contain placeholders, partial runtime output, or standard `RowItemKind.STANDARD` rows while Mosaic is enabled. Completed `RowItemKind.MOSAIC_FALLBACK` rows are valid final Mosaic output.
- Shared element return targeting depends on stable grid keys and `assetDisplayIndexById`.

## Verification Targets

When changing this area, prefer focused tests for:

- cached assets render before opened-owner sync completes;
- unchanged sync does not rebuild display items;
- changed sync invalidates only changed groups when group order is stable;
- group order changes clear group caches and runtime states;
- Person `loadMore()` ignores duplicate and sync-overlapping requests;
- Album and Person page refresh, full Mosaic preparation, and initial sync cannot overlap or use a stale page token;
- Person cache preparation syncs the full owner before writing persistent artifacts;
- cache-disabled detail Mosaic skips persistent artifacts and computes runtime groups;
- visible Mosaic groups outrank offscreen work;
- initial, resumed, and retry work stays within the visible-plus-neighbor window;
- scroll pause leaves offscreen groups resumable;
- retryable failures remain placeholders and later retry;
- partial chunks project real bands plus placeholders, not fallback-thumbnail bands;
- final ready groups can write assignments, mixed display records, section geometry, and aggregate geometry;
- overlay dismiss maps the current asset id to a valid display item after row/Mosaic transitions.
- initial short-page pagination requires the explicit button, while a scrolled near-end request is automatic and loading/exhausted states are rejected.
