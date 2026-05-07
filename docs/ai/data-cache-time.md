# Data, Cache, API, And Time Rules

Load this for Room, DAOs, repositories, cache invalidation, Immich API work, settings, or timestamp/date work.

For Mosaic cache artifact shape, readiness, runtime-vs-cache behavior, and invalidation rules, load `docs/ai/mosaic-rendering.md`.
For Timeline cold sync, warm launch, manual refresh, and no-op bucket refresh details, load `docs/ai/timeline-sync.md`.

## Room Cache

- Room uses `BundledSQLiteDriver` and KSP code generation.
- Current Room data is a re-fetchable cache. `fallbackToDestructiveMigration(true)` is acceptable while that remains true.
- If the app starts storing durable user-authored data, add explicit migrations and remove destructive fallback for those tables.
- DAOs expose `Flow` for observed reads and `suspend` functions for writes and imperative reads.
- Room builders set `setQueryCoroutineContext(Dispatchers.IO)` on every platform, and repositories still wrap imperative DAO reads/writes in `withContext(Dispatchers.IO)` at the call site. Do not rely on caller dispatcher for counts, sync metadata, cached asset reads, or cache clears.
- Cache replacement should be transactional where multiple tables or relationship rows must stay in sync.
- Stale relationship data should be replaced atomically: delete or replace refs for the scoped owner, then insert current refs. Timeline bucket metadata is the exception: count-changed buckets keep old refs until that bucket's asset refresh succeeds so cached launch rows do not collapse during background work; removed buckets still clear refs immediately.
- Timeline Mosaic cache rows are split by purpose: assignments are width-independent, while section geometry, aggregate bucket geometry, and display-band cache rows are width/key/version dependent. `TimelineMosaicCacheRepository` persists, reads, and clears these rows; assignment/progress/fallback/geometry math goes through `MosaicRenderEngine`. `TimelineRepository` must stay bucket/asset-sync focused. `ViewConfig.cacheMosaicResults = false` disables sync-time Timeline Mosaic artifact preparation and disk reads for rendering; materialized Timeline buckets compute runtime Mosaic state for the requested config only from render-demand work after active scrolling settles instead of fetching old rows from disk.
- Album and Person detail Mosaic cache uses owner-scoped assignment, display-band, section-geometry, and aggregate-geometry artifacts. Cache keys must include owner identity, group/section identity when section-scoped, Mosaic column count for every artifact type, normalized Mosaic families, ordered asset fingerprint, rounded geometry keys, and artifact version. Album Detail may cache whenever the owner snapshot is complete; Person Detail must only persist/read owner Mosaic cache after the full asset set is known (`hasMore == false`).
- Mosaic cache lookup tables should have composite indices matching their config lookup paths, not only owner or bucket indices. Cached Mosaic config apply treats matching persisted rows as readiness; it should skip already prepared rows and compute only missing rows. Timeline manual sync with `ViewConfig.cacheMosaicResults = false` clears all Timeline and Detail Mosaic artifacts before refreshing buckets, then leaves runtime Mosaic rebuilds to visible/target render demand.
- Mosaic cache readiness requires matching assignments, section geometry, and aggregate geometry. Display-band rows are additionally required only when `ViewConfig.cacheMosaicResults = true`, and display-band rows must independently cover the current ordered assets with no gaps, overlaps, duplicate ids, unknown ids, invalid tile dimensions, or mismatched source slices. Timeline and detail runtime rendering must not read or reuse disk Mosaic artifacts when cache results are disabled; they should compute Mosaic bands for the requested config even if matching rows exist. Timeline active-scroll persisted cache reads are visible/target scoped and must not replace display bands until scroll settles. Detail runtime partial chunks, retryable fallbacks, and assignment checkpoints are memory-only state and are not cache readiness. Repositories must not compute Mosaic assignments, fallback bands, display projection, or geometry directly; they only persist, read, and clear artifacts produced through the domain engine.
- App database schema changes may rely on destructive reset while the project remains a pre-alpha cache-only client. Keep `AppDatabase` at version 1 and export only the current version-1 schema; do not preserve historical schema JSON files until the app stores durable user-authored data. Old Mosaic cache rows must not be reused after artifact shape changes; a full cold resync is acceptable.

