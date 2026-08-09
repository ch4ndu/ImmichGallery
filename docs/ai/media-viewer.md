# Photo And Video Viewer Architecture

Load this when changing photo/video detail viewing, overlay paging, shared
transitions, expanded asset information, slideshow, drag dismissal, platform
video playback, or desktop viewer shortcuts.

## Viewer Owners

- `PhotoOverlayHost` owns open/dismiss state, shared-transition lifecycle,
  transition identity, and the hidden grid asset for Search, Album Detail, and
  Person Detail.
- `StaticPhotoOverlay` pages an already materialized ordered asset list for
  those screens.
- Timeline uses `TimelinePhotoOverlay` because its pager spans lazy buckets.
  `TimelineOverlayCoordinator` owns the API key, expanded asset/file-name
  lookups, projected overlay state, and atomic asset/bucket return target.
- `AssetPage` is the shared still/video/slideshow media surface.
- `AssetDetailSheet` fetches and renders expanded asset information.
- `SlideshowOptionsDialog` edits `SlideshowConfig` before playback starts.
- `PlatformVideoPlayer` is expect/actual platform UI.

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
  display index and scrolls only when the source is fully off-screen. Timeline
  commits asset id and bucket together through `TimelineOverlayCoordinator`.
- `lastSelectedAssetId`/host-retained content keeps the exit destination alive
  until the shared transition completes.

## Still Images

- Grid/detail thumbnails use a bounded 256 x 256 exact Coil request with stable
  memory/disk cache keys.
- Still pages request the edited original URL and use the matching thumbnail as
  the transition/loading source.
- `CoilZoomAsyncImage` owns pinch/pan zoom. Once zoomed, the media zoom gesture
  takes precedence over drag dismissal.
- A tap toggles viewer chrome when the detail sheet and slideshow are inactive.
- The fitted media container owns drag transforms so the touched media point
  remains anchored during dismissal; snap-back restores identity only when the
  dismiss is cancelled.

## Video

- Only the settled pager page is playing; off-screen pages pause.
- Android sends the API key as an HTTP header to the shared media-player host.
- JVM appends Immich's supported `apiKey` query parameter because the desktop
  libVLC path does not reliably forward custom headers.
- iOS tries the authenticated original file first, then falls back to Immich's
  authenticated playback/transcode URL when AVPlayer fails or stalls.
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
  and re-checks active slideshow/page state after suspension.
- Still images use `KenBurnsImage` when random animations are enabled. Video
  pages use the asset thumbnail while slideshow is active rather than starting
  video playback.
- `ScreenWakeLock` stays acquired only while slideshow is active and is released
  on exit/disposal.
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
- a fully off-screen return source is made visible before exit;
- still-image zoom prevents accidental drag dismissal;
- only the settled video plays and platform authentication reaches Immich;
- Info loading/failure does not destroy the pager;
- slideshow stops cleanly, wraps/chooses pages according to config, and releases
  the wake lock;
- desktop shortcuts act only while the viewer is enabled and do not interfere
  with open dialogs/sheets.
