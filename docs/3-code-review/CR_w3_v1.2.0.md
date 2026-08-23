# Code Review: Whole-Project Audit

**Review Date**: 2026-08-23
**Version**: 1.2.0 (build 3)
**Baseline**: `01ab2b7` (`master`, `origin/master`, tag `v1.2.0`)
**Review Type**: findings-only audit of the effective repository, active architecture contracts, platform hosts, build/release configuration, and current upstream compatibility
**Plan**: `docs/1-plans/F_1.2.0_advanced-search.plan.md` is the active version plan; this audit intentionally covers the whole project rather than only that feature range.

**Files Reviewed**:

- all 506 tracked files, including 367 files under `composeApp`, the Android and iOS hosts, Gradle/Xcode configuration, resources, scripts, and repository guidance;
- all 326 Kotlin files (45,365 lines), including `commonMain`, Android, iOS, JVM, shared host tests, and the Android migration test;
- all active architecture and feature contracts under `docs/ai`, `docs/ARCHI.md`, `docs/ARCHI-rules.md`, `docs/timeline.md`, release/history records, and project-local TRIP review instructions;
- the pinned Immich reference under `.local/immich` for consumed OpenAPI/server/web contracts, plus a focused comparison with current official Immich source;
- resolved direct runtime/build dependencies and platform integration seams. Generated build output was not treated as source authority, and secret values in gitignored local configuration were deliberately not inspected.

---

## Executive Summary

The project compiles on every configured target, Android lint and assembly pass,
and all 359 shared host tests pass on both JVM and Android. The architecture is
generally disciplined: dependency direction is preserved, platform seams are
complete, cancellation handling is usually correct, Room writes are commonly
transactional, and the hot photo-grid paths use stable lazy identity.

The audit nevertheless found **3 Critical Confirmed**, **18 Major Confirmed**,
**9 Major Risk**, **12 Minor Confirmed**, and **5 Minor Risk/Measurement** items.
The three deployment-blocking defects are all session-boundary failures: an old
saved API key is sent to an edited candidate host before trust is established;
changing server/account can retain the old session's Room data and relabel its
URLs under the new origin; and logout does not fence active writers, allowing an
old session to repopulate Room after cleanup. These defects compose with
session-unscoped image caches and materially increase cross-account disclosure
risk.

**Final verdict: NEEDS REVISION.** The green build verifies compilation and the
existing tests; it does not invalidate the source-proven security, cache,
pagination, concurrency, accessibility, media, and release-path findings below.

Evidence labels used throughout:

- **Confirmed** means the defective branch, violated contract, unsafe ordering,
  or missing behavior is present in the current source without requiring a
  speculative implementation assumption.
- **Risk** means the unsafe mechanism is present, but user-visible manifestation
  depends on a redirect, timing window, live data mutation, platform behavior,
  large workload, or distribution decision that was not exercised in this
  automated audit.

All findings are **open**. This audit did not modify production behavior.

---

## Changes Overview

This is a baseline audit, not a review of a new implementation range. It traced
the production paths for authentication and session replacement, Immich API
decoding and pagination, Room and Coil cache ownership, Timeline/Mosaic
scheduling, every top-level screen and viewer, Android/iOS/JVM actuals, Gradle
and Xcode builds, packaging, documentation contracts, tests, and repository
governance.

The review used four independent waves—data/security, UI/Compose, platform/build,
and root-level docs/tests/upstream adjudication—and reconciled duplicates before
classification. The project-local review checklist remains the severity and
approval authority: `.agents/skills/trip-review/checklist.md`.

## Contract Matrix

| Surface | Active contract/source checked | Result |
|---|---|---|
| Auth, startup, logout | `docs/ai/auth-session.md`, `docs/ARCHI.md`, Login/Startup/Main/Clear actions | C-01 through C-03, MR-02; cache-first ordering itself is compliant |
| Immich API and compatibility | pinned OpenAPI/server/web source, current official Immich source, DTOs/service/repositories | M-01 through M-05; current consumed upstream fields otherwise remain compatible |
| Room and Mosaic cache | `docs/ai/data-cache-time.md`, Timeline/detail repositories, DAOs and migrations | C-02, C-03, M-03 through M-05, MR-02, m-02 |
| Compose and photo grids | `docs/ai/ui.md`, Compose performance checklist, row/Mosaic/detail contracts | M-07 through M-13, MR-03 through MR-07, m-03 through m-06 |
| Viewer/media | `docs/ai/media-viewer.md`, shared overlay/page content, three platform players | M-10, M-11, M-15, M-18, MR-04 through MR-06, m-03, m-07 through m-10 |
| Android platform | manifests, Activity/Application, local-network gate, FGS, Android actuals | MR-08; baseline permissions/module ownership are compliant |
| iOS platform | Swift host, plist, Xcode project, framework helper, AVPlayer, iOS actuals | M-15 through M-17, m-09, m-10, mr-04 |
| JVM/Desktop | window lifecycle/input, Settings, media, Coil, packaging | M-14, M-18, MR-02, MR-09, m-07, m-08, m-11, mr-05 |
| Build, dependencies, release | Gradle/catalog/wrapper, Xcode config, runtime graph, advisory/license checks | M-16 through M-18, MR-09, m-11, m-12, S-01 through S-04 |
| Docs and repository hygiene | all tracked Markdown and local-link validation | no broken local links; several behavior/configuration discrepancies are included in findings |
| Tests | 51 `commonTest` files, one Android device migration test, task outputs | existing 718 host executions pass; critical session and concurrency paths lack direct regressions |

---

## Findings

### Critical Issues

#### C-01 — Persisted API key is disclosed to an arbitrary edited candidate host

**Severity/evidence**: Critical — Confirmed. **Disposition**: open.

