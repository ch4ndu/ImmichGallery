# Authentication, Startup, And Session Architecture

Load this when changing login, saved-session startup, local-network access,
server-status monitoring, logout, or server-scoped cleanup.

## Ownership And Navigation

- `AppNavigation` renders a blocking startup indicator until
  `AppStartupViewModel` resolves `Loading`, `Login`, or `Main`.
- A saved session exists when both the server URL and API key are non-blank in
  `ServerConfigRepository`.
- `LoginViewModel` owns editable credentials, validation state, Android
  local-network recovery, and the one-shot login-success state.
- `MainScreenViewModel` owns server-status monitoring, shared Timeline display
  preferences, logout state, and the logout-completed navigation event.
- `SessionStateRepository` owns the application-wide session generation,
  long-lived owner epoch, operation admission, commit validation, and exclusive
  mutation boundary.
- `CoilImageCacheController` owns the installed singleton image loader and
  rotates it when server-scoped state is cleared.
- ViewModels call auth UseCases and Actions. They do not access repositories or
  platform permission APIs directly.

```text
AppNavigation
  -> AppStartupViewModel
      -> GetLoginStatusUseCase + GetServerUrlUseCase
      -> EnsureLocalNetworkAccessAction
  -> LoginScreen / MainScreen
```

## Login Flow

1. `LoginViewModel` starts with saved credentials when present, otherwise with
   optional generated local developer defaults.
2. Empty server URL or API key is rejected locally.
3. The server URL is normalized by trimming trailing `/` characters.
4. Android resolves any required local-network access before contacting a local
   endpoint. iOS and JVM use the no-op controller.
5. `ValidateServerUseCase` reaches `AuthRepository`, which validates the server
   ping and API key through `ImmichApiService.validateConnection(...)`.
6. Validation calls anonymous `/api/server/ping`, then attaches only the entered
   candidate key to `/api/albums` on that same candidate origin.
7. Only a successful validation runs `ReplaceServerSessionAction` and navigates
   to Main. An exact normalized URL-and-key match is a no-clear fast path. Any
   change to either value is a session replacement and clears the previous
   server-scoped state before saving the new pair. A failed validation leaves
   the entered values available for retry.

Normal API requests capture the configured URL and API key as one session
snapshot and attach `x-api-key` explicitly. The shared Ktor client and every
authenticated image client refuse automatic redirects, so a server response
cannot forward the custom credential header to another authority. Image clients
also attach the key only when the final request URL exactly matches the
configured scheme, host, and port.

The normalized server URL and key are stored by `multiplatform-settings`; they
are configuration, not Room rows. JVM Desktop uses the app-specific Java
Preferences node `com/udnahc/immichgallery`. A one-time migration copies known
legacy root-node keys, durably flushes the destination, and only then removes
those known legacy keys; logout never clears unrelated root preferences.

## Saved-Session Startup

- Without complete saved credentials, startup goes directly to Login.
- With saved credentials, startup resolves local-network access before Main is
  composed. This avoids briefly rendering authenticated screens and redirecting
  afterward.
- If saved local access is denied or the Android permission launcher is not
  available, startup returns to Login with the saved credentials still editable
  and shows the local-network guidance.
- Public endpoints, iOS, JVM, and Android versions below API 37 do not require
  the Android local-network permission gate.

## Android Local-Network Gate

- Android API 37+ requests `ACCESS_LOCAL_NETWORK` only for endpoints classified
  as local.
- Loopback, RFC1918/private, link-local, CGNAT, local IPv6, single-label hosts,
  and `.local` hosts classify locally without DNS.
- Other hostnames may be resolved on IO for up to 1.5 seconds. A resolution
  timeout or failure is `Unknown`, which follows ordinary connection handling
  instead of prompting.
- `AndroidLocalNetworkAccessController` binds its Activity Result launcher to
  each `MainActivity`; it is reached through `EnsureLocalNetworkAccessAction`.
- A denied login exposes the app-settings recovery action. The permission result
  never clears the credentials the user typed.
- iOS declares `NSLocalNetworkUsageDescription` and
  `NSAllowsLocalNetworking`; it does not enable arbitrary remote HTTP.

## Server Status And Lifecycle

