# Photo And Video Viewer Architecture

Load this when changing photo/video detail viewing, overlay paging, shared
transitions, expanded asset information, slideshow, drag dismissal, platform
video playback, or desktop viewer shortcuts.

## Viewer Owners

- `PhotoOverlayHost` owns open/dismiss state, shared-transition lifecycle,
  transition identity, and the hidden grid asset for Search, Favorites,
  Memories, Album Detail, and Person Detail.
  Opening resolution is explicit: `Found` mounts the overlay at the resolved
  page, `Pending` keeps the requested asset selected while waiting for the
  owner source, and `Absent` clears the request. Hosts must not fall back to
  page zero when a non-Timeline source cannot resolve the requested asset.
  Each host keys resolution by its owner, ordered asset ids, and source
  readiness/completeness so an incomplete source cannot be mistaken for an
  authoritative absence. A restored `Found` selection also reconstructs its
  grid transition identity. Re-resolving an already-open source preserves the
  pager's settled current asset rather than resetting return identity to the
  originally tapped asset.
- `StaticPhotoOverlay` pages an already materialized ordered asset list for
  those screens.
- Timeline uses `TimelinePhotoOverlay` because its pager spans lazy buckets.
  `TimelineOverlayCoordinator` owns the API key, expanded asset/file-name
  lookups, projected overlay state, and atomic asset/bucket return target.
- `AssetPage` is the shared still/video/slideshow media surface.
- `AssetDetailSheet` fetches and renders expanded asset information.
- `SlideshowOptionsDialog` edits `SlideshowConfig` before playback starts.
- Both overlay types use `SlideshowBinding` and one shared
  `SlideshowPlaybackController`. The binding serializes synchronous decisions
  on a Default-dispatcher consumer and publishes immutable flow snapshots;
  source-supplier materialization and 33ms tick wakeups are deferred while
  inactive, materialized atomically on `start(...)`, and cleared on `stop()`.
  Pager/transition presentation remains owned by Compose.
- `PlatformVideoPlayer` is expect/actual platform UI.
- `StackSessionOverlay` owns the loading/error/retry surface for one ephemeral
  nested stack viewer and reuses `StaticPhotoOverlay` for ready members.

## Paging And Return Targeting

- The selected asset id is resolved against the current ordered screen asset
  source before opening.
- Horizontal pager pages use the same ordering as the source grid. Timeline
  resolves page indexes through its bucket/page projection and requests a
  bucket when the current or adjacent page has not been materialized.
- The settled asset id is reported to the host so only that grid cell is hidden.
- Every back, drag, or host dismissal reports the currently viewed asset, not
  only the originally selected asset.
- Before the exit transition, the owning screen maps that id through its current
  display index and scrolls only when the source is fully off-screen. Static
  overlay hosts must await this return-source preparation before starting the
  host exit animation, so the grid-side shared-element source is visible in the
  exit composition. Timeline commits asset id and bucket together through
  `TimelineOverlayCoordinator`.
- `lastSelectedAssetId`/host-retained content keeps the exit destination alive
  until the shared transition completes.
- `PhotoOverlayHost` has an explicit return policy. Current-asset mode is the
  default for Timeline, Album, Person, Explore Search, and Favorites. Memories
  selects origin mode: the cover stays hidden and remains the shared-element
  exit target while settled-page changes continue to drive Info, playback,
  slideshow, and stack actions.
- The Memories base viewer shows a bottom-center page-dot indicator when a
  memory has more than one supported asset. When every fixed-width dot slot
  fits the measured available width, one dot is shown per asset and the pager's
  fractional position continuously transfers size and opacity from the
  outgoing dot to the incoming dot. When the complete dot row does not fit, a
  compact full-width segmented progress bar uses that same fractional position.
  Accessibility continues to announce the settled page. The indicator stays
  visible when filename chrome is hidden, and nested stack/detail layers cover
  it without changing the base position.
- Normal viewer chrome offers a filmstrip toggle and position text. The lazy
  strip resolves only visible thumbnails, marks the selected item, pages on
  selection, and reveals the settled page with a direct scroll. Static counts
  describe the materialized source only; Timeline uses its projected count and
  stable bucket/offset string keys through materialization. Visible strip demand
  reuses existing bucket loading without a separate library query.
