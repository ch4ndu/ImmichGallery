#!/bin/sh

set -eu

if [ "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-}" = "YES" ]; then
  echo "Skipping Gradle build task invocation due to OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED environment variable set to \"YES\""
  exit 0
fi

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=${IMMICH_GALLERY_PROJECT_ROOT:-"$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"}
GRADLEW=${IMMICH_GALLERY_GRADLEW:-"$PROJECT_ROOT/gradlew"}
cd "$PROJECT_ROOT"
"$GRADLEW" :composeApp:embedAndSignAppleFrameworkForXcode