`HttpClientFactory.kt:37-41` adds the currently persisted `x-api-key` to every
request made by the shared Ktor client. `ImmichApiService.kt:61-70` validates an
arbitrary candidate URL by first calling its unauthenticated-looking
`/api/server/ping`; because the client default is unrestricted, that request
contains the old saved key. `LoginViewModel.kt:43-56` initializes editable
fields from saved credentials, and `AppStartupViewModel.kt:42-58` has a concrete
permission-recovery path back to Login without clearing those credentials.

The authenticated candidate `/api/albums` call explicitly adds the candidate
key, while Ktor's default-request merge can also append the old default value.
The candidate can therefore receive the old key on ping and potentially both
old and candidate keys during authenticated validation. No compromise of the
candidate is required: the user only needs to edit the URL while the old session
remains persisted.

**Impact**: disclosure of a long-lived Immich API key to an arbitrary host before
that host is authenticated or trusted.

**Smallest correction and verification**: remove authentication from the global
`defaultRequest`; use an unauthenticated ping request/client; attach only the
candidate key to authenticated validation on the normalized candidate origin;
and make all normal authentication origin-scoped. Add MockEngine tests that
assert the old key is absent from a changed host and that the albums validation
contains exactly one candidate key.

#### C-02 — Server/account replacement retains and relabels the old Room cache

**Severity/evidence**: Critical — Confirmed. **Disposition**: open.

The saved-session recovery route preserves credentials and allows edits
(`AppStartupViewModel.kt:42-58`, `LoginViewModel.kt:43-56,106-111`). Successful
validation calls `SaveServerConfigAction.kt:5-12`, which only overwrites URL and
key. The database is a single global store (`AppDatabase.kt:30-49`), and entities
such as `AssetEntity.kt:6-18` have no server or authenticated-user namespace.
Timeline, Album, and People repositories then reconstruct cached URLs with the
*current* configuration (`TimelineRepository.kt:52-78`,
`AlbumRepository.kt:43-63`, `PeopleRepository.kt:42-62`,
`Mappers.kt:63-77`).

**Impact**: after changing server or account, prior Timeline, Album, People,
relationship, asset, and Mosaic rows can appear under the new session. A
cross-server switch also sends old asset IDs to the new origin. A same-server
account switch is a direct cross-user isolation failure and can reuse already
decoded image bytes under identical cache keys.

**Smallest correction and verification**: replace the two-setting save with one
atomic session-replacement action. Treat any normalized URL or key change as an
identity change unless a durable authenticated user ID proves otherwise;
quiesce writers, clear every server-scoped Room/Mosaic/in-memory/Coil cache, then
persist the new identity. Add denied-saved-session tests for URL change and
same-origin key/account change, verifying configuration-last failure behavior.

#### C-03 — Logout is not fenced against active cache writers

**Severity/evidence**: Critical — Confirmed. **Disposition**: open.

Logout remains enabled while sync is active (`MainScreen.kt:231-235,520-527`).
`MainScreenViewModel.kt:85-100` calls `ClearServerConfigAction.kt:25-41`, but
neither component cancels or joins active work, holds a session mutation gate,
or changes a generation checked by writers. `SyncActivityTracker.kt:41-83`
counts work only. Android manual Timeline sync is owned by a service
`SupervisorJob` (`SyncForegroundService.kt:28-50`) and is cancelled only when
the service is destroyed. `TimelineManualSyncCoordinator.kt:28-44` and the Room
transactions in `TimelineRepository.kt:177-223` do not capture or validate a
session identity before committing.

Room serializes transactions, but serialization does not establish the needed
order: a request authenticated as account A can finish after logout clears Room
and write A's rows back after credentials are removed. The next login then
inherits the repopulated rows through C-02.

**Impact**: logout can complete visibly while private data from the logged-out
session is reinserted, violating the cache-first isolation guarantee.

**Smallest correction and verification**: introduce one application-wide
session mutation/write gate; prevent new writes; cancel and join screen-owned
and service-owned work; clear Room, Mosaic, Coil, and memory; then clear
configuration. As defense in depth, capture a session generation before each
request and validate it immediately before every Room transaction. Test a
controlled delayed response/commit racing logout and session replacement.

### Major Issues — Confirmed

#### M-01 — Timeline omits Immich's stack-collapsing query contract

**Evidence**: `ImmichApiService.kt:76-83,91-100` requests both Timeline endpoints
without `withStacked=true`. The pinned OpenAPI contract at
`.local/immich/open-api/immich-openapi-specs.json:14439-14445,14628-14634` and
server implementation at `asset.repository.ts:774-780,869-893` show that stack
counts and primary-only results are conditional on that flag. The official web
Timeline passes it; this app already maps the optional stack tuple in
`AssetResponse.kt:71-104`.

**Impact**: every stack member can appear as an ordinary duplicate Timeline item,
bucket counts include children, and the primary receives `stackCount=0`, hiding
the immediate stack affordance.

**Correction**: send `withStacked=true` on both requests and add request/response
contract tests.

#### M-02 — Album/Person pagination lacks a forward-progress invariant

**Evidence**: `SearchResponse.kt:19-21` accepts any positive next page.
`AlbumRepository.kt:242-248` and `PeopleRepository.kt:241-248` follow tokens in
unbounded loops without comparing the current or consumed pages. The hardened
Favorites implementation at `GetFavoriteAssetsUseCase.kt:44-97` already shows
the intended guard.

**Impact**: equal, backward, or cyclic compatible-looking tokens repeatedly
request and append pages until cancellation or resource exhaustion; incremental
detail paging can repeatedly offer the same page.

**Correction**: centralize next-token validation, reject `next <= current` and
consumed tokens as compatibility failures, and test equal/backward/two-page
cycles in both full and incremental flows.

#### M-03 — Filtering unsupported assets corrupts Album/Person raw page offsets

