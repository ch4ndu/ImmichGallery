# ImmichGallery

ImmichGallery is a read-only gallery client for self-hosted
[Immich](https://immich.app/) servers. It is built with Kotlin Multiplatform and
Compose Multiplatform for Android, iOS, and JVM Desktop.

Current release: **1.2.0**.

The app browses and plays existing Immich media. It does not upload, edit,
favorite, delete, share, or download assets, and it does not change the library
on the server.

## Features

### Browse the library

- **Timeline** — browse photos and videos chronologically, group by month or
  day, use sticky date context and a date-aware fast scrollbar, and refresh the
  full Timeline from the server. Use **Jump to date** in the Timeline menu to
  select a calendar date, or **Back to today** to return to recent photos.
  Dates use the closest photo in the selected month, with the nearest available
  month used when that month has no photos.
- **Albums** — browse album cover thumbnails, names, and item counts, then open
  a cached-first paginated photo grid in the album's server-defined order.
- **People** — browse face-recognized people, filter names locally, and open a
  cached-first paginated grid for a person.
- **Explore** — combine Context, Filename, Description, or OCR text with exact
  People, Albums, Tags, camera, location, date, media, Motion Photo, Favorites,
  album-membership, visibility, and rating filters. Filter-only Search is
  supported, while the blank state retains static Favorites and Memories entry
  cards. Results keep server pagination and an explicit **Load more** retry.
- **Favorites** — open a live, owner-filtered collection of the authenticated
  user's favorite photos and videos. Sparse partner-only pages are skipped,
  later-page failures remain explicitly retryable, and results are not cached
  in Room.
- **Memories** — open the current local date's live Immich **On this day**
  memories as year-labelled cover cards. Each memory keeps its server asset
  order and supported item count; the viewer uses width-aware animated page dots
  that follow every swipe and segmented progress when the full dot row cannot
  fit. Multiple memories from one year stay separate.
- **Supported assets** — image and video assets are shown. Audio and other
  unsupported asset types are filtered out.

### Grid layouts

- Standard justified rows preserve source order and adapt to the window width.
- Optional Mosaic layouts are available on Timeline, Album Detail, and Person
  Detail. The settings include layout density, 4- through 8-tile template
  families, persistent result caching, and a zoom-disable option.
- Explore Search and Favorites always use justified rows. Memories uses cover
  cards. Album and People collection lists stay fixed collection grids, and
  none of these collection surfaces expose Mosaic controls.
- Pinch zoom changes justified-row density on touch platforms; Ctrl/Cmd-wheel
  does the same on Desktop. Row density is remembered separately for Timeline,
  Album Detail, Person Detail, Explore Search, and Favorites.
- Applying Mosaic settings with **Cache results** enabled prepares the complete
  applicable cache before the setting takes effect. This can temporarily block
  that screen, and a failure keeps the previous layout.

### View photos and videos

- Open any visible asset in a full-screen horizontal pager with a shared
  grid-to-viewer transition.
- Pinch and pan full-resolution still images.
- Play authenticated video through the platform media player; only the settled
  pager page plays. Shared play/pause, seek, time, mute, ten-second skip and
  playback-speed controls hide with the top bar and thumbnail row after three
  seconds of inactivity. Drag vertically on the video's right side for volume
  or left side for brightness; indicators appear only during adjustment.
  Android/iOS restore screen brightness on leaving active viewing; desktop
  dims only the video. Photos keep tap-to-toggle controls. Track and route
  menus are not exposed.
- Swipe down to dismiss, swipe up (or use the bottom handle) for information,
  tap to show or hide viewer controls, and return to the currently viewed asset
  even after paging.
- Open an information sheet for available filename, capture time, dimensions,
  size, people, location, camera/lens/exposure data, and description. Selecting
  a recognized person opens that person's gallery. Open valid coordinates in
  Maps, copy them, or choose **Show in Timeline** to jump to the capture date.
- Toggle a thumbnail filmstrip to jump between photos while retaining the
  current position. Timeline thumbnails load lazily as the strip scrolls.
- Start a sequential or nonrepeating shuffled slideshow, pause/resume, and
  revisit displayed slides with Previous/Next. Choose the duration, Cut/Fade/
  Slide transition, Still/Pan-and-zoom motion, and optional captions. Timing
  waits for media readiness and pauses while information is open.
- Choose whether slideshows skip videos, show their thumbnails, or play them
  with mute and an optional duration limit. Settings are remembered on this
  device across logout; playback position and history last only for the session.
- On desktop, toggle window fullscreen from the viewer or have slideshows
  enter it automatically, restoring the previous placement when they finish.
- For an asset in an Immich stack, open **View stack** from the viewer menu to
  browse its supported photos and videos. Closing the nested stack returns to
  the same asset in the original viewer before returning to the source grid.
- When a memory is paged beyond its cover, closing the viewer still returns to
  that memory's cover card. A nested stack closes back to the memory pager
  first, preserving the cover-card return target.
- Desktop viewer shortcuts: Left/Right to page or traverse slideshow history,
  Space to open slideshow options or pause/resume, `I` for information, and
  `F` for fullscreen. Escape stops a slideshow, then exits fullscreen, then
  closes the viewer on subsequent presses.

### Connection, cache, and refresh behavior

- Connect with an Immich server URL and API key. The URL and key are saved on
  the device after validation and reused at startup.
- Authenticated image loading uses OkHttp on Android and Ktor on iOS/Desktop.
- Timeline, Album, People, opened Album/Person relationships, sync metadata, and
  optional Mosaic artifacts use a local Room cache. Cached screens remain
  visible when a background refresh fails.
- Explore Search results, capabilities, preferences, Tags, and camera/location
  suggestions are destination-lifetime live data and are not stored as
  standalone Room records. Search People and Album selectors reuse their
  existing Room caches. Favorites, current-date Memories, expanded asset
  information, and viewer stack members are also live server requests;
  Explore's entry cards do not prefetch either collection.
- A first Timeline sync is blocking. After a complete cache exists, startup is
  cached-first; the current Timeline policy waits for the explicit top-bar
  refresh before contacting the server again for Timeline changes.
- Album and People lists, plus opened Album/Person details, refresh from the
  server while retaining cached content when available.
- The top bar shows periodic server reachability, offers refresh on applicable
  tabs, and provides logout. Logout clears server-scoped Room data before
  clearing the saved server configuration.
- Android API 37+ asks for local-network access when the configured endpoint is
  local. A denied saved session returns to Login with its values preserved and
  offers an app-settings recovery action.

## Requirements

- A reachable self-hosted Immich server and an API key with permission to read
  the intended library.
- Android 7.0 / API 24 or newer; the Android app targets API 37.
- iOS/iPadOS 18.2 or newer for the current Xcode project.
- A Gradle-compatible JDK and the platform toolchain required by the target you
  are building.

HTTP can be used for a trusted local server: Android permits cleartext traffic,
and iOS permits local-network HTTP through `NSAllowsLocalNetworking`. Prefer
HTTPS for servers reached outside a trusted LAN.

## Connect to Immich

1. Create or copy an API key from your Immich account settings.
2. Enter the full server origin, such as `https://photos.example.com` or a local
   server URL, without an `/api` suffix.
3. Enter the API key and choose **Connect**. ImmichGallery validates both the
   server and key before saving them.

For local development only, the Login fields can be prefilled from the
gitignored root `local.properties` file:

```properties
immichGallery.loginServerUrl=https://photos.example.com
immichGallery.loginApiKey=your-api-key
```

These values are build inputs and do not bypass connection validation. Do not
commit real credentials.

## Build and run

### Android

Build the installable debug application:

```shell
./gradlew :androidApp:assembleDebug
```

The APK is produced under `androidApp/build/outputs/apk/debug/`.

### Desktop (JVM)

Run directly with Gradle:

```shell
./gradlew :composeApp:run
```

Development runs use a staged VLC bundle when one is supplied and otherwise
may use an installed VLC. Native application images and DMG, MSI, or DEB
packages require a builder-supplied VLC bundle through either
`-PimmichGallery.vlcBundleDir=/absolute/path/to/vlc-root` or the
`IMMICH_GALLERY_VLC_BUNDLE_DIR` environment variable. The root contains a
directory matching the build host (`macos-arm64`, `macos-x64`,
`windows-arm64`, `windows-x64`, `linux-arm64`, or `linux-x64`):

```text
<vlc-root>/
  macos-arm64/
    lib/
      libvlc.dylib
      libvlccore.dylib
    plugins/
      .../*.dylib

  windows-x64/
    libvlc.dll
    libvlccore.dll
    plugins/
      .../*.dll

  linux-x64/
    libvlc.so*
    libvlccore.so*
    plugins/                 # vlc/plugins/ is also accepted
      .../*.so*
```

For example, create a self-contained application image on the current host:

```shell
./gradlew :composeApp:createDistributable \
  -PimmichGallery.vlcBundleDir=/absolute/path/to/vlc-root
```

The build validates the current target's core libraries and plugin layout and
copies only that target into generated application resources. VLC binaries are
not tracked by this repository and the build never downloads them.

### iOS

Open `iosApp/iosApp.xcodeproj` and build the `iosApp` scheme. The project keeps
a stable bundle identifier but does not commit an Apple Developer team or code
signing identity. For a signed device build or local archive, copy the example
configuration and replace its placeholder with your team identifier:

```shell
cp iosApp/Configuration/Local.xcconfig.example iosApp/Configuration/Local.xcconfig
```

`Local.xcconfig` is gitignored and optionally included by the shared
`Config.xcconfig`. Xcode automatic signing selects the appropriate local
identity from `TEAM_ID`; builds that do not need a development team can leave
it blank. If the shared bundle identifier is not available to your developer
team, uncomment `PRODUCT_BUNDLE_IDENTIFIER` in the local configuration and set
it to a globally unique reverse-DNS identifier that you control.

The Xcode build phase invokes
`:composeApp:embedAndSignAppleFrameworkForXcode` through
`iosApp/scripts/compile-kotlin-framework.sh` on every build unless Xcode's
existing Kotlin IDE override is active. Gradle's declared inputs and up-to-date
checks decide whether work is needed, including changes to local login-prefill
properties or `IMMICH_GALLERY_LOGIN_*` environment inputs.

Any signed archive produced this way is a builder-owned source-build artifact.
This repository does not supply distribution certificates, provisioning
profiles, App Store signing, or a release-distribution workflow.

After command-line Gradle work, stop the daemon with:

```shell
./gradlew --stop
```

## Architecture

The shared app follows a data -> domain -> ViewModel -> Compose dependency
direction:

```text
Immich API / Room / settings
  -> repositories
  -> read UseCases and write/side-effect Actions
  -> screen ViewModels and layout coordinators
  -> Compose screens and shared components
```

- `composeApp` contains shared KMP code, platform actuals, Room, resources, and
  the Desktop application. It is an Android KMP library, not the installable
  Android application.
- `androidApp` is the thin Android application/launcher module and depends on
  `composeApp`.
- `iosApp` is the Swift/Xcode host for the static Kotlin framework.
- Room is a re-fetchable cache. Server URL, API key, Timeline grouping, Mosaic
  configuration, and per-screen row density use `multiplatform-settings`.
- Navigation uses serializable type-safe routes. The Main shell owns the four
  Timeline, Albums, People, and Explore tabs; Album, Person, Favorites, and
  Memories destinations sit above that shell.
- Photo grids are projected before Compose: standard rows use `packIntoRows`,
  and Mosaic uses the shared `MosaicRenderEngine` plus screen-specific runtime
  scheduling and validated artifacts.

Canonical architecture references:

- [System architecture](docs/ARCHI.md)
- [Architecture and feature map](docs/ai/architecture.md)
- [Authentication, startup, and session](docs/ai/auth-session.md)
- [Collection lists and Search](docs/ai/collection-lists-search.md)
- [Photo and video viewer](docs/ai/media-viewer.md)
- [Timeline rendering](docs/timeline.md) and [Timeline sync](docs/ai/timeline-sync.md)
- [Album and Person detail](docs/ai/album-person-detail.md)
- [Standard justified rows](docs/ai/row-packing.md)
- [Mosaic overview](docs/ai/mosaic-rendering.md), [assignment](docs/ai/mosaic-assignment.md), and [runtime](docs/ai/mosaic-runtime.md)
- [Data, cache, API, and time](docs/ai/data-cache-time.md)
- [Testing guidelines](docs/4-unit-tests/TESTING.md)

## Technology

Kotlin 2.4.10 · Compose Multiplatform 1.11.1 · Material 3 · Ktor 3.5.1 ·
Coil 3.5.0 · Room 2.8.4 / SQLite 2.7.0 · Koin 4.2.2 ·
kotlinx-datetime 0.8.0