- Collapsing the strip retains normal position chrome. Memories keeps its
  established indicator without a second competing position count. Slideshow,
  drag, shared transition, and covered layers hide the strip; safe-area padding
  and existing zoom/letterbox input ownership remain with the overlay.
  Tap-to-hide normal chrome also hides the new strip/position/toggle without
  forgetting expansion; Memories' original persistent indicator is separate.

## Viewer-Scoped Stack Browsing

- A settled asset with a positive `stackCount` shows **View stack** immediately
  only while its viewer-local availability is `Unknown`. The list count is a
  provisional hint: once an authoritative `Checking`, `Available`, `NoStack`,
  or `Failed` state exists, that state wins even if the source count is stale.
  A zero count is unknown because sparse metadata-search responses can omit
  nested stack data; opening the overflow menu starts one lazy authoritative
  `GetAssetStackUseCase` preflight for that asset.
- Preflight state is explicit and viewer-local: `Unknown`, `Checking`,
  `Available`, `NoStack`, or retryable `Failed`. Only a successful current
  asset response without a stack publishes `NoStack`; permission, unavailable,
  transient, and unsupported-member failures remain actionable failures.
- An available preflight is reused when **View stack** is selected. A known
  positive count defers the two-read resolution until selection. Only one
  request is active, and paging or closing the base viewer cancels or rejects a
  stale result.
- Stack members are deduplicated, the supported primary is placed first, and
  the originating member is the initial page when still present. Unsupported
  asset types are filtered without changing the remaining server order.
- The nested session disables recursive stack entry and covers the base viewer.
  Base paging, gestures, shortcuts, slideshow, and video activity are disabled
  while covered. Loading and error surfaces consume pointer input before it can
  reach base-viewer chrome while leaving their own Back and Retry controls
  usable. Back, Escape, or nested drag dismissal closes the stack first and
  restores the unchanged base page.
- Stack children never become `PhotoOverlayHost` grid return targets. Info,
  image zoom, authenticated video, paging, slideshow, and person navigation
  continue through the existing static viewer components.
- From a memory non-cover page, dismissing a nested stack restores that exact
  memory pager page first; dismissing the base viewer then returns to the
  memory cover retained by origin mode.

## Still Images

- Grid/detail thumbnails use a bounded 256 x 256 exact Coil request with stable
  memory/disk cache keys.
- Still pages request the edited original URL and use the matching thumbnail as
  the transition/loading source.
- Still-image load state is scoped to the original URL and retained by the
  stable page across transition/drag rendering branches. Loading, success, and
  failure callbacks apply only to the exact URL plus retry-nonce request that
  produced them. Retry increments that nonce in the Coil request while
  preserving the asset/page identity; late callbacks from an older nonce are
  ignored.
- Static and Timeline still-image viewers prefetch only `IMAGE` assets in the
  settled viewport window: current page plus one neighbor on each side for
  static lists, and the resolved candidate pages Timeline supplies for bucketed
  paging. Prefetch requests use the measured viewport size, retain active Coil
  disposables for unchanged targets, and dispose targets that leave the window
  or when the overlay leaves composition. Videos are not prefetched through
  this still-image path.
- During slideshow, the reserved upcoming target participates in bounded
  prefetch, including shuffled and forward-history targets. Lazy Timeline
  candidates retain bucket/offset identity while assets materialize; loading
  state and loaded-ID maps must not reset the shuffle cycle.
- `CoilZoomAsyncImage` owns pinch/pan zoom. Once zoomed, the media zoom gesture
  takes precedence over drag dismissal.
- A tap toggles viewer chrome when the detail sheet and slideshow are inactive.
- The fitted media container owns drag transforms so the touched media point
  remains anchored during dismissal; snap-back restores identity only when the
  dismiss is cancelled.

## Video

- Only the settled pager page is playing; off-screen pages pause.
- Android sends the API key as an HTTP header to the shared media-player host.
- JVM and iOS build media URLs through one fail-closed `AuthenticatedMediaUrl`
  boundary. It accepts only absolute HTTP(S) URLs, removes duplicate `apiKey`
  values, appends exactly one percent-encoded key, preserves other query and
  fragment components, and redacts its diagnostic representation. JVM retains
  the existing query-authentication contract with mpv; iOS uses it instead of
  private AVFoundation header options.
- iOS tries the authenticated original file first, then falls back to Immich's
  authenticated playback/transcode URL when AVPlayer fails or stalls. The
  bounded fallback observer exists only while that pager page is current;
  leaving cancels it and returning starts a fresh window while the original is
  still selected.