**Evidence**: Album and Person filter AUDIO/OTHER before assigning compact
supported-only positions (`AlbumRepository.kt:145-163`,
`PeopleRepository.kt:150-168`) but compare and truncate using raw
`(page - 1) * size` offsets (`PeopleRepository.kt:311-322`,
`DetailAssetPreparation.kt:70-79`, `AlbumRepository.kt:190-204`,
`PeopleRepository.kt:180-198`). Full sync and incremental sync therefore persist
two incompatible ordinal schemes.

**Impact**: an unsupported item on an earlier page shifts every later compact
position. A removed first supported item on a later page can be judged unchanged
and survive as a stale relationship; duplicate/stale tail rows can persist.

**Correction**: retain each raw response ordinal before filtering and compare
or truncate by actual persisted `sortOrder` windows. Add unsupported-tail,
unsupported-earlier-page, removal, and reorder regressions.

#### M-04 — People pagination treats an optional flag as authoritative false

**Evidence**: `PersonResponse.kt:17-20` defaults `hasNextPage` to false;
`PeopleRepository.kt:97-107` uses only that Boolean and ignores `total`. The
pinned server DTO marks the flag optional and OpenAPI requires `people`, `total`,
and `hidden`, not `hasNextPage`.

**Impact**: a valid response with 500 people, `total=501`, and no flag stops at
page one and transactionally replaces a complete cache with an incomplete list,
deleting remaining owners and related cache artifacts.

**Correction**: model the flag nullable, continue when true or accumulated count
is below `total`, reject no-progress/cycles, and cover the omitted-flag case.

#### M-05 — Timeline accepts malformed columnar responses as valid cache data

**Evidence**: `AssetResponse.kt:71-80` defaults required columns to empty lists;
`AssetResponse.kt:108-126` validates only a subset. Missing `visibility`,
favorite, ratio, thumbhash, and other required columns silently receive defaults
at `:85-103`, despite the pinned required list in OpenAPI
`:27042-27057` and the compatibility contract in
`docs/ai/timeline-sync.md:51-55`. `TimelineRepository.kt:177-223` then replaces
bucket refs with the permissive result.

**Impact**: `{}` can clear a populated bucket as a successful zero-row response,
and a missing visibility column defaults hidden assets to Timeline-visible.

**Correction**: remove defaults for required columns, require every modeled
required array to equal `id.size`, retain absence only for genuinely optional
columns, and test empty/missing/short arrays.

#### M-06 — Release logging emits private origins, identifiers, dates, and raw exceptions

