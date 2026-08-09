#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
HELPER="$SCRIPT_DIR/../compile-kotlin-framework.sh"
FIXTURE_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/immichgallery-xcode-test.XXXXXX")
trap 'rm -rf "$FIXTURE_ROOT"' EXIT INT TERM

PASSED=0

new_fixture() {
  name=$1
  TEST_ROOT="$FIXTURE_ROOT/$name"
  MARKER="$TEST_ROOT/gradle-called"
  FRAMEWORK="$TEST_ROOT/composeApp/build/xcode-frameworks/Debug/iphonesimulator1.0/ComposeApp.framework/ComposeApp"

  mkdir -p \
    "$TEST_ROOT/composeApp/src/commonMain/kotlin" \
    "$TEST_ROOT/composeApp/src/iosMain/kotlin" \
    "$TEST_ROOT/composeApp/build/xcode-frameworks/Debug/iphonesimulator1.0/ComposeApp.framework" \
    "$TEST_ROOT/gradle/wrapper" \
    "$TEST_ROOT/gradle"
  touch \
    "$TEST_ROOT/composeApp/src/commonMain/kotlin/Common.kt" \
    "$TEST_ROOT/composeApp/src/iosMain/kotlin/Ios.kt" \
    "$TEST_ROOT/composeApp/build.gradle.kts" \
    "$TEST_ROOT/build.gradle.kts" \
    "$TEST_ROOT/settings.gradle.kts" \
    "$TEST_ROOT/gradle.properties" \
    "$TEST_ROOT/gradle/libs.versions.toml" \
    "$TEST_ROOT/gradle/wrapper/gradle-wrapper.properties" \
    "$TEST_ROOT/gradlew" \
    "$FRAMEWORK"
  find "$TEST_ROOT" -exec touch -t 202001010000 {} +
  touch -t 202001010100 "$FRAMEWORK"

  printf '%s\n' '#!/bin/sh' ': > "$IMMICH_GALLERY_GRADLE_MARKER"' > "$TEST_ROOT/gradlew"
  chmod +x "$TEST_ROOT/gradlew"
  touch -t 202001010000 "$TEST_ROOT/gradlew"
}

run_helper() {
  IMMICH_GALLERY_PROJECT_ROOT="$TEST_ROOT" \
    IMMICH_GALLERY_FRAMEWORK_BINARY="$FRAMEWORK" \
    IMMICH_GALLERY_GRADLE_MARKER="$MARKER" \
    "$HELPER" >/dev/null
}

expect_skipped() {
  test_name=$1
  run_helper
  if [ -e "$MARKER" ]; then
    echo "FAIL: $test_name invoked Gradle"
    exit 1
  fi
  PASSED=$((PASSED + 1))
}

expect_gradle() {
  test_name=$1
  run_helper
  if [ ! -e "$MARKER" ]; then
    echo "FAIL: $test_name skipped Gradle"
    exit 1
  fi
  PASSED=$((PASSED + 1))
}

new_fixture unchanged
expect_skipped "unchanged inputs"

new_fixture edited_source
touch "$TEST_ROOT/composeApp/src/commonMain/kotlin/Common.kt"
expect_gradle "edited source"

new_fixture added_source
touch "$TEST_ROOT/composeApp/src/commonMain/kotlin/Added.kt"
expect_gradle "added source"

new_fixture deleted_source
rm "$TEST_ROOT/composeApp/src/commonMain/kotlin/Common.kt"
expect_gradle "deleted source"

new_fixture renamed_source
mv "$TEST_ROOT/composeApp/src/iosMain/kotlin/Ios.kt" "$TEST_ROOT/composeApp/src/iosMain/kotlin/Renamed.kt"
expect_gradle "renamed source"

new_fixture changed_resource
mkdir -p "$TEST_ROOT/composeApp/src/commonMain/composeResources/values"
touch "$TEST_ROOT/composeApp/src/commonMain/composeResources/values/strings.xml"
expect_gradle "changed Compose resource"

new_fixture changed_root_build
touch "$TEST_ROOT/build.gradle.kts"
expect_gradle "changed root build configuration"

new_fixture changed_catalog
touch "$TEST_ROOT/gradle/libs.versions.toml"
expect_gradle "changed version catalog"

new_fixture changed_local_properties
touch "$TEST_ROOT/local.properties"
expect_gradle "changed local properties input"

new_fixture missing_framework
rm "$FRAMEWORK"
expect_gradle "missing framework"

new_fixture ide_override
OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES run_helper
if [ -e "$MARKER" ]; then
  echo "FAIL: IDE override invoked Gradle"
  exit 1
fi
PASSED=$((PASSED + 1))

echo "Passed $PASSED Kotlin framework freshness checks"
