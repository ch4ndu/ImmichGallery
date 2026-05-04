# Data, Cache, API, And Time Rules

Load this for Room, DAOs, repositories, cache invalidation, Immich API work, settings, or timestamp/date work.

## Room Cache

- Room uses `BundledSQLiteDriver` and KSP code generation.
- Current Room data is a re-fetchable cache. `fallbackToDestructiveMigration(true)` is acceptable while that remains true.
- If the app starts storing durable user-authored data, add explicit migrations and remove destructive fallback for those tables.
- DAOs expose `Flow` for observed reads and `suspend` functions for writes and imperative reads.
- Cache replacement should be transactional where multiple tables or relationship rows must stay in sync.
- Stale relationship data should be replaced atomically: delete or replace refs for the scoped owner, then insert current refs. Timeline bucket metadata is the exception: count-changed buckets keep old refs until that bucket's asset refresh succeeds so cached launch rows do not collapse during background work; removed buckets still clear refs immediately.

## Repository Rules

- Repositories wrap `ImmichApiService`, Room DAOs, and settings.
- ViewModels do not inject repositories directly.
- Network fetches that update cached data write through Room before exposing refreshed state where the feature depends on cached reads.
- DB and network writes use `Dispatchers.IO`.
- Flow transforms that map entity lists to domain projections use `.flowOn(Dispatchers.Default)` where appropriate.
- Reconstruct URLs from current server config and asset IDs where possible so cached rows do not retain stale server URLs.
- Include `edited=true` on asset image URLs when non-destructive edit support requires edited variants.

## Immich API

- Auth uses the configured API key as the `x-api-key` header.
- Hidden assets should be filtered out before writing or presenting timeline bucket assets.
- Album detail currently returns all assets at once; do not add pagination loops unless the endpoint behavior changes.
- Person assets and search endpoints may paginate. Follow the endpoint's `nextPage` response and guard duplicate loads with `isLoadingMore`.
- Person detail page refreshes should replace the fetched ref window and truncate stale later refs when page membership/order changes. Preserving later cached refs after a changed page can mix snapshots because subsequent pages may have shifted.
- Timeline buckets load lazily. Cached launch syncs Timeline bucket metadata only; cached Room refs only mark asset availability. The ViewModel should materialize cached asset rows into memory for visible/nearby buckets, keep cached rows visible during background refresh, and avoid render-facing loading/error mutations for unchanged cached refreshes. Visible/nearby bucket asset refresh is serial and cache-preserving, while top-bar refresh performs the explicit full Timeline asset refresh.

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
