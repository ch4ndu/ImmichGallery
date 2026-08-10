# Code Review: Memory Page Indicator

**Review Date**: 2026-08-09
**Version**: 1.1.0
**Files Reviewed**:

- `composeApp/src/commonMain/kotlin/com/udnahc/immichgallery/ui/component/StaticPhotoOverlay.kt`
- `composeApp/src/commonMain/kotlin/com/udnahc/immichgallery/ui/theme/Dimensions.kt`
- `composeApp/src/commonTest/kotlin/com/udnahc/immichgallery/ui/component/StaticPhotoOverlayTest.kt`
- `docs/ai/media-viewer.md`

**Plan**: no plan — unplanned change

---

## Executive Summary

The Memories viewer's capped seven-dot window was replaced with a width-aware
indicator that shows one continuously morphing fixed-slot dot per asset whenever
the complete row fits, including exactly 20 assets in a 320dp viewport, and
otherwise renders continuously advancing full-width segmented progress. The
review found one confirmed Minor pixel-rounding defect; it was accepted, fixed,
covered at mdpi and 1.25x density, and followed by a provenance-only continuation
that resolved the final ownership-evidence blocker without changing repository
bytes.

APPROVED

---

## Changes Overview

`StaticPhotoOverlay.kt` now derives visual position from
`PagerState.currentPage + currentPageOffsetFraction`, transfers size and opacity
continuously between adjacent dots, preserves settled-page accessibility
semantics, and selects between complete dots and segmented progress using the
actual measured pixel width. `Dimensions.kt` supplies the two shared dimensions
used by the segmented fallback, while `StaticPhotoOverlayTest.kt` covers fit
boundaries, fractional-density rounding, middle-page interpolation, segmented
progress, and invalid inputs.

The change remains in `commonMain`, preserves the Memories viewer's
`ORIGIN_ASSET` return behavior, keeps the indicator independent of filename
chrome, and leaves detail and nested-stack overlays composed above it.
`docs/ai/media-viewer.md` records the durable behavior and verification target.

Persisted model and effort evidence verifies the complete review chain:

- Implementation start and provenance continuation: `gpt-5.6-sol`, `high`;
  continuation ownership includes all four changed paths.
- Consolidated review and review response: `gpt-5.6-sol`, `xhigh`, read-only.
- Accepted-fix wave: `gpt-5.6-sol`, `xhigh`, scoped to the component and focused
  test.
- Initial completion gate: `gpt-5.6-sol`, `high`, read-only; returned
  `REQUEST_CHANGES` solely for incomplete `Dimensions.kt` ownership provenance.
- Fresh completion gate after the provenance continuation: `gpt-5.6-sol`,
  `high`, read-only; returned `APPROVED` and cleared the promotion gate.

---

## Findings

### Critical Issues

None.

### Major Issues

None.

### Minor Issues

**Density-unaware dot-row fit predicate — addressed.**
`composeApp/src/commonMain/kotlin/com/udnahc/immichgallery/ui/component/StaticPhotoOverlay.kt:71`

The original width-aware implementation compared an aggregate logical-DP width
with the available logical width. Compose measures every 10dp slot, 4dp gap, and
8dp padding edge independently in pixels, so fractional-density rounding could
select a dot row that did not actually fit. At 1.25x density, the former
22-dot/320dp boundary was classified as fitting even though the measured row
required 411px in a 400px viewport.

The response accepted the finding without override. The final implementation
passes `constraints.maxWidth` and the active `Density` into `allPageDotsFit`,
independently converts each repeated dimension with `roundToPx()`, and uses
subtraction-based `Long` arithmetic before selecting the rendering branch at
`StaticPhotoOverlay.kt:510`. Focused regression coverage at
`composeApp/src/commonTest/kotlin/com/udnahc/immichgallery/ui/component/StaticPhotoOverlayTest.kt:11`
confirms:

