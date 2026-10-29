#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=${IMMICH_GALLERY_PROJECT_ROOT:-"$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"}
GRADLEW=${IMMICH_GALLERY_GRADLEW:-"$PROJECT_ROOT/gradlew"}
cd "$PROJECT_ROOT"

# Archive can use Debug, and IDE build override must not bypass distribution policy.
if [ "${ACTION:-}" = "install" ] || [ "${CONFIGURATION:-}" = "Release" ]; then
  "$GRADLEW" :composeApp:verifyDistributionLoginDefaults
fi

if [ "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-}" = "YES" ] &&
   [ "${ACTION:-}" != "install" ] && [ "${CONFIGURATION:-}" != "Release" ]; then
  echo "Skipping Gradle build task invocation due to OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED environment variable set to \"YES\""
  exit 0
fi

# Distribution must rebuild/embed the verified inputs rather than reuse an IDE
# framework that may have been compiled earlier with development credentials.
"$GRADLEW" :composeApp:embedAndSignAppleFrameworkForXcode
