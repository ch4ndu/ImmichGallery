---
name: trip-test
description: Author and run focused tests following project standards
---

# Testing Mode

## Initialization Gate

Before any action, require `.agents/trip/initialized.json`. If it is absent, stop and invoke `trip-init`; do not author or run project tests through this workflow.

You are now in **testing mode** for **ImmichGallery**.

This skill is the **deep test-authoring reference**: the `trip-2-implement` testing gate points here for heavy authoring work and full guidance. Invoke it standalone for test backfill or coverage work outside an implementation session.

## Prerequisites - Read First

Before testing, you MUST read:

1. @docs/ARCHI.md - Understand system architecture
2. @docs/4-unit-tests/TESTING.md - Testing guidelines

## Your Task

Test: $ARGUMENTS

---

## Testing Guidelines

### Scope

- Only run tests for relevant files that changed (not the whole project)
- Focus on the new feature/fix/refactor

### Commands

```bash
# Run all tests
./gradlew :composeApp:jvmTest :composeApp:testAndroidHostTest

# Run a specific test
./gradlew :composeApp:jvmTest --tests fully.qualified.TestClass.testName

# Coverage
# No coverage task or numeric coverage threshold is currently configured.
```

### Test Structure

- `composeApp/src/commonTest` contains portable API-contract, repository, domain, Action, ViewModel, photo-grid, pagination, sync, Mosaic, and UI-helper tests.
- `composeApp/src/androidDeviceTest` contains the instrumented Room migration test.
- There are currently no project test files in `jvmTest`, `iosTest`, or `androidHostTest`; their platform tasks consume shared `commonTest` sources.
- Test files use the `*Test.kt` suffix and `kotlin.test.Test`; coroutine tests use `kotlinx-coroutines-test`.
- Put tests in the lowest portable source set. Use Android device tests only for behavior that requires the Android/Room device boundary.

### Testing Priorities

**Portable unit and contract tests**:

- API serialization and column-shape contracts, pagination tokens, filtering, mapping, and cancellation
- UseCase/Action behavior, ViewModel projections, stale generations, loading/error/empty/retry state, and no-op refresh behavior
- Row packing, Mosaic assignment/geometry/cache validation, display indexes, scroll/return targeting, and scheduler ordering
- Normal, invalid, empty, boundary, first/last-page, cancellation, retry, cache-hit, cache-miss, and partial-failure paths

**Persistence and integration tests**:

- Room migrations, transactions, owner relationships, sync metadata, cache replacement, and scoped artifact invalidation
- Immich request/response behavior against an explicitly authorized real server when Mock/local contract tests cannot prove compatibility

**Manual or platform verification**:

- Exact Compose user flows, adaptive layout, shared transitions, gestures, playback, and slideshow behavior
- Android local-network permission, foreground service, notification, lifecycle, and media authentication
- iOS local-network, lifecycle, image loading, AVPlayer original/transcode fallback, and Xcode-host behavior
- Desktop shortcuts, libVLC authentication, focus lifecycle, and native packages

Do not claim platform acceptance from common host tests. Device, simulator, real-server, and packaged-app checks require explicit authorization.

---

## Hard-to-Test Code

Seam ladder, cheapest first: **exported pure helper → injectable client/adapter → module mock → integration/emulator test**. Take the first rung that works; refactor for a seam only if the refactor is smaller than the feature you're shipping — otherwise it's coverage debt. Before refactoring legacy code, pin it with characterization tests (assert current behavior as-is, then refactor safely).

Uncovered risky paths: one line each in `docs/4-unit-tests/COVERAGE-DEBT.md` (`path | why hard | escape plan`). Delete a ledger line in the same change that gives its path meaningful coverage.

---

## Post-Testing Summary

After completing tests, create a summary file:

**File**: `docs/4-unit-tests/wa_vx.y.z_test.md`
(a = project week, x.y.z = version)

**Content**:

```markdown
# Test Summary - Week a, V. x.y.z

## What Was Tested

[List of tested components/functions]

## Test Results

- Total tests: X
- Passed: X
- Failed: X
- Coverage: X%

## Key Findings

[Any issues discovered, edge cases found, etc.]

## Notes

[Additional context or recommendations]
```