- 20 and 22 assets fit at 320px/mdpi, while 23 do not.
- 20 assets fit at 400px/1.25x density.
- 22 assets do not fit at 400px or 410px/1.25x density.
- 22 assets fit at the exact 411px measured boundary.

The required 20-asset case measures 292px at mdpi and 375px at 1.25x density,
so it fits the corresponding 320dp viewport at both tested densities. Final
disposition: **addressed; no open code finding remains**.

**Incomplete ownership provenance for shared progress dimensions — addressed
review-gate blocker; not an additional code defect.**
`composeApp/src/commonMain/kotlin/com/udnahc/immichgallery/ui/theme/Dimensions.kt:54`

The first completion gate confirmed the implementation's functional correctness
but returned `REQUEST_CHANGES` because the initial implementation metadata owned
only three of the four modified paths and omitted `Dimensions.kt`, despite the
implementation having added `pageIndicatorProgressHeight` and
`pageIndicatorSegmentDividerWidth`. A provenance-only continuation explicitly
adopted and revalidated all four modified paths, including both dimension
declarations and their exclusive uses at `StaticPhotoOverlay.kt:568` and
`StaticPhotoOverlay.kt:579`.

The continuation left repository bytes unchanged, retained a clean
`git diff --check`, and persisted exact four-path ownership. The active ownership
registry is empty, and no unrelated or untracked path is present. Final
disposition: **addressed; ownership evidence is complete**.

### Suggestions

None.

---

## Checklist

- [x] 1. Functional Requirements — Passed; all 20 dots fit at 320dp,
  middle-page drags continuously transfer emphasis, oversized rows use
  fractional segmented progress, and settled-page/viewer behavior remains
  intact.
- [x] 2. Code Quality — Passed; focused helpers, descriptive names, obsolete
  capped-window code removed, shared dimensions reused, and no unrelated
  abstraction introduced.
- [x] 3. Architectural Compliance — Passed; the change remains inside the
  established shared viewer component, theme dimensions, focused tests, and
  canonical viewer documentation.
- [x] 4. KMP Layers And Lifecycle — Passed; all behavior is in `commonMain`, no
  platform duplication or lifecycle ownership changed, and shared compilation
  succeeded across JVM, Android, iOS ARM64, and iOS Simulator ARM64.
- [x] 5. Immich API, Room, And Cache — Not applicable; no endpoint,
  authentication, repository, Room schema, cache, pagination, or data-contract
  behavior changed.
- [x] 6. Compose UI And Photo Grids — Passed; measured constraints and active
  density drive adaptive layout, existing viewer components and `Dimens` are
  reused, accessibility remains tied to `settledPage`, and the exact Memories
  interaction and overlay layering were traced in current source.
- [x] 7. Platform Integration — Passed; no native integration changed, all
  shared platform targets compiled, Android lint completed with 0 errors, and
  the Android debug APK assembled successfully. No device or simulator run was
  requested or performed.
- [x] 8. Error Handling — Passed; page counts, non-positive widths,
  out-of-range positions, and non-finite fractional inputs are bounded without
  introducing a crash or invalid rendering path.
- [x] 9. Security — Not applicable; no credentials, networking, logging, URLs,
  authentication, permissions, or sensitive configuration changed.
- [x] 10. Performance — Passed; rendering remains bounded to the current
  memory's asset count, no network/storage/background work was added, and no
  confirmed Compose performance or resource-lifecycle defect was found.

---

## Verdict

**APPROVED**

The review chain is complete. The only confirmed code finding—the
fractional-density pixel-rounding Minor—was accepted and resolved, and the later
ownership-provenance blocker was corrected without modifying repository bytes.
Post-fix verification records Android lint with 0 errors, successful
JVM/Android/iOS ARM64/iOS Simulator ARM64 compilation, successful Android debug
APK assembly, 6 focused tests passing on JVM and 6 on Android host with zero
failures/errors/skips, a clean `git diff --check`, and Gradle daemon shutdown.
No findings were overridden, no code or provenance findings remain open, and no
deferred follow-up is required for this change.
