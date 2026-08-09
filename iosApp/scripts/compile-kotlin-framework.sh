#!/bin/sh

set -eu

if [ "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-}" = "YES" ]; then
  echo "Skipping Gradle build task invocation due to OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED environment variable set to \"YES\""
  exit 0
fi

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=${IMMICH_GALLERY_PROJECT_ROOT:-"$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"}

if [ -n "${IMMICH_GALLERY_FRAMEWORK_BINARY:-}" ]; then
  FRAMEWORK_BINARY=$IMMICH_GALLERY_FRAMEWORK_BINARY
else
  : "${CONFIGURATION:?Xcode CONFIGURATION is required}"
  : "${PLATFORM_NAME:?Xcode PLATFORM_NAME is required}"
  : "${SDK_VERSION:?Xcode SDK_VERSION is required}"
  FRAMEWORK_BINARY="$PROJECT_ROOT/composeApp/build/xcode-frameworks/$CONFIGURATION/$PLATFORM_NAME$SDK_VERSION/ComposeApp.framework/ComposeApp"
fi

framework_is_current() {
  [ -f "$FRAMEWORK_BINARY" ] || return 1

  for input in \
    "$PROJECT_ROOT/composeApp/src/commonMain" \
    "$PROJECT_ROOT/composeApp/src/nativeMain" \
    "$PROJECT_ROOT/composeApp/src/appleMain" \
    "$PROJECT_ROOT/composeApp/src/iosMain" \
    "$PROJECT_ROOT/composeApp/src/iosArm64Main" \
    "$PROJECT_ROOT/composeApp/src/iosSimulatorArm64Main" \
    "$PROJECT_ROOT/composeApp/build.gradle.kts" \
    "$PROJECT_ROOT/build.gradle.kts" \
    "$PROJECT_ROOT/settings.gradle.kts" \
    "$PROJECT_ROOT/gradle.properties" \
    "$PROJECT_ROOT/gradle/libs.versions.toml" \
    "$PROJECT_ROOT/gradle/wrapper" \
    "$PROJECT_ROOT/gradlew" \
    "$PROJECT_ROOT/local.properties"
  do
    if [ -e "$input" ] && find "$input" -newer "$FRAMEWORK_BINARY" -print -quit | grep -q .; then
      return 1
    fi
  done

  return 0
}

if framework_is_current; then
  echo "Kotlin framework is current; skipping Gradle invocation"
  exit 0
fi

GRADLEW=${IMMICH_GALLERY_GRADLEW:-"$PROJECT_ROOT/gradlew"}
cd "$PROJECT_ROOT"
"$GRADLEW" :composeApp:embedAndSignAppleFrameworkForXcode
