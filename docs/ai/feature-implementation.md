# Feature Implementation

Load this for new features, bug fixes, UI work, and significant refactors.

## Before Editing

- Read `AGENTS.md`, then load only the task-relevant focused docs.
- Explore existing code for reusable UseCases, Actions, repositories, composables, utilities, strings, icons, and theme dimensions.
- Identify whether the change belongs in `commonMain` or requires platform-specific code.
- Keep the change minimal and aligned with existing architecture.

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
- Register UseCases and Actions in `di/AppModule.kt`.
- Use a screen-specific ViewModel for screen state.
- Register ViewModels in `di/AppModule.kt` with `viewModel { ... }`.

## UI

- Add screens under `ui/screen/{feature}/`.
- Add shared components under `ui/component/` only when the pattern is reused or clearly cross-screen.
- Obtain ViewModels with `koinViewModel()` at the screen call site.
- Wire new tabs or destinations in `ui/navigation/`.
- Follow `docs/ai/ui.md` for theme, strings, dimensions, previews, overlay padding, and recomposition rules.
- If a feature shows a photo grid, use `packIntoRows()`, `JustifiedPhotoRow`, `BoxWithConstraints`, and `pinchToZoomRowHeight`.
- If a feature opens photo detail, use the existing overlay/detail patterns before adding a new pager.
- If an Immich API endpoint paginates, implement `loadMore()` with a duplicate-request guard and near-end detection through `snapshotFlow` or `derivedStateOf`.

## Platform

- Prefer `commonMain`.
- Use `expect`/`actual` only for platform services such as database builders, HTTP engines, system gesture exclusion, haptics, wake locks, edge-to-edge, or platform media playback.
- Android context-dependent setup belongs in Android entry points and platform modules.

## Verification

- Run `./gradlew :composeApp:compileKotlinJvm` for normal code changes.
- For platform-sensitive changes, also run the relevant Android or iOS compile task.
- For broad shared changes, prefer `./gradlew :composeApp:compileKotlinJvm :composeApp:compileKotlinIosSimulatorArm64`.
- For UI-visible changes, manually run desktop with `./gradlew :composeApp:run` or verify on device/simulator when practical.
- Search changed Kotlin code for `!!`, hardcoded user-visible strings, hardcoded non-trivial `dp` values, and data transformation in composables.
- After running Gradle, run `./gradlew --stop`.
