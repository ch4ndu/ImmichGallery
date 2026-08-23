# Timeline Sync Architecture

Load this when changing Timeline cold sync, warm launch sync, manual refresh, bucket materialization, no-op refresh handling, Timeline sync metadata, or sync-driven Mosaic work.

For Timeline rendering, overlay, scrollbar, and Mosaic display behavior, also read `docs/timeline.md`. For Room/cache rules, read `docs/ai/data-cache-time.md`. For RowPacking invalidation, read `docs/ai/row-packing.md`.

## Sync Ownership

- `TimelineViewModel.syncFromServer(...)` chooses cold-sync and cached-launch modes and owns their user-facing loading state.
- Manual Timeline refresh is coordinated by `TimelineManualSyncCoordinator`. Android gives each command a process-unique request ID, stamps it with the current session generation/owner epoch at the user action, starts `SyncForegroundService`, and the service validates that same session stamp before running the coordinator from a service-owned coroutine scope after entering foreground. A command delayed across logout or session replacement is rejected. JVM/iOS run the same identified, stamped coordinator request from the ViewModel.
- `TimelineViewModel` observes manual coordinator events/results and applies UI state, banners, removed-bucket invalidation, changed-bucket publication, Mosaic geometry publication, and visible-refresh resumption. The coordinator/action returns structured sync results and does not mutate screen state directly.
- `GetTimelineBucketsUseCase.sync()` refreshes bucket metadata through `TimelineRepository.syncBuckets()`.
- `SyncAllTimelineAssetsAction` and `LoadBucketAssetsAction` refresh bucket assets through `TimelineRepository.syncBucketAssets(...)`.
- `TimelineRepository` owns Immich API fetches, Room writes, hidden-asset filtering, edit enrichment, ordered change detection, and sync metadata.
- `PrepareTimelineMosaicCacheAction` is sync-time Mosaic preparation. It runs only when Mosaic is enabled and `ViewConfig.cacheMosaicResults = true`.
- Runtime Mosaic is render-demand work. Sync paths must not fall through to runtime Mosaic when cache results are off.

## Sync Mode Selection

`TimelineViewModel.syncFromServer()` computes cold vs cached launch:

```text
hasWarmTimelineCache = hasCachedBuckets && hasCompletedColdTimelineSync

if !hasWarmTimelineCache:
  mode = Cold
else:
  mode = CachedLaunch
```

- Cached bucket metadata alone is not enough for warm launch. A failed cold sync can write metadata before assets and geometry are complete.
- The cold-complete marker lives in `sync_metadata`.
- Warm launch is allowed only when bucket metadata exists and the cold-complete marker exists.
- Manual refresh is the explicit top-bar refresh path after warm cache exists and bypasses this mode function by building a `TimelineManualSyncRequest`.
- `TimelineViewModel` can run a cache-only warm policy through `DISABLE_TIMELINE_NON_MANUAL_SERVER_SYNC`. When enabled, completed-cold-cache launch and bucket visibility changes do not hit the server; cold sync and manual refresh remain the only Timeline server refresh paths.

## Cold Sync

Cold sync is the only blocking Timeline sync path. It runs when there is no warm Timeline cache or the previous cold sync did not mark completion.

### UI State

1. `TimelineViewModel` sets `_isBuilding = true` and clears `_buildError`.
2. `TimelineScreen` renders the full-screen blocking loading state.
3. The shell hides normal top and bottom controls while the blocking state is active.
4. The normal grid, scrollbar, overlay host, banners, and visible-bucket effects are not composed.
5. The loading gate still reports full Timeline width and height so later geometry requests use the same full-surface bounds.

### Algorithm

1. Wait until `availableWidth > 0`.
2. Read the previous `lastSyncedAt` value for display.
3. Fetch `/api/timeline/buckets`.
   Before the first Timeline request in a process, fetch stable `/api/server/version` for diagnostics. Timeline endpoints remain internal, so decode failures and mismatched column lengths are reported as a typed incompatibility error that includes the server version and endpoint. Do not reject a server solely because its version is unfamiliar.
4. Write `TimelineBucketEntity` rows and metadata sync timestamp.
5. Set `_bucketData.buckets` to the fetched bucket list.
6. Sync every bucket asset through `SyncAllTimelineAssetsAction`.
7. Retry failed asset buckets once.
8. If any bucket still fails:
   - set `_isBuilding = false`;
   - set `_buildError = NoConnectionToServer`;
   - do not mark cold sync complete.