## Repository Rules

- Repositories wrap `ImmichApiService`, Room DAOs, and settings.
- ViewModels do not inject repositories directly.
- Network fetches that update cached data write through Room before exposing refreshed state where the feature depends on cached reads.
- DB and network writes use `Dispatchers.IO`.
- Imperative DB reads also use `Dispatchers.IO`; only pure entity-to-domain mapping may move to `Dispatchers.Default`.
- Flow transforms that map entity lists to domain projections use `.flowOn(Dispatchers.Default)` where appropriate.
- Reconstruct URLs from current server config and asset IDs where possible so cached rows do not retain stale server URLs.
- Include `edited=true` on asset image URLs when non-destructive edit support requires edited variants.
- Mosaic cache writes should use ordered asset fingerprints for invalidation, not timestamps or counts alone. A no-op sync must keep existing Mosaic rows, geometry, display cache, and asset revisions intact; changed or removed scoped content must clear the matching Mosaic cache rows with the relationship/content update. Timeline bucket-detail sync may skip asset/ref writes entirely when the fetched ordered visible fields match Room and all edited assets with dimensions have already resolved edit enrichment.
- When `ViewConfig.cacheMosaicResults` is enabled, Mosaic settings apply is a blocking cache-preparation operation. Persist the new `ViewConfig` only after the applicable Timeline/detail cache rows are ready; failed preparation must keep the previous config. Timeline blocking prepare normally fetches/materializes current metadata buckets through asset sync, retries failures once, then runs explicit Mosaic cache preparation before reporting readiness. Under cache-only warm policy, Timeline blocking prepare uses cached bucket rows only and must not perform non-manual asset sync. Timeline Mosaic read use cases must not backfill or upsert rows; only Actions write cache rows. Standalone persisted-ref precompute must reject empty Room asset reads when bucket metadata still reports assets, so unsynced buckets cannot persist zero-height geometry.

## Immich API

- Auth uses the configured API key as the `x-api-key` header.
- Hidden assets should be filtered out before writing or presenting timeline bucket assets.
- Album detail currently returns all assets at once; do not add pagination loops unless the endpoint behavior changes.
- Person assets and search endpoints may paginate. Follow the endpoint's `nextPage` response and guard duplicate loads with `isLoadingMore`.
- Person detail page refreshes should replace the fetched ref window and truncate stale later refs when page membership/order changes. Preserving later cached refs after a changed page can mix snapshots because subsequent pages may have shifted.
- Timeline buckets load lazily. Cached Room refs only mark asset availability. The ViewModel should materialize cached asset rows into memory for visible/nearby buckets and avoid render-facing loading/error mutations for unchanged cached refreshes. Under the cache-only warm policy, completed-cold-cache launch and visible/target bucket changes do not hit the server; top-bar manual refresh is the explicit full Timeline asset refresh. If warm server refresh is enabled, cached launch syncs metadata only and visible/nearby bucket asset refresh is serial and cache-preserving. Unchanged already-loaded refreshes should not republish bucket state or enqueue Mosaic reads.

## Settings And Credentials

- Server URL and API key live in `ServerConfigRepository` backed by `multiplatform-settings`.
- Lightweight user view preferences, including per-screen row height and Mosaic `ViewConfig`, also live in `ServerConfigRepository` backed by `multiplatform-settings`.
- Treat view preferences as settings, not Room cache data.
- Clear-login flows must clear credentials and cache data that would otherwise leak data from the previous server.
- Normalize server URLs consistently, usually by trimming the trailing slash before composing endpoint or image URLs.

## Date/Time

- Use `kotlinx-datetime` or `kotlin.time.Clock.System.now()` for time.
- Avoid raw day-millis literals such as `86400000L`; use date/time utilities or named constants.
- Keep timeline bucket parsing and labels in one place when adding date grouping behavior.
