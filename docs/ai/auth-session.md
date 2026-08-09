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
6. Validation currently calls `/api/server/ping`, then authenticated
   `/api/albums` as the API-key check.
7. Only a successful validation runs `SaveServerConfigAction` and navigates to
   Main. A failed validation leaves the entered values available for retry.

The API key is transported in the `x-api-key` header for normal Ktor requests.
The normalized server URL and key are stored by `multiplatform-settings`; they
are configuration, not Room rows.

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

`ClearServerConfigAction` is intentionally cache-first and configuration-last:

1. Clear Timeline, Album, People, shared asset/sync metadata, Detail Mosaic, and
   Timeline Mosaic Room data.
2. Clear `ServerConfigRepository`, including credentials and view preferences.
3. Emit logout completion and navigate to Login.

If any cache clear fails, the action does not clear the active configuration.
Main stays visible and shows a retryable logout message, preventing cached data
from being exposed under a different server identity.

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
- login saves configuration only after validation succeeds;
- server monitoring starts/stops with Main and lifecycle activity;
- logout clears server-scoped caches before configuration and stays on Main if
  cleanup fails.
