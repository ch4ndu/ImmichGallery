# Chaintech's media-player artifact retains an optional YouTube path whose
# WebView dependency is not packaged by ImmichGallery. Immich-generated media
# URLs cannot enter that unsupported path, so suppress its missing-type checks.
-dontwarn com.multiplatform.webview.**
