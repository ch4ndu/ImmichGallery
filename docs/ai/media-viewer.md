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
  fragment components, and redacts its diagnostic representation. JVM uses
  query authentication because libVLC does not reliably forward custom
  headers; iOS uses it instead of private AVFoundation header options.
- iOS tries the authenticated original file first, then falls back to Immich's
  authenticated playback/transcode URL when AVPlayer fails or stalls. The
  bounded fallback observer exists only while that pager page is current;
  leaving cancels it and returning starts a fresh window while the original is
  still selected.
- iOS Picture in Picture is disabled. Enabling it requires a separate contract
  for AVAudioSession and host background-media capability.
- Desktop startup probes a validated packaged libVLC bundle before any player
  class initializes. Native packages require that builder-supplied bundle and
  fail closed when it is absent or invalid. A Gradle development run may use an
  installed VLC when no bundle is supplied. The viewer shows a resource-backed
  recovery message when VLC or the authenticated media URL is unavailable.
- Android and JVM wrap the native player in a final-pass pointer observer. It
  never consumes pointer changes and reports only a single-pointer gesture that
  stays within touch slop, so drags and multi-touch remain player-owned. The
  pinned Chaintech player consumes both its surface and control taps without
  exposing which child handled them; consequently a native-control tap can also
  toggle app chrome. Distinguishing those taps requires replacing or forking
  the player controls and is not part of this remediation.
- The video URL and original URL are server-derived edited variants. Video
  controls are platform-player controls rather than custom domain state.

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

## Slideshow

- Slideshow is offered only when the viewer has more than one page.
- `SlideshowConfig` supports sequential or random order, a user-entered duration
  in seconds, and animations off or random.
- Sequential order wraps. Random order chooses a page other than the current one.
- The timer restarts after each settled page, including a manual desktop advance,
  and re-checks active slideshow configuration, settled page, page count, and
  stack coverage after suspension before advancing.
- Still images use `KenBurnsImage` when random animations are enabled. Video
  pages use the asset thumbnail while slideshow is active rather than starting
  video playback.
- `ScreenWakeLock` stays acquired only while slideshow is active and is released
  on exit/disposal. JVM uses one balanced process-wide owner: Windows performs
  acquire/reset on the same dedicated JNA thread, macOS owns a `caffeinate`
  process, and Linux owns a `systemd-inhibit` process when available. Missing
  native commands are a safe unsupported fallback, and application exit closes
  only the process/thread owned by ImmichGallery.
- A tap during slideshow stops it and restores the top bar.

## Input And Chrome

- Mobile/tablet supports horizontal paging, still-image pinch/pan, tap-to-toggle
  chrome, downward drag dismissal, and an upward drag or bottom handle for Info.
- JVM additionally supports Left/Right page navigation, Escape dismiss, Space
  slideshow toggle, and `I` for Info.
- Grid density uses pinch zoom on touch platforms and Ctrl/Cmd-wheel on JVM;
  that is separate from full-image zoom inside the viewer.

## Verification Targets

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
- slideshow stops cleanly, wraps/chooses pages according to config, revalidates
  state after the delay, and releases the wake lock;
- desktop shortcuts act only while the viewer is enabled and do not interfere
  with open dialogs/sheets.
