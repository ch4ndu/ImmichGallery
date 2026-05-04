# Feature Implementation

Load this for new features, bug fixes, UI work, and significant refactors.

## Before Editing

- Read `AGENTS.md`, then load only the task-relevant focused docs.
- Explore existing code for reusable UseCases, Actions, repositories, composables, utilities, strings, icons, and theme dimensions.
- Identify whether the change belongs in `commonMain` or requires platform-specific code.
- Keep the change minimal and aligned with existing architecture.
- When a code change updates durable architecture, workflow, layout, or verification behavior, update the relevant `docs/ai/*` file in the same change.

## Data Layer

For a new persisted cache entity or schema change:

- Add API response model in `data/model/` if the Immich API response is new.
- Add Room entity in `data/local/entity/` and DAO methods in `data/local/dao/`.
- DAO reads should be `Flow` when UI observes them and `suspend` when used as imperative cache lookups.
- Add or update repository behavior in `data/repository/`; repositories wrap `ImmichApiService`, Room DAOs, and settings.
- Add mapper functions in `domain/model/Mappers.kt` or the relevant domain model file for API to entity, entity to domain, and API to domain conversion.
- Update `AppDatabase.kt` entities, DAO accessors, and version.
- Because this database is cache-only, platform builders may keep `fallbackToDestructiveMigration(true)` unless the data becomes durable user data.
- Register repositories in `di/AppModule.kt`.

## Domain And ViewModels

- Add or reuse one UseCase per read in `domain/usecase/{entity}/`.
- Add or reuse one Action per write or side effect in `domain/action/{entity}/`.
- Put derived and filtered flows in UseCases where possible.
- Put screen-specific projections, row packing, pagination state, and overlay source lists in the screen ViewModel.
- ViewModels should expose typed UI message values for user-visible errors and banners; composables resolve them with `stringResource()`.
- Persisted view preferences such as row height and Mosaic settings should flow through settings UseCases/Actions and `ServerConfigRepository`, not direct repository access from ViewModels.
- Mosaic is enabled by default for new installs so Timeline first sync can precompute persisted Mosaic assignments. Existing saved user preferences must still win over the default.
- For direct photo-grid screens, use `PhotoGridLayoutRunner` for expensive zoom-driven row or detail Mosaic projection work. Keep the runner as coroutine orchestration only; do not move screen projection ownership out of the ViewModel.
- Route CPU-heavy Album Detail and Person Detail Mosaic assignment work through `MosaicWorkScheduler`; foreground visible detail groups must not wait behind offscreen prefetch work. Timeline Mosaic is different: compute changed buckets after server sync, persist assignments in Room, and have `TimelineViewModel` read the cached assignments instead of continuously calculating them.
- For Timeline sync/layout changes, keep asset revisions per bucket. Do not use a successful full sync as a global row-packing or Mosaic invalidation signal. Cached Timeline launch should sync bucket metadata only, remember which buckets have cached Room refs, and materialize asset rows only for visible/nearby buckets; the top-bar refresh is the explicit full asset refresh path and should publish changed buckets progressively. Cached background refreshes should not mutate bucket loading/error display state unless content changes or the bucket has no cached rows. When Timeline Mosaic is enabled, use the fixed Timeline column policy and recompute/persist Mosaic only for buckets whose ordered asset fingerprint changed. Width and config changes should read existing persisted Mosaic rows only for materialized visible/nearby buckets; if the requested config misses cache, keep the previous active Mosaic layout when available, otherwise show placeholders. Timeline Mosaic precompute runs on `Dispatchers.IO` with a four-bucket concurrency limit.
- Timeline UI hot paths should use the ViewModel's precomputed display index for visible bucket detection, scrollbar fraction mapping, and scroll targeting. Keep long-lived `LaunchedEffect`s stable with `rememberUpdatedState` for changing display lookup data, and avoid storing transient scroll jobs in Compose state. Timeline detail overlay should collect projected bucket/page state rather than full `TimelineState`, while continuing to read shared `bucketAssetsCache` for pager assets and shared-element transitions.
- Detail screens that read Room assets, such as Album Detail and Person Detail, should render cached assets immediately and sync the opened detail in the background. Treat sync success and ordered content change as separate signals; album-name-only updates and cache bookkeeping changes must not repack rows or rebuild Mosaic assignments.
- Album and Person detail visible-group changes should reprioritize pending `MosaicWorkScheduler` work without bumping layout generation or republishing display items. Use a precomputed display index for visible-group effects instead of scanning `displayItems` in a `LaunchedEffect` keyed by the list.
- Person detail page refreshes must avoid mixed cached snapshots. When a fetched page changes membership/order, truncate refs from that page start and let `loadMore()` refill later pages; final pages must also clear stale tail refs. Ignore `loadMore()` while a person detail sync is active and surface the one-shot Material snackbar message.
- Do not preload Person detail assets from the People list. Person detail caching starts only when that person is opened; first open with no cached assets may perform the full opened-person sync.
- Add nearby code comments for cache invalidation, sync freshness, and layout reuse logic when the invariant is not obvious from the type names alone.
- Register UseCases and Actions in `di/AppModule.kt`.
- Use a screen-specific ViewModel for screen state.
- Register ViewModels in `di/AppModule.kt` with `viewModel { ... }`.

## UI

- Add screens under `ui/screen/{feature}/`.
- Add shared components under `ui/component/` only when the pattern is reused or clearly cross-screen.
- Obtain ViewModels with `koinViewModel()` at the screen call site.
- Wire new tabs or destinations in `ui/navigation/`.
- Follow `docs/ai/ui.md` for theme, strings, dimensions, previews, overlay padding, and recomposition rules.
- If a feature shows photo assets, use the ViewModel-owned photo-grid projection. Choose justified rows or the established Mosaic layout from persisted `ViewConfig`; do not transform assets into layout items inside composables.
- When touching Mosaic fallback behavior, keep the policy explicit in `MosaicPacking.kt`: fallback rows are justified full-span rows, wide-image promotion is disabled, single-photo complete rows are not allowed, and empty-assignment groups with 1-4 assets use the shared taller small-group fallback across Mosaic grid screens.
- Add Mosaic controls only where photo assets are displayed directly. Collection grids such as AlbumList and PeopleList should not expose photo-layout controls.
- If a feature opens photo detail, use the existing overlay/detail patterns before adding a new pager.
- If an Immich API endpoint paginates, implement `loadMore()` with a duplicate-request guard and near-end detection through `snapshotFlow` or `derivedStateOf`.

## Platform

- Prefer `commonMain`.
- Use `expect`/`actual` only for platform services such as database builders, HTTP engines, system gesture exclusion, haptics, wake locks, edge-to-edge, or platform media playback.
- Android context-dependent setup belongs in Android entry points and platform modules.
- Local developer login defaults come from gitignored root `local.properties` keys `immichGallery.loginServerUrl` and `immichGallery.loginApiKey`; Gradle generates blank-fallback common constants for builds where those keys are absent.

## Verification

- Run `./gradlew :composeApp:compileKotlinJvm` for normal code changes.
- For platform-sensitive changes, also run the relevant Android or iOS compile task.
- For broad shared changes, prefer `./gradlew :composeApp:compileKotlinJvm :composeApp:compileKotlinIosSimulatorArm64`.
- For UI-visible changes, manually run desktop with `./gradlew :composeApp:run` or verify on device/simulator when practical.
- Search changed Kotlin code for `!!`, hardcoded user-visible strings, hardcoded non-trivial `dp` values, and data transformation in composables.
- After running Gradle, run `./gradlew --stop`.
