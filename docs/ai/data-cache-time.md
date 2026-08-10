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
- Timeline Mosaic cache rows are split by purpose: assignments are width-independent, while section geometry, aggregate bucket geometry, and mixed display-record cache rows are width/key/version dependent. `PrepareTimelineMosaicCacheAction` and `TimelineMosaicArtifactBuilder` derive Timeline Mosaic artifacts through `MosaicRenderEngine`; `TimelineMosaicCacheRepository` persists, reads, clears, and validates these rows only. `TimelineRepository` stays bucket/asset-sync focused but may expose a bucket snapshot read of ordered assets plus metadata count for precompute. `ViewConfig.cacheMosaicResults = false` disables sync-time Timeline Mosaic artifact preparation and disk reads for rendering; materialized Timeline buckets compute runtime Mosaic state for the requested config only from render-demand work after active scrolling settles instead of fetching old rows from disk.
- Album and Person detail Mosaic cache uses owner-scoped assignment, mixed display-record, section-geometry, and aggregate-geometry artifacts. Cache keys must include owner identity, group/section identity when section-scoped, Mosaic column count for every artifact type, normalized Mosaic families, ordered asset fingerprint, rounded geometry keys, and artifact version. Both detail screens must only persist/read owner Mosaic cache after the full asset set is known (`hasMore == false`).
- Album and Person detail asset sync resolves edited-asset dimensions before its Room writer transaction. That transaction writes final asset rows and owner refs, detects the resulting ordered owner content, and clears all four artifact families for that owner only when it changed. A no-op page must not rewrite rows or invalidate artifacts. Detail edit resolution may reuse an exact resolved aspect only while the API-visible asset fields used by the cache match; otherwise it must resolve `/edits` again before writing. Because Room has no edit-revision or edit-dimensions fingerprint, an edit changed remotely without any of those visible fields remains cached until a later visible asset change or a cache reset.
- Detail invalidation is deliberately current-owner scoped: an Album or Person sync does not immediately invalidate Timeline or another owner that happens to reference the same `AssetEntity`. Those scopes refresh on their own next sync or recreation. Removing an Album or Person clears that owner's refs, detail artifacts, and sync metadata together.
- Mosaic cache lookup tables should have composite indices matching their config lookup paths, not only owner or bucket indices. Cached Mosaic config apply treats matching persisted rows as readiness; it should skip already prepared rows and compute only missing rows. Timeline manual sync with `ViewConfig.cacheMosaicResults = false` must not clear Timeline or Detail Mosaic artifacts before it knows content changed; no-op refreshes preserve runtime Mosaic state and Detail artifacts, while changed Timeline buckets use normal per-bucket invalidation and later runtime Mosaic rebuilds come only from visible/target render demand.
- Mosaic cache readiness requires source-slice-valid assignments, contiguous positive section geometry ranges, and fingerprint/current-section-valid aggregate geometry. Assignment gaps are legal completed fallback ranges; overlap, wrong-slice ids, duplicate ids, bad visual order, unknown templates, malformed JSON, and height mismatches are cache misses. Mixed display rows are optional for renderability and must independently cover current ordered assets. Timeline geometry hydration reads ordered assets once with aggregate and section geometry; assignments/display rows remain visible-demand reads. Repositories only persist, read, clear, and validate artifacts produced through the domain engine/builder.
- Runtime exact geometry may be kept in memory for render safety even when `ViewConfig.cacheMosaicResults = false`; this is not a disk-cache read or readiness signal. Disk cache remains disabled in cache-off mode, but render paths still require in-memory exact geometry before publishing partial or ready Mosaic replacements.
- `AppDatabase` is version 2. Version 2 did not change the exported Room schema, so every platform builder registers an explicit no-op `MIGRATION_1_2` that preserves the re-fetchable cache. Keep the version-1 schema JSON and the Android migration test so this path remains validated. Destructive fallback remains acceptable only for unsupported version gaps while Room contains no durable user-authored data.

## Repository Rules

