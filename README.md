# ImmichGallery

A read-only gallery app for self-hosted [Immich](https://immich.app/) photo servers, built with Kotlin Multiplatform + Compose Multiplatform.

**Platforms:** Android · iOS · Desktop (JVM)

## Features

### Browse & Navigate
- **Timeline** — photos organized chronologically by month with sticky headers
- **Albums** — browse all albums with cover thumbnails and item counts; tap to view contents
- **People** — face-recognized people displayed as circular avatars; tap to view all photos of a person
- **Search** — smart (AI-powered semantic) and metadata (filename) search with toggle between modes

### Media Viewing
- Full-screen photo viewer with horizontal swipe navigation
- Pinch-to-zoom on images
- Video playback

### UI & Interaction
- 4-tab bottom navigation (Timeline, Albums, People, Search)
- Interactive fast-scroll scrollbar with date label bubble
- Material 3 theming with light/dark mode support
- Ege-to-Edge support

### Connection & Data
- API key authentication to self-hosted Immich servers
- Local caching via Room database for offline browsing
- Authenticated image loading via Coil + Ktor
- Efficient pagination with Paging 3

### Multi-Platform
- Android (minSdk 24)
- iOS (arm64 + simulator)
- Desktop / JVM

## Build & Run

### Android
```shell
./gradlew :composeApp:assembleDebug
```

### Desktop (JVM)
```shell
./gradlew :composeApp:run
```

### iOS
Open `iosApp/iosApp.xcodeproj` in Xcode and build the `iosApp` scheme.

## Tech Stack

Kotlin 2.3.0 · Compose Multiplatform 1.10.0 · Ktor 3 · Coil 3 · Room 2.8.4 · Paging 3 · Koin · kotlinx-datetime