9. Clear Mosaic cache rows for changed successful buckets.
10. If Mosaic is disabled or `cacheMosaicResults = false`:
   - skip `PrepareTimelineMosaicCacheAction`;
   - publish successful buckets as cached, not loaded;
   - mark every successful bucket server-refreshed;
   - mark cold sync complete;
   - clear the blocking state;
   - request visible render-demand Mosaic after the UI can report visibility.
11. If Mosaic is enabled and `cacheMosaicResults = true`:
   - build a full-screen `TimelineMosaicGeometryRequest`;
   - call `PrepareTimelineMosaicCacheAction` for successful buckets;
   - require every successful bucket to produce aggregate geometry;
   - publish cached and geometry-ready buckets;
   - mark every successful bucket server-refreshed;
   - mark cold sync complete;
   - clear the blocking state.

   Each section geometry row written here carries the aggregate section
   `placeholderHeight` plus `geometryRangesJson`. Geometry ranges store the source
   range and layout height for each final real Mosaic band or completed fallback row. Timeline renders one
   aggregate placeholder per pending section to keep LazyColumn item count low,
   and uses geometry ranges to keep partial runtime placeholders height-exact.
   Manual refresh per-bucket precompute writes the same field.

### Bucket Asset Sync

For each bucket:

1. Read ordered Room assets before the fetch.
2. Fetch `/api/timeline/bucket?timeBucket=...`.
3. Filter hidden assets before writing or presenting.
4. Map responses to candidate `AssetEntity` rows and ordered `TimelineAssetCrossRef` rows.
5. Skip the write path only when:
   - ordered visible `AssetEntity` fingerprints match;
   - ordered asset ids match;
   - every edited asset with dimensions is already `editsResolved = true` in Room.
6. If the skip predicate passes:
   - do not upsert assets;
   - do not replace refs;
   - do not run edit enrichment;
   - return `changed = false`.
7. Otherwise:
   - upsert assets;
   - replace refs atomically for that bucket;
   - run edit enrichment for eligible edited assets;
   - read ordered Room assets again;
   - return `changed = orderedAssetsChanged(before, after)`.

The returned `changed` value is a layout/content invalidation signal, not merely a network success signal.

## Warm Launch Sync

Warm launch is cached-first and non-blocking. After the cached bucket metadata
observation, `TimelineViewModel` runs a visible-first geometry phase:
bucket-aggregate and per-section geometry rows are read together for the
visible/target buckets plus radius neighbors. `timelineGeometryReady` gates only
that priority read, and opens even if the read fails so visible Mosaic work
cannot stall indefinitely. Remaining cached bucket geometry hydrates afterward
in small offscreen chunks.

### Startup Shape

1. Room bucket metadata is observed immediately.
2. Cached bucket ids are read from existing `timeline_asset_refs`.
3. `_bucketData.cachedBuckets` records buckets that have cached refs.
4. `_bucketData.loadedBuckets` means renderable assets are already in `bucketAssetsCache`; it is not a synonym for cached Room refs.
5. Timeline display projection is in-memory only. `buildTimelineState()`, `buildDisplayItems()`, and bucket projection must never read Room. If cached refs exist but assets are not materialized, the grid renders headers/placeholders.
6. Visible or targeted buckets materialize assets from Room first. For Mosaic buckets with matching aggregate geometry but no renderable section state yet, materialization must keep the exact aggregate placeholder height instead of replacing it with a count estimate. While lazy-list scrolling or scrollbar dragging is active, target changes are recorded but do not start materialization, Mosaic reads, or geometry work. At settle, the final scrollbar target is published first, followed by settled visible buckets. Current-radius neighbor prefetch begins only after `250ms` of continued idle time and publishes in bounded local chunks so launch or fast scroll does not trigger one RowPacking/display-index rebuild per bucket. If the cache-only warm policy is disabled, these buckets may then refresh from the server; if it is enabled, they stay cache-only until manual refresh.

### Background Metadata Refresh

When warm server refresh is enabled, warm launch `syncFromServer()` refreshes bucket metadata only:

1. Fetch `/api/timeline/buckets`.
2. Compare old and new metadata.
3. Mark count-changed buckets as stale.
4. Clear refs immediately only for removed buckets.
5. Keep count-changed bucket refs until that bucket asset refresh succeeds.
6. Replace bucket metadata rows.
7. Queue current visible/nearby buckets for serial server refresh.

Warm launch must not fetch every bucket's assets before first render.