- iOS Picture in Picture is disabled. Enabling it requires a separate contract
  for AVAudioSession and host background-media capability.
- Android uses MediaMP's ExoPlayer backend; desktop uses its mpv backend.
  Both disable native controls and share a common binding with one player per
  authenticated source and slideshow request, closed on disposal. Opening is
  initially paused; current eligibility and user pause intent govern playback
  after opening. Normal end-of-media loops only while current and user-playing.
- Desktop startup prepares the host-specific published mpv runtime from the
  classpath before player construction. Extraction/validation or native-load
  failures show the resource-backed recovery message, as do invalid media URLs.
  There is no system-player fallback. Supported runtime hosts and licenses are
  documented in the README. Native runtime JNA dependencies use the app's JPMS
  pair to avoid duplicate classes.
- Normal video uses app-owned play/pause, seeking, elapsed/duration, mute,
  ten-second backward/forward skip and a 0.125×/0.25×/0.5×/1×/1.5×/2× speed menu across
  Android, iOS and JVM. Seek/time and playback actions occupy separate rows;
  skip clamps to valid duration and is disabled while duration is unknown.
  Speed selection preserves pause intent and survives iOS source fallback.
  Settings are ephemeral per player, with no persisted playback preference.
  Native route, track/quality and fit menus remain hidden; video remains FIT.
  The video and original URLs remain server-derived edited variants.
- Vertical drags in the outer left/right 30% of an active normal video adjust
  brightness/player volume. A full-height drag spans the level range; a
  temporary indicator shows the adjustment. The child consumes movement on
  crossing vertical slop; horizontal paging and central dismiss/Info gestures
  retain ownership. Desktop uses primary-button drags. A second pointer or
  cancellation ends adjustment. Controls and the filmstrip own their input.
  Raising volume from mute unmutes before applying the new level; mute/unmute
  retains the chosen volume. No permanent volume/brightness sliders appear.
- Android adjusts the active window's brightness; iOS adjusts its screen.
  Only the eligible normal video owns an override, restores the prior value
  when covered, backgrounded, paged away or disposed, and reapplies a chosen
  level when that same player regains eligibility. Entering a fresh video does
  not override brightness until adjustment. Desktop instead dims only the
  video layer, up to 85%, leaving chrome and thumbnails undimmed. Mobile
  ownership prevents a late disposal from restoring over the newly active page.
- `StaticPhotoOverlay` and `TimelinePhotoOverlay` share a viewer-owned chrome
  state: normal video's top bar, playback controls, filmstrip and Info handle
  show/hide together immediately after three seconds of inactivity. Surface
  taps toggle this state; control actions, thumbnail selection/toggle and
  footer wheel scrolling restart the interval. Pointer holds and open overflow
  menus suspend hiding; release starts a fresh interval. Side adjustments reveal
  hidden chrome and hold it under `VideoGesture`; the speed popup owns
  `VideoSpeedMenu`, separately from control presses and top-bar overflow.
  Menu holds follow actual popup lifetime, including disposal. Photos retain manual
  tap-to-toggle visibility and existing transitions, including the current
  visibility when paging from video to photo.
- Info, slideshow dialogs, covered stacks, drag/shared transitions and inactive
  apps hide normal-video chrome and suspend its timer. Regaining eligibility
  reveals it for a fresh interval. Only the settled page renders playback
  controls. Slideshow keeps its own controller/timing, and the Memories page
  indicator remains independent of normal chrome visibility.
- Android/JVM slideshow video retains pinch zoom up to 3× with one tap owner
  for slideshow controls. Normal video keeps paging and side-level gestures.
- Android/JVM disable built-in normal controls and competing gestures. A
  non-consuming surface-tap observer wraps only the video, with shared controls
  as siblings. iOS uses a full-bounds `AVPlayerViewController` with native
  playback controls disabled and shared Compose controls outside its tap
  recognizer. The shared side-gesture surface wraps the iOS native view;
  cooperative UIKit touch delivery requires native verification, not just a
  successful Kotlin compile. Manual pause intent survives lifecycle pauses
  and source fallback.
