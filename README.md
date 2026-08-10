# ImmichGallery

ImmichGallery is a read-only gallery client for self-hosted
[Immich](https://immich.app/) servers. It is built with Kotlin Multiplatform and
Compose Multiplatform for Android, iOS, and JVM Desktop.

Current release: **1.1.0**.

The app browses and plays existing Immich media. It does not upload, edit,
favorite, delete, share, or download assets, and it does not change the library
on the server.

## Features

### Browse the library

- **Timeline** — browse photos and videos chronologically, group by month or
  day, use sticky date context and a date-aware fast scrollbar, and refresh the
  full Timeline from the server.
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
  pager page plays.
- Swipe down to dismiss, swipe up (or use the bottom handle) for information,
  tap to show or hide viewer controls, and return to the currently viewed asset
  even after paging.
- Open an information sheet for available filename, capture time, dimensions,
  size, people, location, camera/lens/exposure data, and description. Selecting
  a recognized person opens that person's gallery.
- Start a sequential or random slideshow, choose its duration, and enable or
  disable animated still-image motion.
- For an asset in an Immich stack, open **View stack** from the viewer menu to
  browse its supported photos and videos. Closing the nested stack returns to
  the same asset in the original viewer before returning to the source grid.
- When a memory is paged beyond its cover, closing the viewer still returns to
  that memory's cover card. A nested stack closes back to the memory pager
  first, preserving the cover-card return target.
- Desktop viewer shortcuts: Left/Right to page, Escape to close, Space to toggle
  slideshow, and `I` to open information.

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

The desktop packaging configuration also supports DMG, MSI, and DEB targets on
their applicable host platforms.

### iOS

Open `iosApp/iosApp.xcodeproj`, set the development team used by
`iosApp/Configuration/Config.xcconfig` when signing is required, and build the
`iosApp` scheme. The Xcode build phase compiles the Kotlin framework through
`iosApp/scripts/compile-kotlin-framework.sh`; it skips Gradle only when the
destination framework is newer than all relevant shared/Apple inputs.

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