### Cache-Only Warm Policy

When `DISABLE_TIMELINE_NON_MANUAL_SERVER_SYNC = true`, warm launch intentionally skips the server:

1. `syncFromServer(isFullRefresh = false)` still computes `CachedLaunch`, reads `lastSyncedAt`, clears transient syncing UI state, and logs that warm server sync was skipped.
2. It does not call `GetTimelineBucketsUseCase.sync()`, so bucket metadata stays whatever cold sync or manual refresh last wrote.
3. Visible, targeted, scrollbar, retry, and explicit bucket-load paths still materialize cached buckets so Room-backed rows can render.
4. Batch materialization reads Room assets outside the refresh mutex, claims duplicate in-flight buckets, writes `bucketAssetsCache`, and publishes `_bucketData.loadedBuckets` in bounded chunks. A bucket already marked loaded but missing in-memory assets is still materialization-eligible and repaints through a render-only materialization revision. In RowPacking mode this path must not request Mosaic reads, geometry reads, runtime Mosaic, or progressive Mosaic flushes.
5. After materialization, those paths return before queueing `pendingVisibleRefreshBuckets` or starting `visibleRefreshJob`.
6. Blocking Timeline Mosaic settings preparation uses already-cached bucket assets; it must not call `SyncAllTimelineAssetsAction` under this policy.
7. Manual refresh is the only warm-cache path that refreshes metadata and bucket assets from the server.

This policy is currently implemented as a compile-time experiment flag, but if it becomes permanent it should be promoted to a named Timeline sync mode rather than being treated as a transient optimization.

### Visible Bucket Refresh

When warm server refresh is allowed, visible refreshes are serial and cache-preserving:

1. Materialize cached Room assets for the requested bucket before network refresh when available.
2. Add the bucket to loaded/cached state if this is the first materialization.
3. Queue the visible or targeted bucket ahead of stale offscreen work.
4. Fetch server bucket assets.
5. If `changed = true`:
   - clear stale Mosaic artifacts for that bucket;
   - invalidate runtime Mosaic state for that bucket;
   - reload bucket assets from Room;
   - publish loaded state with an incremented asset revision;
   - run sync-time Mosaic precompute only if cache results are enabled.
6. If `changed = false` and the bucket was already loaded with in-memory assets:
   - do not republish loaded state;
   - do not bump the asset revision;
   - do not repack RowPacking rows;
   - do not request Mosaic cache reads;
   - mark the bucket server-refreshed.
7. If `changed = false` but the bucket was not materialized:
   - load Room assets into `bucketAssetsCache`;
   - publish loaded state without revision increment;
   - request render-demand Mosaic as needed.

## Manual Refresh

Manual refresh is the top-bar full Timeline refresh after warm cache exists. It is broader than warm launch but still preserves cached UI.

### Entry

1. `refreshAll()` builds a `TimelineManualSyncRequest` from the current group, Mosaic config, banner state, and valid geometry snapshot when available.
2. `_uiConfig.isSyncing` becomes true and sync banners are cleared.
3. `beginManualRefresh()` sets `manualRefreshActive = true`, clears pending visible refresh queues, and waits for any active visible refresh job to finish.
4. On Android, `TimelineManualSyncStarter` starts `SyncForegroundService` with the request. The service calls `startForeground(...)` immediately, then runs `TimelineManualSyncCoordinator` from a service-owned coroutine scope. On JVM/iOS, the ViewModel runs the coordinator directly.
5. `TimelineManualSyncAction` refreshes bucket metadata, refreshes every metadata bucket asset with one retry for failures, preserves cached data on failures, clears removed/changed Timeline Mosaic artifacts, and runs sync-time Mosaic precompute only when Mosaic cache results are enabled and a valid geometry request was supplied.
6. `TimelineViewModel` consumes the coordinator result, publishes removed/changed buckets, records successful buckets as server-refreshed, publishes Mosaic geometry summaries from precompute, sets success/failure banners, calls `endManualRefresh()`, and resumes visible refresh.
7. Album/Person detail syncs may still run concurrently; the foreground notification stays active until every tracked foreground work operation finishes.

