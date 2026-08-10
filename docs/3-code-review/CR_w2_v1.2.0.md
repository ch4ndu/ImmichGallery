# Code Review: Advanced Search

**Review Date**: 2026-08-09
**Version**: 1.2.0
**Plan**: `docs/1-plans/F_1.2.0_advanced-search.plan.md`
**Implementation range**: `4b13a4b..99fbf00`

## Executive Summary

Search was expanded from basic smart/filename queries into an Immich-aligned
Advanced Search surface. The implementation adds four text modes, searchable
multi-select People/Album/Tag editors, camera and location suggestions, date and
metadata filters, capability-aware availability, adaptive sheet/dialog hosts,
and one normalized submitted-session model across search execution and
pagination.

The initial Sol xhigh review confirmed the People flow, draft semantics,
endpoint selection, omitted/null request encoding, adaptive UI, pagination, and
viewer wiring. It found two Major issues: the lens suggestion type used the
wrong Immich wire value, and raw Ktor suggestion exceptions could expose request
dependencies in local logs. Both findings were accepted, fixed together, and
covered by focused regressions. A fresh metadata-verified Sol high final gate
then found no unresolved Critical, Major, Minor, or Suggestion issues.

**Final verdict: APPROVED.**

## Changes Overview

- `SearchCriteria` owns normalized text mode and filter state, conflict rules,
  selected entity IDs, date boundaries, tri-state fields, and visibility.
- `SearchAssetsUseCase` freezes the Smart-versus-Metadata endpoint before I/O;
  endpoint-specific JSON preserves omitted fields versus explicit JSON nulls.
- `SearchViewModel` owns immutable submitted sessions, pagination generations,
  lazy/cached option state, cancellation, retry, and blank-filter restoration.
- People and Album options reuse existing Room flows and synchronization
  UseCases. Tags, server features, preferences, and typed suggestions remain
  destination-lifetime live state and add no Search Room table.
- Search editing uses one atomic draft in a bottom sheet below 600dp and a
  bounded dialog at wider sizes. The existing justified results and static
  viewer remain the result surface.

## Findings

### Critical Issues

None.

### Major Issues

#### Lens-model suggestion wire value — addressed

The initial implementation mapped `SearchSuggestionType.LENS_MODEL` to
`lens-model`. The pinned Immich contract accepts `camera-lens-model`, so every
lens option request would have failed.

The API mapping now emits `camera-lens-model`. The request-contract test covers
all stable suggestion types and the complete normalized lens request, including
`includeNull`, make, and model dependencies. No stale production mapping
remains.

#### Suggestion-failure diagnostic disclosure — addressed

Camera and location dependencies are request query parameters. Passing a Ktor
response exception to the logger could therefore include the request URL,
response text, and user-selected dependencies in local logs.

The failure path now logs only the normalized suggestion type and exception
class category. It passes no throwable and does not interpolate exception
messages or request-derived values. The regression exercises the real
ViewModel failure path with a throwable containing a private URL, dependency,
and response text; it verifies one sanitized entry and preserves the retryable
UI error state.

### Minor Issues

None.

### Suggestions

None.

## Checklist

- [x] **Functional requirements** — Context, Filename, Description, OCR,
  searchable multi-select People, every approved filter, draft Cancel/Apply,
  direct chip removal, final-filter Explore restoration, paging, retry, and
  viewer flows are connected.
- [x] **Code quality** — Criteria and option contracts are strongly typed and
  focused; obsolete mode-specific UseCases were removed; no unrelated
  refactoring or Kotlin `!!` was introduced.
- [x] **Architecture** — Dependency direction remains API to repository to
  UseCases/Actions to ViewModel to Compose. Request construction stays in the
  data layer and editor state stays screen-owned.
- [x] **KMP and lifecycle** — Production changes remain in `commonMain`; Koin
  registrations are complete; no navigation or platform-host ownership moved.
- [x] **API and cache** — Immich field names, omitted/null behavior, date
  boundaries, stable ID order, pagination, authentication, and live-versus-Room
  ownership are covered. No Room schema or migration changed.
- [x] **Compose UI** — Narrow/wide adaptive hosts, stable entity keys,
  authenticated thumbnails, selected-first filtering, and the existing grid and
  viewer wiring conform to the project UI contract.
- [x] **Platform integration** — JVM, Android, iOS ARM64, and iOS Simulator
  shared compilation passed; Android lint and debug assembly passed. No native
  host file changed.
- [x] **Error handling** — Initial, option, first-page, and later-page failures
  retain explicit retry paths. Stale requests cannot publish over newer state.
- [x] **Security** — API keys remain header-based for Search; suggestion failure
  logs contain neither throwables nor request-derived camera/location values.
- [x] **Performance** — Filtering and row packing stay outside composition,
  option requests are lazy and cached by normalized dependencies, and
  cancellation prevents stale work from accumulating.

## Verification

The integrated matrix passed:

- Android `lintDebug`;
- JVM, Android, iOS ARM64, and iOS Simulator ARM64 compilation;
- Android debug APK assembly;
- 358 JVM host tests and 358 Android host tests, for 716 passing executions;
- 54 initially added focused tests;
- Gradle daemon shutdown.

After review remediation, the risk-based matrix passed Android lint, all named
shared platform compilation targets, Android debug assembly, and 26 affected
JVM/Android host executions. One additional behavioral logging regression
brought the feature range to 55 added focused tests. Diff checks passed and no
Gradle daemon remained running.

No simulator, emulator, physical device, live Immich server, screenshot, or
device-log run was performed. This matches the approved verification boundary.

## Review Provenance

- Initial review: verified `gpt-5.6-sol`, `xhigh`, read-only; two Major findings.
- Review response: verified `gpt-5.6-sol`, `xhigh`, read-only; both findings
  accepted with current file evidence.
- Fix wave: verified `gpt-5.6-sol`, `xhigh`, scoped workspace write; both fixes
  and their focused tests applied together.
- Final gate: verified `gpt-5.6-sol`, `high`, read-only; current clean commit
  `99fbf00` adjudicated with no unresolved finding.

## Verdict

**APPROVED**

The implementation satisfies the approved Advanced Search scope. Both review
findings are resolved and regression-covered, the verification gates are green,
the ownership and documentation records are complete, and the feature is ready
for the 1.2.0 release handoff.