- Both viewers measure footer height excluding navigation insets and forward
  `bottomChromePadding` through `AssetPage`. Only the shared controls are offset
  above that footer. The video surface never receives footer-dependent padding;
  its FIT viewport stays stable when chrome hides or the filmstrip expands.
  The parent media layer supplies safe-area padding once.

## Expanded Information

- Opening Info calls `GetAssetDetailUseCase`, which fetches `/api/assets/{id}`
  through `AssetRepository`. This expanded response is on-demand and is not
  persisted in Room.
- The sheet can show filename, localized capture date/time, dimensions, file
  size, recognized people, location/coordinates, camera/lens/exposure metadata,
  and description when those fields exist.
- Selecting a recognized person dismisses the sheet and viewer, then navigates
  to that person's detail route.
- Loading and failure are local to the sheet and do not close or replace the
  underlying pager.
- Detail/loading/error/action feedback is keyed by the displayed asset. A
  cancelled request or response for a different asset cannot publish metadata
  actions for the current sheet.
- Finite latitude/longitude within inclusive geographic bounds expose explicit
  Open in maps and Copy coordinates actions, even without a textual location.
  Maps uses a coordinates-only HTTPS URL; the clipboard receives a plain pair.
  Copy success and dispatch failures use resource-backed accessible feedback.
- The sheet exposes Show in Timeline through an optional date callback. Its
  caller supplies the settled asset's canonical `createdAt` so date grouping
  agrees with Timeline: instants use the device time zone, valid ISO local
  datetimes/dates retain their date. A supplied malformed canonical value hides
  the action. Expanded-detail date fallback is allowed only when canonical
  data was not supplied, and requires a fully valid ISO value.
- A date action closes Info and any child stack, then completes the base
  viewer's return preparation and existing exit lifecycle before routing. The
  clicked child date is separate from the unchanged base return asset/bucket;
  Memories still returns to its origin cover. Root navigation pops to the
  existing Main entry, and Main delivers a uniquely identified ephemeral request
  to the existing Timeline tab/resolver. Loading delays delivery; ready-empty
  targets resolve to the existing unavailable-date feedback. Matching-ID
  acknowledgement, session epochs, and logout-start cancellation prevent replay
  or stale navigation.

## Desktop Fullscreen Ownership

- The desktop window host binds `WindowState` and provides a scoped
  `ViewerFullscreenController`; mobile and previews use an unsupported default.
- A viewer session starts owning placement at its first fullscreen action.
  Slideshow leases additionally retain the placement at show entry. Releasing
  a show restores that entry placement, while closing its viewer restores the
  viewer's entry placement, including maximized or already-fullscreen states.
- Nested stack viewers inherit the parent session through composition. Covered
  parents cannot change placement, and nested releases cannot overwrite parent
  restoration targets. Closing a parent invalidates its entire subtree; stale,
  duplicate, forgotten, and abandoned cleanup is safe.
- Window visibility/non-minimization is distinct from application foreground
  state. The bridge must not replace the playback application's background gate
  with visibility alone or alter server-status focus monitoring.
- Fullscreen controls and F are available only where the host supports them.
  The remembered automatic-fullscreen option acquires a show lease that survives
  pause/coverage/transient focus loss. Focus loss can freeze presentation time;
  it must not stop the show, discard history, or release/recreate the lease.
  Escape stops slideshow first, then exits fullscreen, then dismisses the viewer
  on subsequent presses. Modal and covered-viewer shortcut gates still apply.

## Slideshow

- Slideshow is offered only when the viewer has more than one page.
- `SlideshowConfig` independently selects sequential/random order, a validated
  1–120-second duration, Still/Pan-and-zoom motion, Cut/Fade/Slide transition,
  Skip/Thumbnail/Play video policy, mute, an optional positive video cap,
  captions, and desktop automatic fullscreen. Preferences go through the
  existing settings UseCase/Action and viewer-preferences ViewModel. They are
  device-wide and survive logout; current assets, history, requests, and caption
  metadata remain ephemeral to the viewer session.
- The in-show mute control also updates the remembered mute preference through
  the ViewModel/Action, preserving the other saved options. It does not persist
  the current slide, timer, media request, or history.
- Sequential order wraps. Random order uses a no-repeat cycle and avoids an
  immediate repeat across cycles. Previous traverses displayed history; Next
  replays forward history before drawing a new target. Skipped and unresolved
  candidates must not become displayed history. Genuine source membership/order
  changes reconcile identity and surviving history; materialization alone does
  not restart playback order.