The request carries both its command ID and originating session stamp through
the Android Intent. Timeline retains the currently active command ID and only a
matching command may apply Started/Completed/Failed/session-cancelled UI state;
a late terminal from an older overlapping command cannot stop a newer refresh.
The coordinator registers that exact stamp before emitting `Started` or reading
cache/network state, so a queued command cannot re-register under a replacement
session. `SyncActivityTracker` is the sole foreground-activity count owner; its
final decrement runs in non-cancellable cleanup, and the Android service removes
foreground state only when the shared count reaches zero. When session mutation
cancels or rejects the command, the coordinator emits a session-cancelled event
before preserving cancellation. Timeline waits for the mutation's published
admitted outcome before applying that exact command's terminal event: a failed logout retains the owner
epoch, clears the still-mounted Timeline's transient manual-refresh state, and
resumes visible work under the new admitted generation without reporting a
network failure; a successful replacement changes the owner epoch and discards
the old terminal. Buffered Started/Completed/Failed events run through isolated
full-stamp gates, so they cannot cancel the long-lived event collector or update
a newer session.

### Cache-Off Mosaic Cleanup

If `ViewConfig.cacheMosaicResults = false`, manual refresh does not clear Timeline or Detail Mosaic state before asset sync. A no-op manual refresh must preserve current rows, runtime Mosaic state, geometry state, and Album/Person Detail Mosaic artifacts. Changed Timeline buckets use the normal per-bucket invalidation path after `changed = true`; removed buckets clear their Timeline refs and Timeline Mosaic artifacts. Detail screens own Detail Mosaic invalidation because Timeline sync does not know the affected Album/Person owners.

### Asset Refresh Loop

Manual refresh loops through every current metadata bucket inside `TimelineManualSyncAction`:

1. Call `SyncAllTimelineAssetsAction` for all buckets.
2. Retry failed buckets once.
3. Keep cached rows visible on failure by applying failure state only after the coordinator result returns.
4. For changed successful buckets, clear persisted Timeline Mosaic artifacts before optional precompute.
5. For unchanged buckets, suppress publication and Mosaic work.
6. At the end, the ViewModel sets:
   - `bannerError = CannotConnectToServer` if any bucket failed;
   - `bannerSuccess = ConnectedToServer` only when recovering from a previous banner error;
   - `isSyncing = false`.

### Mosaic Policy

- When `cacheMosaicResults = true`, changed successful buckets may run sync-time Mosaic precompute.
- When `cacheMosaicResults = false`, changed buckets do not precompute Mosaic and sync-driven helper paths must not compute runtime Mosaic.
- Runtime Mosaic resumes later only through explicit render-demand sources: visible buckets, targeted buckets, or scroll-settled deferred work.

## No-Op Refresh Contract

A no-op bucket refresh is a successful server fetch whose ordered visible content still matches Room.

No-op refresh must not:

- replace identical Timeline bucket metadata rows;
- rewrite Room asset/ref rows when the repository skip predicate passes;
- bump per-bucket asset revision;
- clear Mosaic assignments, display cache, section geometry, or aggregate geometry;
- clear Album/Person Detail Mosaic artifacts;
- clear RowPacking derived bucket cache;
- publish a new loaded state for already-loaded buckets;
- publish a new bucket metadata state for identical bucket emissions;
- request sync-time or render-demand Mosaic work.

No-op refresh may:

- update last server-refreshed bookkeeping;
- clear a loading marker if this request set one;
- keep cached rows visible.

## Failure Rules

- Cold sync failure keeps the blocking retry state and does not mark cold sync complete.
- Warm visible refresh failure can show a bucket error only when no cached rows are available.
- Manual refresh failure keeps cached rows visible and reports a banner after the loop.
- Removed buckets clear refs and Mosaic artifacts immediately.
- Count-changed buckets keep old refs until the bucket asset refresh succeeds.
- Cache-only warm launch cannot discover removed or count-changed buckets until manual refresh or a future cold sync.
- Interactive scroll pauses new Timeline Mosaic demand without cancelling the active bucket worker. Cancellation from config/generation invalidation is control flow, not a sync failure.

## Verification Expectations

Tests or manual log checks for sync changes should cover:

- cold sync cache-on prepares Mosaic artifacts before completion;
- cold sync cache-off skips Mosaic preparation and waits for render-demand runtime Mosaic;
- warm launch does not sync every bucket asset before first render;
- cache-only warm launch skips metadata sync and all non-manual bucket server refresh while still materializing cached Room rows;
- unchanged visible bucket refresh does not rewrite rows or bump revision;
- manual refresh cache-off no-op preserves Timeline runtime Mosaic state and Detail Mosaic artifacts;
- manual refresh cache-off does not run sync-time or runtime Mosaic work;
- render-demand Mosaic still computes when Mosaic is enabled and cache results are off.