- Repositories wrap `ImmichApiService`, Room DAOs, and settings.
- ViewModels do not inject repositories directly.
- Network fetches that update cached data write through Room before exposing refreshed state where the feature depends on cached reads.
- DB and network writes use `Dispatchers.IO`.
- Imperative DB reads also use `Dispatchers.IO`; only pure entity-to-domain mapping may move to `Dispatchers.Default`.
- Flow transforms that map entity lists to domain projections use `.flowOn(Dispatchers.Default)` where appropriate.
- Reconstruct URLs from current server config and asset IDs where possible so cached rows do not retain stale server URLs.
- Explore Search results, server feature/preference reads, Tags, and typed
  suggestions are memory-only for the Search destination lifetime. Successful
  suggestions may be cached in that ViewModel by normalized dependency tuple;
  none of these values are written to Room or settings.
- Search People and Album selectors observe and synchronize the existing Room
  list caches through their domain UseCases. Do not create Search-owned entity
  tables, duplicate repositories, or separate invalidation/logout rules.
- Include `edited=true` on asset image URLs when non-destructive edit support requires edited variants.
- Mosaic cache writes should use ordered asset fingerprints for invalidation, not timestamps or counts alone. A no-op sync must keep existing Mosaic rows, geometry, display cache, and asset revisions intact; changed or removed scoped content must clear the matching Mosaic cache rows with the relationship/content update. Timeline bucket-detail sync may skip asset/ref writes entirely when the fetched ordered visible fields match Room and all edited assets with dimensions have already resolved edit enrichment.
- Detail repositories must decide no-op versus changed from the final rows (including resolved edit aspect), not the pre-enrichment API response. They must never run a later background edit-enrichment write after replacing refs, because that leaves owner content and Mosaic artifacts out of sync.
- When `ViewConfig.cacheMosaicResults` is enabled, Mosaic settings apply is a blocking cache-preparation operation. Persist the new `ViewConfig` only after the applicable Timeline/detail cache rows are ready; failed preparation must keep the previous config. Timeline blocking prepare normally fetches/materializes current metadata buckets through asset sync, retries failures once, then runs explicit Mosaic cache preparation before reporting readiness. Under cache-only warm policy, Timeline blocking prepare uses cached bucket rows only and must not perform non-manual asset sync. Timeline Mosaic read use cases must not backfill or upsert rows; only Actions write cache rows. Standalone persisted-ref precompute must reject empty Room asset reads when bucket metadata still reports assets, so unsynced buckets cannot persist zero-height geometry.

## Immich API

- Auth uses the configured API key as the `x-api-key` header.
- The shared Ktor client has explicit cross-platform timeouts: 15 seconds to connect and 60 seconds for request/socket activity. Endpoint-specific work may override these only when its transfer contract requires a longer bound.
- Hidden assets should be filtered out before writing or presenting timeline bucket assets.
- Immich v3 album detail returns metadata only. Fetch album assets through paged metadata search with `albumIds`, the album's server order, and the returned `nextPage` token.
- People list sync fetches every `/people` page into memory and replaces Room only after the complete paginated fetch succeeds.
- Album assets, Person assets, and search endpoints may paginate. Parse and follow the endpoint's returned `nextPage` value; never synthesize the next page by incrementing local state. A malformed non-null token is an API compatibility failure. Guard duplicate loads with `isLoadingMore`.
- Explore Search builds endpoint-specific JSON from normalized criteria rather
  than serializing one defaults-heavy request DTO. Omit inactive fields, encode
  explicit missing-value filters as JSON `null`, preserve selected Person,
  Album, and Tag id order, and select Smart versus Metadata before performing
  network I/O. Date filters use inclusive selected calendar boundaries labelled
  as `T00:00:00.000Z` and `T23:59:59.999Z`, matching Immich web's wall-clock
  date semantics.
- Search filter support reads public `/api/server/features` independently from
  authenticated `/api/users/me/preferences`. Tags use authenticated
  `/api/tags`; typed camera/location values use authenticated
  `/api/search/suggestions` with only the dependency query values supported by
  that suggestion type. Raw option values and dependencies must not be logged.