- `MainScreen` starts monitoring only while the app lifecycle is active and the
  Main composition exists; disposal or background/unfocus stops it.
- `ServerStatusRepository` performs `/api/server/ping` every 30 seconds on IO.
- `startMonitoring(...)` is idempotent, and `stopMonitoring()` cancels and
  clears the owned job.
- The top-bar indicator reflects only this reachability ping. Individual data
  requests still own their own error and retry states.

## Logout And Cleanup

`ClearServerConfigAction` and changed-identity login use the same exclusive
session mutation boundary. The boundary is intentionally cache-first and
configuration-last:

1. Close operation admission, advance the session generation, cancel and join
   registered work from the previous generation, and reject any queued work
   stamped by that generation and owner epoch. Joining old jobs is
   non-cancellable: cancellation of the logout/replacement caller cannot reopen
   admission while an old writer still runs. After the join, caller
   cancellation is checked before any cache or configuration mutation begins.
2. Shut down the installed Coil loader and its disk cache, delete the closed
   app-owned image-cache directory, and prepare—but do not install—a fresh
   loader. The process-global Coil slot remains pointed at the shut-down loader
   while Timeline, Album, People, shared asset/sync metadata, Detail Mosaic,
   Timeline Mosaic, and API/runtime server-scoped state are cleared.
3. Clear `ServerConfigRepository`, including credentials and view preferences,
   or save the validated replacement URL/key, then install the prepared fresh
   loader. If the wrapped clear/configuration block fails, the configuration is
   unchanged and the prepared loader is still installed so the session remains
   usable and retryable.
4. For successful logout, emit completion and navigate to Login.

Cache-producing logical work registers at its real start and carries one
immutable stamp, made from the current generation and its long-lived screen or
coordinator owner epoch, across every deferred child. Each cache commit
validates both values. A descendant must never recapture either value after a
suspension. Timeline/detail owners retain their owner epoch for their lifetime;
Timeline queue, repair, and Android manual-service commands carry the originating
stamp explicitly.

Every mutation advances the generation. The published generation changes only
when admission reopens, so synchronous callbacks during cleanup keep an old
stamp and are rejected. A successful mutation also advances the owner epoch,
which rejects callbacks from an old still-mounted screen during the brief gap
before navigation disposes it. A failed mutation keeps the owner epoch, allowing
that still-mounted screen to retry with the new generation. Admission rejection
and stale-operation rejection are cancellation control flow, not user-visible
sync failures. Together these rules prevent an old response, delayed Mosaic
computation, queued foreground-service command, or post-cleanup callback from
repopulating cache.

If any cache clear fails, the action does not clear the active configuration.
Main stays visible and shows a retryable logout message, preventing cached data
from being exposed under a different server identity.

Application logging is disabled on Android, iOS, and JVM for every build. HTTP
logging is also disabled, and retained log call sites use stable operation
labels rather than URLs, identifiers, dates, response bodies, or raw throwable
messages.

## Developer Login Defaults

`composeApp/build.gradle.kts` generates `LocalLoginDefaults` with blank
fallbacks. Local development can populate them with the gitignored
`local.properties` keys:

```properties
immichGallery.loginServerUrl=https://immich.example.com
immichGallery.loginApiKey=your-api-key
```

The same Gradle properties or `IMMICH_GALLERY_LOGIN_SERVER_URL` and
`IMMICH_GALLERY_LOGIN_API_KEY` environment variables are fallback inputs. These
defaults prefill Login only; they do not bypass validation.

## Verification Targets

- no saved credentials routes to Login;
- a saved public endpoint routes to Main without a permission request;
- a saved denied local endpoint returns to Login without clearing credentials;
- candidate ping is anonymous and validation sends only the candidate key to
  the exact candidate origin;
- login saves configuration only after validation succeeds, preserves cache
  for an exact normalized URL/key match, and clears old state for any change;
- server monitoring starts/stops with Main and lifecycle activity;
- session mutation cancels and joins registered work, refuses old-generation
  commits/deferred work, and admits new-generation work after a failed clear;
- logout rotates and clears Coil plus server-scoped Room/runtime caches before
  configuration and stays on Main if cleanup fails.