**Evidence**: no production logging configuration is installed. The pinned
KmLogging behavior enables every level without configuration, as documented in
the [official configuration guide](https://github.com/DiamondEdge1/KmLogging#configuration).
`ImmichApiService.kt:50-279` logs server origins, Album/Person/Asset IDs, bucket
dates, and media operations. `AssetEditsEnricher.kt:64-76` and
`TimelineRepository.kt:245-249` pass raw Ktor exceptions plus private IDs/dates.
Ktor `expectSuccess=true` means those exceptions can retain request URLs and
server-controlled response text. Android Release does not minify logs away.

**Impact**: production platform logs can retain self-hosted origins, media and
owner identifiers, date buckets, and response bodies. This is the same
disclosure mechanism already corrected for Advanced Search in
`CR_w2_v1.2.0.md:61-71`.

**Correction**: disable platform logging for release on every target; log only a
stable operation and sanitized category/status class; never pass throwable
messages, response text, IDs, dates, or credential-bearing URLs. Add injected
logger regressions analogous to Search.

#### M-07 — Album/Person automatic pagination immediately retries failed pages

**Evidence**: `PhotoGridDetailContent.kt:445-459` keys the pagination effect on
`isLoadingMore` and immediately emits true while the footer remains visible.
Album and Person failure paths only set `isLoadingMore=false`
(`AlbumDetailViewModel.kt:286-306`, `PersonDetailViewModel.kt:288-306`), leaving
the same token and geometry eligible.

**Impact**: a persistent failure restarts the effect and requests the same page
again without a new scroll or user action, producing an unbounded retry loop.

**Correction**: track a per-token automatic attempt and suppress failed tokens
until a new near-end transition or explicit retry, matching Search/Favorites.

#### M-08 — `PhotoGridLayoutRunner` has a cross-dispatcher data race

**Evidence**: ordinary mutable `generation` and `job` fields in
`PhotoGridLayoutRunner.kt:19-41` are read/written without a mutex or atomic
owner. Its limited-parallelism calculation dispatcher does not serialize
callers. Search, Favorites, Album, and Person invoke the same runner from UI,
IO, Default, and Room-result paths (for example `SearchViewModel.kt:326,408` and
`FavoritesViewModel.kt:180,263`).

**Impact**: concurrent callers can allocate the same generation, cancel the
wrong job, leave two packs running, and allow an obsolete width/config result to
pass `isCurrent` and publish after the newer calculation.

**Correction**: make launch/cancel/generation single-owner or guard replacement
atomically; test concurrent publication and cancellation with controlled
dispatchers.

#### M-09 — Timeline mutable runtime caches are accessed from multiple dispatchers

**Evidence**: ordinary maps/sets include `dayGroupsCache`, deferred Mosaic maps,
resume maps, and materialized-bucket buffers (`TimelineViewModel.kt:566,634-644,
668-671`). Default pipelines call unsynchronized `getOrPut` at `:5000`; sync
invalidation removes entries at `:3762`; IO materialization adds at `:2191`
while UI settlement drains at `:1076,1181-1187`; dedicated workers and UI paths
mutate deferred/resume state at `:4389-4474,4634-4689`.

**Impact**: compound iteration/filter/clear operations can lose checkpoints or
deferred work, publish stale sections, or fail with concurrent modification.
The nearby confinement comment does not match actual dispatcher ownership.

**Correction**: make each runtime-state family actor-owned on one dispatcher or
protect every compound read/write/invalidate/drain with a shared mutex. Add
controlled interleaving tests.

#### M-10 — Still-image error and retry UI is unreachable

**Evidence**: `AssetPageContent.kt:868` initializes `hasError=false`; the error
branch and Retry button at `:892-896` can only set it false again; the
`CoilZoomAsyncImage` at `:898-905` supplies no error callback and nothing assigns
true.

**Impact**: original-image network/decode failure leaves the viewer blank or in
a failed painter state; the resource-backed failure message and retry control
never render on any source screen or platform.

**Correction**: key error/retry state by asset request, wire Coil error/success
callbacks, and make Retry issue a fresh request. Add a focused painter-state UI
test.

#### M-11 — Album/Person begin viewer exit before preparing an off-screen return source

**Evidence**: `PhotoOverlayHost.kt:110-116` calls `onOverlayDismissed` and then
immediately clears host selection. Album and Person callbacks merely launch a
coroutine (`AlbumDetailScreen.kt:73-80`, `PersonDetailScreen.kt:92-100`), while
`PhotoGridReturnSource.kt:20-31` can suspend for scrolling and a frame. Search,
Favorites, and Timeline correctly await preparation before calling the host
dismiss callback. The requirement is explicit in `docs/ai/ui.md:34` and
`docs/ai/media-viewer.md:34`.

**Impact**: after paging away from the tapped asset, dismiss can start before
the return cell is composed, breaking or degrading the shared transition.

**Correction**: wire Album/Person like Search/Favorites: await
`ensureReturnSourceVisible`, then dismiss the host.

#### M-12 — Custom scrollbar is not accessibility-adjustable

**Evidence**: `ScrollbarOverlay.kt:367-500` provides content descriptions and a
pointer drag gesture, but no `progressBarRangeInfo`, `setProgress`, increment,
decrement, or equivalent adjustable semantics. The inner icon creates another
announced node without an operable action.

**Impact**: TalkBack, VoiceOver, switch access, and non-pointer keyboard users
can discover the handle but cannot move it across the large Timeline and grid
surfaces where it is the primary fast-scroll affordance.

**Correction**: expose one merged adjustable node with a 0..1 range and route
`setProgress`/increment/decrement to the existing fraction callback; make inner
arrows decorative; validate with platform accessibility services when
explicitly authorized.

#### M-13 — Mosaic/Search choices do not expose one coherent labeled control

**Evidence**: Mosaic settings make only visual radio/checkbox controls clickable
while labels/rows are inert (`MosaicViewConfigMenuItem.kt:347-414`). Search mixes
a generic clickable row with independently actionable child controls and uses
`clickable` rather than `selectable`/`toggleable`
(`SearchMetadataFilterSections.kt:573-603`,
`SearchEntityPickers.kt:361-415`). Entity thumbnails also repeat visible labels.

**Impact**: label taps fail in Mosaic, and accessibility trees can expose
separate generic-button and checked/selected nodes with duplicate names and
ambiguous activation.

**Correction**: make the whole labeled row `selectable`/`toggleable` with the
correct role, disable child actions, merge semantics, and mark redundant images
decorative.

#### M-14 — JVM settings use the global Java Preferences root

**Evidence**: `AppModule.kt:89-92` binds no-arg `Settings()`;
`ServerConfigRepository.kt:18-26` uses generic keys and `:81-85` calls
`settings.clear()`. The resolved `multiplatform-settings-no-arg` 1.3.0 JVM
implementation delegates to `Preferences.userRoot()`, not an ImmichGallery node.

**Impact**: another no-arg Java application can collide with `server_url` or
`api_key`, and ImmichGallery logout clears all root-level Java preference keys,
including unrelated applications' values.

**Correction**: platform-provide an app-specific Preferences node and clear only
owned keys. Platform credential-vault hardening is a separate accepted product
decision.

#### M-15 — iOS video authentication depends on unsupported AVFoundation behavior

**Evidence**: `PlatformVideoPlayer.ios.kt:71-80` passes the literal private key
`AVURLAssetHTTPHeaderFieldsKey`. It is not a public AVURLAsset initialization
option, and [Apple staff explicitly state that it is unsupported](https://developer.apple.com/forums/thread/671139).

**Impact**: authenticated playback can regress across OS versions, redirects,
AirPlay behavior, or review policy even though it works in some direct cases.

**Correction**: use a supported signed/authenticated media URL or supported
resource-loading design. If query authentication is chosen, encode the key and
explicitly accept its URL/log exposure tradeoff.

#### M-16 — iOS framework freshness can retain removed or changed generated credentials

**Evidence**: `iosApp/scripts/compile-kotlin-framework.sh:22-46` only checks
currently existing paths newer than the framework. A deleted `local.properties`
is ignored, and effective Gradle/environment values have no fingerprint. The
helper skips Gradle entirely at `:49-56`. Its 11 tests include modified local
properties but not deletion or environment-value changes.

**Impact**: Xcode can reuse a framework containing old generated Login defaults,
including an old API key, after the input is deleted or changed outside the
timestamp set.

**Correction**: always invoke Gradle and rely on task inputs/up-to-date state, or
compare a non-plaintext fingerprint of every effective value and its
presence/absence. Add deletion and environment/Gradle value regressions.

#### M-17 — iOS signing instructions do not control the actual signing team

**Evidence**: `README.md:162-168` directs developers to
`Config.xcconfig`; its `TEAM_ID` only changes the bundle ID
(`Config.xcconfig:1-7`). Debug and Release hardcode
`DEVELOPMENT_TEAM=AR67YA6TFB` and `CODE_SIGN_IDENTITY=Apple Development` in
`project.pbxproj:235-245,263-273`.

**Impact**: another developer following the README does not change the signing
team, and Release has no correct distribution/archive identity path.

**Correction**: resolve team/identity from local or CI xcconfig, remove the
fixed development identity from Release, and document archive/export
provisioning.

#### M-18 — Packaged Desktop video silently requires an external VLC installation

**Evidence**: `PlatformVideoPlayer.jvm.kt:35-40` uses Chaintech's media host. The
resolved runtime graph is Chaintech 1.0.53 -> vlcj 4.11.0 -> vlcj-natives/JNA.
The [upstream Desktop instructions require VLC](https://github.com/Chaintech-Network/ComposeMultiplatformMediaPlayer),
but `composeApp/build.gradle.kts:235-243` does not bundle libVLC and
`README.md:151-160` does not disclose the prerequisite.

**Impact**: a clean DMG/MSI/DEB installation can launch but cannot play videos,
with no project-level prerequisite or actionable recovery contract.

**Correction**: document and detect VLC with a user-facing error, or bundle a
license-compliant libVLC runtime; verify a clean packaged machine.

### Major Issues — Risks And Release Gates

#### MR-01 — Cross-authority redirects forward the custom API key

Ktor 3.5.1 and OkHttp 5.3.2 follow redirects by default. Their audited redirect
implementations remove standard `Authorization` on an authority change but copy
custom headers such as `x-api-key`. The global Ktor default can also reapply it.
API calls use that client (`HttpClientFactory.kt:20-43`); iOS/JVM Coil reuse it
(`ImageLoaderFactory.ios.kt:24-27`, `.jvm.kt:24-27`), and Android Coil adds the
same key to a redirect-following OkHttp client (`ImageLoaderFactory.android.kt:24-37`).

**Risk**: any legitimate proxy/server redirect or compromised redirect target
can receive the long-lived key. No live redirect was exercised, so this is
Major Risk rather than a second confirmed disclosure. Disable redirects or
permit only exact same-scheme/host/effective-port redirects; test API and image
clients on every engine.

#### MR-02 — Coil memory/disk data survives logout and is not account-scoped

Every platform enables a 25% memory cache and persistent disk cache
(`ImageLoaderFactory.android.kt:43-54`, `.ios.kt:29-40`, `.jvm.kt:29-40`).
`ClearServerConfigAction.kt:25-33` does not clear them. The stable key in
`Asset.kt:20-23` includes server URL but not account/session identity and is used
for memory and disk in `ThumbnailCell.kt:123-132`. JVM also uses generic system
temporary directory `image_cache`.

**Risk**: bytes definitely survive logout; a same-origin account switch can
reuse prior-account pixels, especially through C-02/C-03. Add an image-cache
clearing abstraction to cache-first logout/session replacement, use app-owned
directories, and namespace retained caches by authenticated server/user.
`docs/ai/data-cache-time.md:102` requires leakable cache cleanup, while the
detailed logout documents enumerate only Room; reconcile those contracts.

#### MR-03 — Detail Mosaic invalidation can clear a newer generation

`PhotoGridDetailLayoutCoordinator.kt:1485-1527` uses `tryLock`; when the mutex is
busy, `mutateMosaicRuntimeState` schedules a later background clear and returns.
The caller immediately schedules a new generation at `:394-415`. UI, Room IO,
and Default configuration callbacks all reach the shared coordinator.

**Risk**: under lock contention, the delayed generation-wide clear has no
generation predicate and can erase maps/checkpoints populated by the newer
layout. Make clear -> generation increment -> schedule one suspended atomic
sequence or actor-own the coordinator.

#### MR-04 — An unresolved selected asset silently opens unrelated page zero

`PhotoOverlayHost.kt:68-71` resolves a selected ID and substitutes index zero
when resolution returns null, contrary to the identity contract in
`docs/ai/media-viewer.md:24`.

**Risk**: live mutation or restoration can remove/delay the selected ID, causing
the first asset to open while transition identity still references another.
Distinguish pending from absent; abort an absent selection and never substitute
an unrelated asset.

#### MR-05 — Viewer prefetch is uncancelled, unbounded, and includes videos

`StaticPhotoOverlay.kt:224-234` and `TimelinePhotoOverlay.kt:170-180` enqueue
current +/- 1 `originalUrl` requests without size bounds, asset-type filtering,
or retaining/cancelling Coil disposables.

**Risk**: rapid paging can queue unique high-resolution downloads/decodes that
outlive the neighborhood; video originals enter the image pipeline. Prefetch
images only, choose an explicit bounded/disk-only policy, and cancel displaced
requests with overlay lifecycle.

#### MR-06 — Timeline pager identity is numeric while its projection remains live

`TimelinePhotoOverlay.kt:90,367-375` collects a live projection but keys pages as
their numeric index; the static viewer keys by asset ID. `TimelineViewModel.kt:772`
reprojects on every Timeline state update.

**Risk**: insertion/removal before the settled page can bind retained zoom/page
state to a different asset. Retain the settled asset ID and reanchor on
projection changes, using stable placeholder identity while unresolved.

#### MR-07 — Competing Timeline effects can overwrite the scroll anchor before restore

`TimelineScreen.kt:493-534` runs one collector that continuously rewrites the
anchor through the latest display index and another that independently restores
from that mutable anchor.

**Risk**: after projection rebuild, old visible numeric indexes can be
reinterpreted through the new projection and overwrite the original stable
anchor before restoration. Combine capture/reconcile/restore into one ordered
state machine and test insertion/removal before the viewport.

#### MR-08 — Overlapping Android sync can remove foreground ownership prematurely

Manual work foregrounds directly and unconditionally calls
`stopForeground` after its own coroutine (`SyncForegroundService.kt:34-50,
130-134`), while the same coordinator and unrelated work participate in the
shared ref count (`TimelineManualSyncCoordinator.kt:28-38`,
`SyncActivityTracker.kt:41-83`). `onDestroy` does not reset static
`serviceStarted` at `SyncForegroundService.kt:64-66,155-157`.

**Risk**: an older start can remove the notification while a newer start ID
keeps other work alive, and stale static state can select `startService` when
`startForegroundService` is required. Make the tracker the sole foreground
owner, stop only at zero, reset lifecycle state, and test overlaps/timeouts.

#### MR-09 — Distribution licensing has no recorded disposition

The resolved Desktop graph includes `uk.co.caprica:vlcj:4.11.0`; the
[upstream project states GPLv3-or-later or commercial licensing](https://github.com/caprica/vlcj).
This public repository has no LICENSE, NOTICE, third-party notice, source-offer
record, or commercial-license evidence, and Android packaging excludes common
`META-INF` license resources.

**Risk**: this is a release/legal verification gate, not a legal conclusion.
Before distribution, obtain an owner/legal disposition, satisfy the applicable
license/notices/source terms or commercial terms, or replace the dependency.

### Minor Issues — Confirmed

#### m-01 — Asset detail ignores top-level dimensions

`Mappers.kt:104-124` maps only nullable EXIF width/height even though
`AssetResponse.kt:20-23` decodes authoritative top-level values and
`AssetDetailSheet.kt:313-318` needs both. Use EXIF values with top-level fallback
and cover image/video responses with sparse EXIF.

#### m-02 — Removed Timeline Mosaic artifacts can survive a failed manual sync

`TimelineManualSyncAction.kt:67-76` returns after asset-sync failure before
clearing metadata-reported removed bucket IDs. `TimelineRepository.kt:253-300`
can partially commit siblings before a compatibility aggregate failure. Clear
removed-bucket artifacts immediately after successful metadata replacement,
then clear changed-successful buckets after assets; test this ordering.

#### m-03 — Slideshow loops omit the documented post-delay recheck

Static and Timeline slideshows advance immediately after `delay`
(`StaticPhotoOverlay.kt:244-258`, `TimelinePhotoOverlay.kt:219-233`) without
re-reading active mode, page, size, or stack coverage as required by
`docs/ai/ui.md:75` and `docs/ai/media-viewer.md:126`. Recheck captured/current
state after suspension before animation.

#### m-04 — Search scrollbar double-counts its top inset

Search moves the results container below status/top bars at
`SearchScreen.kt:199-236` and passes those insets again to the result-local
scrollbar at `:476`; the list has no matching padding. Pass `0.dp`, as the
equivalent People grid does.

#### m-05 — Status/date localization and one dismiss target violate UI conventions

`ErrorBanner.kt:41,105,116-124` hardcodes English status/month/unknown text and
constrains the outer `IconButton` to 24dp. Timeline derives English month labels
and parses the year from display text (`TimelineViewModel.kt:5020`,
`Mappers.kt:193`, `TimelineViewportProjectors.kt:41`). Use resources and typed
date structure; keep a 48dp interactive target and size only the icon.

#### m-06 — Selecting the current person pushes an identical route

`StaticPhotoOverlay.kt:454-466` forwards person taps and
`AppNavigation.kt:106-115` always pushes another `PersonDetailRoute`, even for
the current ID. Dismiss without navigating when IDs match.

#### m-07 — Android/JVM video discard the app-level tap callback

Both actuals accept but never invoke `onTap`
(`PlatformVideoPlayer.android.kt:19-59`, `.jvm.kt:19-66`); iOS wires it. Viewer
chrome therefore cannot toggle through the common contract on Android/JVM.
Integrate without stealing native player controls and perform focused UI checks.

#### m-08 — Desktop slideshow wake lock is a documented no-op

`ScreenWakeLock.jvm.kt:11-14` does nothing while both viewers acquire it and
`docs/ai/media-viewer.md:124-137` claims it remains active. Implement platform
sleep inhibition or document Desktop as unsupported.

#### m-09 — iOS stores refetchable Room cache in Documents

`DatabaseBuilder.ios.kt:9-16` uses `~/Documents/immich_gallery.db`. Apple's
[filesystem guidance](https://developer.apple.com/documentation/foundation/using-the-file-system-effectively)
reserves that location for user documents and backs it up. Migrate to
Application Support with backup exclusion, or Caches if purge/rebuild semantics
are acceptable.

#### m-10 — iOS enables PiP without the required host configuration

`PlatformVideoPlayer.ios.kt:153-160` enables Picture in Picture, but
`Info.plist:4-14` has no Audio/AirPlay/PiP background mode and the repository has
no playback `AVAudioSession` setup. Complete Apple's
[media playback configuration](https://developer.apple.com/documentation/avfoundation/configuring-your-app-for-media-playback)
or disable PiP until supported.

#### m-11 — Desktop JVM bytecode/toolchain is not pinned

`composeApp/build.gradle.kts:131` declares bare `jvm()`; only Android pins JVM
11. Foojay is installed but no toolchain is requested. Declare a Desktop
`jvmToolchain` and compiler target so developer/CI JDK choice does not change
artifacts.

#### m-12 — Public source has no explicit project license

The current [public ImmichGallery repository](https://github.com/ch4ndu/ImmichGallery)
contains no project LICENSE and GitHub reports no detected license. Users and
contributors therefore have no explicit grant governing reuse/contribution.
The owner must choose the license; this audit does not select one.

### Minor Risks And Measurement-Needed Items

#### mr-01 — Album/People/Login can launch duplicate owner requests

Busy state is claimed only inside dispatched work or has no active-job guard
(`AlbumListViewModel.kt:53-70`, `PeopleViewModel.kt:71-88`,
`LoginViewModel.kt:72-110`). Rapid activation can overlap refresh/login requests
and make the last completion own visible state. Claim atomically before launch
or retain one active owner job.

#### mr-02 — Timeline publish and repair channels are unbounded

`TimelineViewModel.kt:646-650` uses `Channel.UNLIMITED`; producers `trySend` rich
payloads before consumer-side coalescing at `:783-805,4653-4660`. Profile backlog
under large libraries; if material, keep latest payloads in an owner map and use
a bounded/conflated wake-up channel.

#### mr-03 — Ken Burns animation reads frame state during composition

`KenBurnsImage.kt:114-128` reads `Animatable.value` and derives transform in the
composable body. This likely recomposes every frame; impact is unmeasured. If a
trace shows material work, move reads/calculation into `graphicsLayer` so only
layer properties invalidate.

#### mr-04 — iOS fallback polling runs on precomposed inactive pages

`PlatformVideoPlayer.ios.kt:84-129` polls for five seconds independently of
`isCurrentPage`; play/pause is separate at `:131-139`. A paused neighbor can
consume or switch its fallback before becoming current. Gate/restart observation
on current-page ownership.

#### mr-05 — JVM media query authentication is concatenated without encoding

`PlatformVideoPlayer.jvm.kt:27-34` concatenates the raw API key into the query.
Reserved characters could alter query semantics, although current Immich key
formats may avoid them. Use a URL builder and percent-encode the value.

### Suggestions

#### S-01 — Add a CI gate for the documented host matrix

No CI workflow is tracked. Automate the exact lint/compile/assemble/host-test
matrix used below, plus the iOS framework helper suite, while preserving focused
local tests as the normal development loop.

#### S-02 — Add focused regressions for session and concurrency boundaries

There are no direct tests for `LoginViewModel`, `AppStartupViewModel`,
`MainScreenViewModel`, the full `TimelineViewModel`, global auth/redirect
behavior, or logout versus active writers/Coil. Add targeted controlled-fake or
MockEngine tests; do not introduce a broad live-server integration harness
without a separate scope decision.

#### S-03 — Add supply-chain reproducibility and transitive scanning

Add `distributionSha256Sum`, Gradle dependency verification/locking, an SBOM,
and a repeatable vulnerability/license check. This audit's direct-coordinate
advisory query found no matches, but it did not resolve a complete transitive
SBOM and is not a substitute for SCA.

#### S-04 — Establish release package validation

Desktop packaging has no recorded icon/signing/notarization policy, clean-machine
VLC test, Gatekeeper/SmartScreen check, or DMG/MSI/DEB launch record. Android
production signing and store archives remain separate known gates. Define these
only when a distribution target is authorized.

#### S-05 — Split the largest runtime owners when behavior is remediated

`TimelineViewModel.kt` is 5,039 lines with 21 constructor dependencies and no
direct end-to-end ViewModel test; `PhotoGridDetailLayoutCoordinator.kt` is 1,592
lines. The concurrency findings show a concrete ownership cost. During fixes,
extract only cohesive actor/state owners needed to establish confinement rather
than performing an unrelated broad refactor.

#### S-06 — Add measured accessibility/performance verification

When explicitly authorized, validate the repaired scrollbar/choice semantics
with TalkBack and VoiceOver, trace Timeline queue depth and Ken Burns
recomposition, and exercise clean packaged media. No device or GUI flow was run
in this audit.

---

## Compliant Areas

- **Architecture/layering**: ViewModels use UseCases and Actions rather than
  repositories; API construction stays in data; common behavior remains in
  `commonMain` unless native APIs require actuals. No Kotlin `!!`, `GlobalScope`,
  production TODO/FIXME, blocking UI sleep, or unimplemented common placeholder
  was found.
- **Platform completeness**: every explicit expect seam has Android, iOS, and
  JVM actuals; Koin modules bind matching DAO/controller surfaces; Room/KSP
  compiler configurations exist for every configured target.
- **Room baseline**: all platform builders use Bundled SQLite, IO query context,
  and the same migration path. Schema 1 -> 2 has no shape change and has an
  Android preservation test. Cache-first/configuration-last logout ordering is
  correct in isolation; the defects are missing writer exclusion and image
  cleanup.
- **Cancellation/error primitives**: `runSuspendCatching` and inspected broad
  catch paths rethrow `CancellationException`; server monitoring owns and clears
  its job. HTTP has explicit 15/60/60-second timeouts, tolerant unknown fields,
  `expectSuccess`, and Ktor wire logging disabled.
- **API implementations**: Favorites guards consumed/backward tokens and sparse
  pages; Search normalizes criteria before I/O, freezes endpoint/session state,
  omits inactive JSON fields correctly, cancels stale jobs, and sanitizes
  suggestion logs. People list fetches all successful pages before its one
  replacement transaction, subject to M-04.
- **Photo-grid hot paths**: Timeline, detail, Search, Favorites, Album, People,
  and Memories use stable lazy keys; the main thumbnail path remembers a bounded
  256x256 request with stable memory/disk keys. Search/Favorites automatic paging
  uses attempt keys and avoids M-07's immediate retry pattern.
- **Viewer baseline**: only settled pages play video; nested stacks disable base
  paging; wake locks are released on exit/disposal; Search, Favorites, Timeline,
  and Memories correctly prepare return identity. Search filter Apply/Cancel is
  transactional.
- **Android baseline**: thin application/shared KMP ownership is correct;
  manifests declare nonexported data-sync service and required permissions;
  process Koin supports services; local-network prompting is classified,
  serialized, and settings activation is explicitly user-triggered. FGS type,
  immediate start, channel, ongoing notification, immutable pending intent, and
  timeout hook are present outside MR-08.
- **iOS/JVM baseline**: iOS ATS is local-network scoped with no arbitrary-load or
  multicast/Bonjour configuration; lifecycle ownership matches each host. iOS
  pauses off-current/disposed players. JVM window focus, viewer input, and zoom
  shortcuts are wired.
- **Build/dependencies**: versions are aligned at 1.2.0/build 3; repositories are
  limited to Google, Maven Central, and Plugin Portal; direct versions are
  pinned. No tracked credential, keystore, provisioning profile, or signing
  secret was found.
- **Documentation hygiene**: all tracked Markdown local links resolved. Current
  official Immich source at
  [commit `c98c20e`](https://github.com/immich-app/immich/commit/c98c20e9639257187e1e3b2efa9f1a7f3b465a9d)
  was compared with the pinned reference for consumed search, person, server,
  tag, asset, and Timeline DTO/controller surfaces; no additional current
  contract drift was found beyond M-01, M-04, and M-05.

---

## Known Accepted Or Documented Boundaries

These are transparent release/security constraints, not counted again as newly
discovered findings in this review:

- optional developer Login defaults are generated into `commonMain` for every
  target/configuration and can embed a nonblank local key in release artifacts;
  gitignored local values were deliberately not inspected;
- `multiplatform-settings` is documented as application configuration rather
  than a secure credential vault, and JVM video may put the key in a URL;
- Android allows cleartext traffic for trusted LAN use, but the code does not
  technically enforce the documented expectation of HTTPS outside trusted
  networks;
- Android `allowBackup=true` has no data-extraction exclusions, so credentials
  and refetchable cache can be backed up under platform defaults;
- Android Release uses debug signing and has minification disabled; this is
  already documented as a production-release blocker in `docs/ARCHI.md:553-556`;
- iOS supports ARM64 device/simulator only with iOS 18.2 minimum, matching the
  current documented target boundary.

If any of these accepted boundaries is reopened, the smallest security posture
would be debug-only generated defaults, OS-backed credential storage,
local-only HTTP enforcement, explicit backup exclusions, and externally
supplied production signing.

---

## Checklist

- [ ] 1. Functional Requirements — open viewer, pagination, Timeline stack,
  People completeness, video, and navigation behavior findings.
- [ ] 2. Code Quality — open oversized-owner and thread-confinement findings;
  naming, typing, imports, and Kotlin null-safety otherwise passed.
- [ ] 3. Architectural Compliance — layering passed, but session/cache ownership
  and runtime state ownership do not satisfy the durable contracts.
- [ ] 4. KMP Layers And Lifecycle — platform actual coverage passed; open Android
  service, iOS media/build/signing, JVM settings/media, and shared concurrency
  findings remain.
- [ ] 5. Immich API, Room, And Cache — C-01 through C-03, M-01 through M-05,
  MR-01/MR-02, and m-01/m-02 remain open.
- [ ] 6. Compose UI And Photo Grids — stable identity/virtualization largely
  passed; pagination effects, thread ownership, return transitions,
  accessibility, viewer identity, and performance risks remain.
- [ ] 7. Platform Integration — source compilation passed; native/device/package
  verification was not authorized and platform findings remain.
- [ ] 8. Error Handling — unreachable image failure UI, retry loop, permissive
  compatibility handling, and release logging remain open.
- [ ] 9. Security — three Critical session/credential findings and redirect/cache
  risks remain open.
- [ ] 10. Performance — existing tests and static hot-path patterns largely pass;
  runtime maps, prefetch, channels, and animation require fixes or measurement.

---

## Verification

### Build, lint, and tests

The integrated source/build matrix passed from the audited baseline:

```text
./gradlew --no-daemon \
  :androidApp:lintDebug \
  :composeApp:compileKotlinJvm \
  :composeApp:compileKotlinIosArm64 \
  :composeApp:compileKotlinIosSimulatorArm64 \
  :composeApp:compileAndroidMain \
  :androidApp:assembleDebug \
  :composeApp:jvmTest \
  :composeApp:testAndroidHostTest
```

- result: **BUILD SUCCESSFUL**, 114 tasks, 1m40s;
- Android lint: zero errors and one informational compatibility warning that
  `enableOnBackInvokedCallback` is ignored below API 33;
- compiler diagnostics: only expected expect/actual Beta warnings.

Tests were then forced to execute rather than relying on up-to-date outputs:

```text
./gradlew --no-daemon --rerun-tasks \
  :composeApp:jvmTest \
  :composeApp:testAndroidHostTest
```

- result: **BUILD SUCCESSFUL**, 54s;
- JVM host: 359 passed, zero failed/errored/skipped;
- Android host: 359 passed, zero failed/errored/skipped;
- total fresh executions: **718 passed**.

`iosApp/scripts/tests/compile-kotlin-framework-test.sh` passed all 11 current
freshness checks; M-16 identifies missing deletion/environment cases rather than
a failure of those tests. The Android Room migration device test was inspected
but not run because no device flow was authorized.

### Static and repository checks

- inventory and source-layout scan completed across all tracked files;
- no Kotlin `!!`, production TODO/FIXME, ViewModel-to-repository injection, or
  tracked real credential was found;
- all tracked Markdown local links resolved;
- current version surfaces are aligned and the worktree baseline was clean;
- direct pinned dependency coordinates were queried against the
  [GitHub Advisory Database API](https://docs.github.com/en/rest/security-advisories/global-advisories):
  no direct-coordinate match was returned. This was not a resolved-transitive
  SBOM/SCA scan;
- `gitleaks`, `trivy`, and `semgrep` were unavailable, so equivalent lightweight
  repository scans were performed but are not represented as full tool coverage.

### Explicitly unperformed verification

No emulator, simulator, physical device, application GUI, screenshot, device
log, live Immich server, backup/restore flow, platform accessibility service,
App Store/Play Store archive, signed/notarized package, clean-machine VLC test,
DMG/MSI/DEB launch, Gatekeeper, or SmartScreen run was performed. Those checks
require an explicitly authorized device/package/release scope and are not
silently inferred from successful compilation.

No production source, architecture rule, or behavior document was changed. The
only audit output is this review record.

---

## Verdict

**NEEDS REVISION**

The project has a healthy compilation baseline and several strong architecture,
cache-transaction, cancellation, lazy-layout, and multiplatform patterns, but it
does not meet the review approval gate while three Critical and multiple Major
issues remain open. Remediation should begin with one coherent session boundary:
origin-scoped authentication, atomic session replacement/logout, writer
quiescence/generation fencing, and Room/Mosaic/Coil cleanup. The next wave should
then address API compatibility/pagination and release logging, followed by
thread confinement in Timeline/detail layout, viewer error/identity behavior,
accessibility, and platform media/release configuration.

This findings-only audit does not authorize implementation, release, push, or
acceptance of any open risk.