- `AssetResponseDto.stack` is nested and supplies the persisted/displayed stack count. Only `IMAGE` and `VIDEO` enter the gallery cache or domain model; filter `AUDIO` and `OTHER` responses at API-to-cache/domain boundaries.
- Complete stacks are live viewer data. Resolve the current stack id from
  `/api/assets/{id}` before reading `/api/stacks/{stackId}`, normalize supported
  members in memory, and do not add stack ids or member relationships to Room.
  Permission denial, unavailable endpoints, transient failures, authoritative
  no-stack responses, and stacks with no supported members remain distinct
  outcomes. Stack reads must preserve coroutine cancellation.
- Favorites is live-only. Resolve the authenticated owner through
  `/api/users/me`, request favorite metadata-search pages of 50 with
  `withStacked=true`, and filter each response by exact `ownerId` before mapping
  supported assets. Do not set `withStacked=false`: Immich uses that value to
  filter out every asset carrying a stack id, including the primary. Sparse partner
  or unsupported pages may be consumed within one logical load. Preserve the
  consumed-page set and first unconsumed retry page on failure; malformed,
  repeated, backward, or consumed next tokens terminate without replaying an
  earlier page.
- Memories is live-only. Send an authenticated `GET /api/memories` with the
  exact `for=YYYY-MM-DD` local-date query and `type=on_this_day`, keep only
  OnThisDay responses and supported image/video assets, and do not persist
  memory ids, relationships, or seen/saved state. Permission denial and
  unavailable endpoints remain distinct from transient request failure.
- Result-producing suspend boundaries must rethrow `CancellationException`; cancellation is control flow, not a connection or content failure.
- Album and Person detail page refreshes should replace the fetched ref window and truncate stale later refs when page membership/order changes. Preserving later cached refs after a changed page can mix snapshots because subsequent pages may have shifted.
- Removed Album and Person list owners must clear their owner refs, Detail Mosaic artifacts, and detail sync metadata in the same Room transaction that replaces the list.
- Timeline buckets load lazily. Cached Room refs only mark asset availability. The ViewModel should materialize cached asset rows into memory for visible/nearby buckets and avoid render-facing loading/error mutations for unchanged cached refreshes. Under the cache-only warm policy, completed-cold-cache launch and visible/target bucket changes do not hit the server; top-bar manual refresh is the explicit full Timeline asset refresh. If warm server refresh is enabled, cached launch syncs metadata only and visible/nearby bucket asset refresh is serial and cache-preserving. Unchanged already-loaded refreshes should not republish bucket state or enqueue Mosaic reads.

## Settings And Credentials

- Server URL and API key live in `ServerConfigRepository` backed by `multiplatform-settings`.
- On Android API 37+, local Immich endpoints require `ACCESS_LOCAL_NETWORK` before validation or cached-session startup. Classify literal loopback/private/link-local/CGNAT IPv4, local IPv6, single-label hosts, and `.local` as local immediately; resolve other hostnames on IO for at most 1.5 seconds and classify them as local when any address is local. Keep classifications in memory only. A timeout or resolution failure does not prompt and follows ordinary connection handling.
- A saved local endpoint must resolve permission before `MainScreen` is composed. If access is denied, return to Login with the saved credentials available for editing; new local logins retain entered credentials and offer retry plus app settings. Public endpoints and iOS/JVM bypass this Android-only gate.
- Lightweight user view preferences, including per-screen row height and Mosaic `ViewConfig`, also live in `ServerConfigRepository` backed by `multiplatform-settings`.
- Favorites uses its own `favorites_target_row_height` setting; it does not
  read or modify Explore Search density.
- Treat view preferences as settings, not Room cache data.
- Clear-login flows must clear credentials and cache data that would otherwise leak data from the previous server.
- Normalize server URLs consistently, usually by trimming the trailing slash before composing endpoint or image URLs.

## Date/Time

- Use `kotlinx-datetime` or `kotlin.time.Clock.System.now()` for time.
- Avoid raw day-millis literals such as `86400000L`; use date/time utilities or named constants.
- Keep timeline bucket parsing and labels in one place when adding date grouping behavior.
- Memories derives the request date at the ViewModel boundary from
  `Clock.System.now()` and `TimeZone.currentSystemDefault()`. The repository and
  API receive a `LocalDate`; explicit retry recomputes it after local midnight.