- Full-resolution readiness plus completed transition starts the still-image
  dwell. Thumbnail placeholders, displaced URLs/retry requests, and previous
  visits cannot start or advance the timer. Dwell and pan/zoom share accumulated
  monotonic presentation time; pause/background/coverage/blocking Info freezes
  both. A new manual visit starts a full interval, while resume retains time.
- Cut changes pages directly, Slide uses pager movement, and Fade retains only
  the outgoing and target media rather than traversing intermediate shuffled
  pages. Completion messages are generation-checked before advancing the
  controller. Offscreen/covered videos remain paused.
- Play video starts while awaiting progress, disables looping, and uses a fresh
  player generation for each visit. Pause/resume retains the same visit and
  playback position. MediaMP positive observed position establishes readiness;
  duration, play commands, and initial nonbuffering do not. A valid short-video
  end can complete before the first positive progress event. Completion/cap
  events advance at most once, and the cap uses media position.
- Active-time startup and no-progress watchdogs bound unavailable media to
  15 seconds. Failures remain visible briefly before skipping; three consecutive
  failures pause for Retry/Next/Exit. Successful readiness resets the streak.
  Lazy Skip resolves candidates as needed and distinguishes all-video input from
  loading, failed, and cache-policy-unavailable buckets. Retry uses the explicit
  retry callback. Missing cache-only rows require manual Timeline refresh;
  slideshow must not override sync policy or repeatedly restart ineffective loads.
- Captions request only current-slide expanded metadata, discard stale reads,
  and bound date/location/description text. Metadata failure cannot block media
  playback or leave the previous slide's caption visible.
- Previous/Pause-or-Resume/Next/Exit controls remain usable while paused or
  failed; playing controls can hide after inactivity. Tap toggles controls rather
  than stopping the show. Exit restores normal viewer chrome.
- `ScreenWakeLock` stays acquired only while actively presenting playback and
  releases on pause/background/exit/disposal. JVM uses one balanced process-wide owner: Windows performs
  acquire/reset on the same dedicated JNA thread, macOS owns a `caffeinate`
  process, and Linux owns a `systemd-inhibit` process when available. Missing
  native commands are a safe unsupported fallback, and application exit closes
  only the process/thread owned by ImmichGallery.

## Input And Chrome

- Mobile/tablet supports horizontal paging, still-image pinch/pan, tap-to-toggle
  chrome, downward drag dismissal, and an upward drag or bottom handle for Info.
- JVM additionally supports Left/Right page navigation (displayed history during
  slideshow), Space to open slideshow options or pause/resume an active show,
  `I` for Info, and F for supported fullscreen. Escape follows slideshow,
  fullscreen, then viewer dismissal precedence. The shortcut focus effect also
  follows slideshow presence: stopping a show can remove its focused button,
  so the normal viewer must reclaim focus for subsequent keys.
- Grid density uses pinch zoom on touch platforms and Ctrl/Cmd-wheel on JVM;
  that is separate from full-image zoom inside the viewer.

## Verification Targets

Synthetic JVM Compose tests may provide `LocalViewerPlaybackEnvironment` to
replace external media rendering, time, and wake inhibition while retaining the
actual overlay, controller, pager, and callbacks. Production defaults retain
native rendering, monotonic time, automatic ticks, and platform wake locks.
Those tests establish interaction/state wiring; native player output, OS window
placement, clipboard/maps dispatch, and Android/iOS device behavior require
separate runtime evidence.

- swiping before dismiss returns to the settled asset rather than the original;
- a Memories viewer returns non-cover pages to its originating cover card;
- missing or still-pending open sources do not fall back to page zero;
- a Memories viewer shows every fractionally active page dot when the complete
  row fits, including 20 assets at 320dp, and otherwise shows fractional
  full-width segmented progress;
- a fully off-screen return source is made visible before exit;
- still-image retry is URL-scoped and prefetch stays bounded/disposed;
- still-image zoom prevents accidental drag dismissal;
- only the settled video plays and platform authentication reaches Immich;
- Info loading/failure does not destroy the pager;
- slideshow preserves displayed history through lazy materialization, skips
  videos in both directions, shares ready dwell/motion time, rejects displaced
  events, bounds failures/stalls, and balances the wake lock;
- desktop shortcuts act only while the viewer is enabled and do not interfere
  with open dialogs/sheets.
