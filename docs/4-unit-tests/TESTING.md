# Testing Guidelines

## Test Framework

ImmichGallery uses `kotlin.test` with JUnit 4.13.2 and
`kotlinx-coroutines-test`. The Android Room migration test uses AndroidX Test,
the Android test runner, Room testing support, and the bundled SQLite driver.

No code-coverage plugin or numeric coverage threshold is currently configured.
Do not report a coverage percentage unless coverage tooling is added and the
report was actually generated.

## Running Tests

```bash
# Shared commonTest suite on the JVM target
./gradlew :composeApp:jvmTest

# Shared tests through the Android host-test target
./gradlew :composeApp:testAndroidHostTest

# A specific shared/JVM test
./gradlew :composeApp:jvmTest --tests 'fully.qualified.TestClass.testName'

# Shared tests compiled and executed for the ARM64 iOS simulator target
./gradlew :composeApp:iosSimulatorArm64Test

# Device-only Room migration verification; requires a connected Android target
./gradlew :composeApp:connectedAndroidDeviceTest
```

Use the smallest suite that owns the changed behavior during implementation.
Run the broader compile/test matrix from `docs/ARCHI.md` when a change crosses
source sets, platform hosts, persistence, API contracts, or packaging.

After command-line Gradle work, stop daemons with:

```bash
./gradlew --stop
```

## Test Organization

- `composeApp/src/commonTest` contains portable API-contract, repository,
  domain, Action, ViewModel, layout, pagination, sync, Mosaic, and UI-helper
  tests.
- `composeApp/src/androidDeviceTest` contains the instrumented Room migration
  test.
- Shared `commonTest` sources are consumed by their platform test tasks.
  `jvmTest` also includes Desktop Compose interaction tests using
  `org.jetbrains.compose.ui:ui-test-junit4`. Timeline date navigation exercises calendar
  cancellation/confirmation, delayed layout publication, and superseded jumps
  without a live Immich server.
- Test files use the `*Test.kt` suffix and test functions use `@Test`.

## Writing Tests

- Put portable behavior in `commonTest`.
- Test observable inputs, outputs, state, persisted effects, and request shape;
  do not test internal wiring merely to increase counts.
- Preserve cancellation semantics in coroutine and `Result` tests.
- Use deterministic coroutine dispatchers and clocks where timing or ordering
  matters.
- Cover valid, invalid, empty, boundary, stale-response, cancellation, retry,
  pagination, cache-hit, cache-miss, and partial-failure paths relevant to the
  change.
- Add API serialization/column-shape coverage when Immich wire models or
  endpoints change.
- Add migration coverage when the Room version or schema changes. Keep exported
  schemas under `composeApp/schemas/` synchronized.
- Prefer existing fake boundaries and pure helpers. If meaningful coverage
  requires a refactor larger than the feature, record the gap in
  `docs/4-unit-tests/COVERAGE-DEBT.md` using `path | why hard | escape plan`.
- Never claim Android service/permission, iOS AVPlayer/local-network, Desktop
  native mpv/input, or packaged-runtime behavior from a common host test alone.

## Verification By Change Type

- **Shared data/domain/UI**: focused `jvmTest`, relevant compile tasks, and
  Android host tests when Android resources or actuals participate.
- **Room schema/migration**: exported-schema review plus
  `connectedAndroidDeviceTest` when device execution is authorized.
- **Immich API contract**: serialization/request tests plus an explicitly
  authorized real-server acceptance check when wire behavior cannot be proven
  locally.
- **Android host/service/permission**: `:androidApp:assembleDebug`,
  `:androidApp:lintDebug`, and an explicitly authorized device check.
- **iOS host/media/network**: iOS compilation/Xcode build and an explicitly
  authorized simulator or device check.
- **Desktop media/input/package**: JVM tests/compilation plus running the app or
  packaged artifact when explicitly requested.
- **UI-visible behavior**: trace the exact user actions and state wiring; build
  success alone is not acceptance.

The repository default is not to run a simulator, emulator, or physical-device
flow unless the current request explicitly asks for it. Report relevant
unperformed checks instead of treating host verification as equivalent.

## Coverage Requirements

No numeric threshold is configured. Require meaningful behavioral coverage for
new logic and a documented coverage-debt entry for newly introduced risky code
that cannot be covered proportionately.
