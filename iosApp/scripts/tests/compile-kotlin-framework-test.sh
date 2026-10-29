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

  mkdir -p \
    "$TEST_ROOT/composeApp/src/commonMain/kotlin" \
    "$TEST_ROOT/composeApp/src/iosMain/kotlin" \
    "$TEST_ROOT/gradle/wrapper" \
    "$TEST_ROOT/gradle"
  TEST_ROOT=$(CDPATH= cd -- "$TEST_ROOT" && pwd)
  MARKER="$TEST_ROOT/gradle-called"
  touch \
    "$TEST_ROOT/composeApp/src/commonMain/kotlin/Common.kt" \
    "$TEST_ROOT/composeApp/src/iosMain/kotlin/Ios.kt" \
    "$TEST_ROOT/composeApp/build.gradle.kts" \
    "$TEST_ROOT/build.gradle.kts" \
    "$TEST_ROOT/settings.gradle.kts" \
    "$TEST_ROOT/gradle.properties" \
    "$TEST_ROOT/gradle/libs.versions.toml" \
    "$TEST_ROOT/gradle/wrapper/gradle-wrapper.properties" \
    "$TEST_ROOT/local.properties"

  printf '%s\n' \
    '#!/bin/sh' \
    '[ "$#" -eq 1 ] || exit 91' \
    'case "$1" in :composeApp:embedAndSignAppleFrameworkForXcode|:composeApp:verifyDistributionLoginDefaults) ;; *) exit 91 ;; esac' \
    'printf "%s\n" "$PWD|$1|${IMMICH_GALLERY_LOGIN_SERVER_URL:-}|${IMMICH_GALLERY_LOGIN_API_KEY:-}" >> "$IMMICH_GALLERY_GRADLE_MARKER"' \
    'if [ "$1" = ":composeApp:verifyDistributionLoginDefaults" ] && [ "${TEST_BLOCK_DISTRIBUTION:-}" = YES ]; then exit 92; fi' \
    > "$TEST_ROOT/gradlew"
  chmod +x "$TEST_ROOT/gradlew"
}

run_helper() {
  server_url=${1:-}
  api_key=${2:-}
  rm -f "$MARKER"
  IMMICH_GALLERY_PROJECT_ROOT="$TEST_ROOT" \
    IMMICH_GALLERY_GRADLE_MARKER="$MARKER" \
    IMMICH_GALLERY_LOGIN_SERVER_URL="$server_url" \
    IMMICH_GALLERY_LOGIN_API_KEY="$api_key" \
    "$HELPER" >/dev/null
}

expect_gradle() {
  test_name=$1
  server_url=${2:-}
  api_key=${3:-}
  run_helper "$server_url" "$api_key"
  if [ ! -e "$MARKER" ]; then
    echo "FAIL: $test_name skipped Gradle"
    exit 1
  fi

  expected="$TEST_ROOT|:composeApp:embedAndSignAppleFrameworkForXcode|$server_url|$api_key"
  actual=$(cat "$MARKER")
  if [ "$actual" != "$expected" ]; then
    echo "FAIL: $test_name invoked Gradle with unexpected task, directory, or login inputs"
    exit 1
  fi
  PASSED=$((PASSED + 1))
}

new_fixture unchanged
expect_gradle "unchanged inputs"
expect_gradle "repeated unchanged inputs"

new_fixture deleted_local_properties
rm "$TEST_ROOT/local.properties"
expect_gradle "deleted local properties input"

new_fixture changed_root_build
printf '%s\n' '// changed' >> "$TEST_ROOT/build.gradle.kts"
expect_gradle "changed root build configuration"

new_fixture changed_compose_build
printf '%s\n' '// changed' >> "$TEST_ROOT/composeApp/build.gradle.kts"
expect_gradle "changed Compose build configuration"

new_fixture changed_catalog
printf '%s\n' '# changed' >> "$TEST_ROOT/gradle/libs.versions.toml"
expect_gradle "changed version catalog"

new_fixture changed_login_server
expect_gradle "changed login server environment input" "https://photos.example.test/path" "first-key"
expect_gradle "changed login server environment input again" "https://other.example.test/path" "first-key"

new_fixture changed_login_key
expect_gradle "changed login key environment input" "https://photos.example.test/path" "first-key"
expect_gradle "changed login key environment input again" "https://photos.example.test/path" "second-key"

new_fixture ide_override
rm -f "$MARKER"
OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES \
  IMMICH_GALLERY_PROJECT_ROOT="$TEST_ROOT" \
  IMMICH_GALLERY_GRADLE_MARKER="$MARKER" \
  "$HELPER" >/dev/null
if [ -e "$MARKER" ]; then
  echo "FAIL: IDE override invoked Gradle"
  exit 1
fi
PASSED=$((PASSED + 1))

for configuration in Release Debug; do
  new_fixture "archive_$configuration"
  CONFIGURATION="$configuration" ACTION=install OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES \
    IMMICH_GALLERY_PROJECT_ROOT="$TEST_ROOT" IMMICH_GALLERY_GRADLE_MARKER="$MARKER" \
    "$HELPER" >/dev/null
  expected=$(printf '%s\n' \
    "$TEST_ROOT|:composeApp:verifyDistributionLoginDefaults||" \
    "$TEST_ROOT|:composeApp:embedAndSignAppleFrameworkForXcode||")
  [ "$(cat "$MARKER")" = "$expected" ] || { echo "FAIL: archive guard ordering"; exit 1; }
  PASSED=$((PASSED + 1))
done

new_fixture release_override
CONFIGURATION=Release OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES \
  IMMICH_GALLERY_PROJECT_ROOT="$TEST_ROOT" IMMICH_GALLERY_GRADLE_MARKER="$MARKER" \
  "$HELPER" >/dev/null
expected=$(printf '%s\n' \
  "$TEST_ROOT|:composeApp:verifyDistributionLoginDefaults||" \
  "$TEST_ROOT|:composeApp:embedAndSignAppleFrameworkForXcode||")
[ "$(cat "$MARKER")" = "$expected" ] || {
  echo "FAIL: IDE override bypassed verified distribution rebuild"; exit 1;
}
PASSED=$((PASSED + 1))

new_fixture blocked_archive
if ACTION=install TEST_BLOCK_DISTRIBUTION=YES \
  IMMICH_GALLERY_PROJECT_ROOT="$TEST_ROOT" IMMICH_GALLERY_GRADLE_MARKER="$MARKER" \
  "$HELPER" >/dev/null; then
  echo "FAIL: blocked archive succeeded"; exit 1
fi
[ "$(cat "$MARKER")" = "$TEST_ROOT|:composeApp:verifyDistributionLoginDefaults||" ] || {
  echo "FAIL: blocked archive invoked embedding"; exit 1;
}
PASSED=$((PASSED + 1))

echo "Passed $PASSED Kotlin framework invocation checks"
