# Architecture Audit

Load this for code review, architecture audit, or "check the codebase" requests. Report findings only unless explicitly asked to fix them.

For any UI, Compose, screen, or whole-project audit, also load
`docs/ai/compose-performance-audit.md` and `docs/ai/ui.md`. Apply the dedicated
Compose audit's evidence classification instead of presenting unmeasured
optimization risks as confirmed regressions.

For Room, repository, cache, API, or date/time audits, also load `docs/ai/data-cache-time.md`.

For Mosaic rendering, cache artifact, scheduler, or fallback audits, also load `docs/ai/mosaic-rendering.md`.

For standard row packing or justified row audits, also load `docs/ai/row-packing.md`.

## ViewModel And Domain Checks

- ViewModels are screen-specific and do not own another screen's behavior.
- ViewModels inject UseCases and Actions, not repositories.
- Reads flow through UseCases; standalone writes and side effects flow through Actions, while cached-resource `Get*UseCase` classes may expose repository `sync*` / cold-sync completion entry points alongside read/observe methods where documented.
- Screens do not inject repositories. Avoid screen-level UseCase injection except established app bootstrap/auth navigation.
- DI wiring in `AppModule.kt` is complete.
- DB and network writes use `Dispatchers.IO`.
- CPU-heavy transforms such as filtering, grouping, and row packing use `Dispatchers.Default` when they can block user interaction.
- Flow transforms use `.flowOn(Dispatchers.Default)` where appropriate.
- UI state flows use `WhileSubscribed(5000)` where state is exposed as `StateFlow`.
- Row packing is performed in ViewModels, never in composables.
- Mosaic assignment is performed in domain/ViewModel code, never in composables.
- Mosaic assignment, progress chunks, assignment checkpoints, fallback projection, section geometry, aggregate geometry, and Timeline artifact derivation go through domain Mosaic code. Flag any reintroduced split Timeline/detail renderer or repository-owned Mosaic math/artifact derivation as an architecture violation.
- Mosaic fallback rows are completed-output artifacts only. Flag fallback Mosaic bands anywhere, flag fallback rows in pending, partial-gap, interrupted, cache-miss, or failed display states, and flag persisted display rows that are replayed without current ordered-asset coverage validation.
- Row computation happens atomically with related data or layout state changes.
- Zoom-driven photo-grid layout work uses cancellable/debounced orchestration such as `PhotoGridLayoutRunner`; it should not recompute expensive row/Mosaic projections directly on every gesture step.
- Persisted view settings are accessed through UseCases/Actions, not direct ViewModel repository injection.

## UI Checks

- One composable represents one UI component.
- Screens and bottom sheets use a Screen/Content split.
- State is collected at the lowest practical scope.
- User-visible text comes from `stringResource()`.
- `dp` values come from `Dimens`, except 0-2dp inline spacing and preview containers.
- Screens account for translucent overlay top and bottom bars.
- Scrollbars receive explicit top and bottom padding.
- Timeline scrollbar labels, markers, handle position, and drag targets use one page-based targeting model.
- Lazy layouts use stable keys.
- No business logic should happen in composables.
- Shared patterns are reused or extracted instead of duplicated.
- Thumbnail cells use `rememberAsyncImagePainter` with bounded request size and `Precision.EXACT`.
- Shared element transitions preserve the `lastSelectedAssetId` style exit-content pattern.

## Compose Recomposition And Performance Checks

Every whole-project audit must include a Compose recomposition and performance
pass. Focused UI, Compose, or screen audits must load
`docs/ai/compose-performance-audit.md` alongside `docs/ai/ui.md` and treat both
as audit requirements. Focused non-UI audits do not need the Compose reference
unless their state, threading, or data flow reaches Compose.

- `[FLOW_SCOPE]` Look for state or flow collection that remains active beyond the UI state, mode, or component that needs it.
- `[RECOMPOSITION]` Look for broad state propagation, unstable inputs, or expensive derivation work that can cause avoidable recomposition.
- `[RECOMPOSITION]` Look for screen state that should be projected, indexed, row-packed, or precomputed before reaching composables.
- `[RECOMPOSITION]` Look for pending/in-flight layout bookkeeping that causes broad recomposition without changing rendered display items.
- `[LAZY_VIRTUALIZATION]` Look for lazy layouts that lose virtualization, stable identity, or efficient row-level recomposition.
- `[RECOMPOSITION]` Treat `remember`, `derivedStateOf`, and similar Compose-local caching as performance tools, not replacements for correctly scoped ViewModel or domain projections.
- Surface any missed Compose performance issue even if it is not named here; this list defines categories, not an exhaustive checklist.

## Data, Cache, And API Checks

- No Kotlin `!!`.
- Room cache writes are scoped and transactional where relationship tables must stay consistent.
- Cache reads rebuild URLs from current server config where practical.
- Hidden Immich assets are filtered from timeline presentation and cache writes.
- `AssetResponse` to domain/entity mappers preserve thumbhash aspect-ratio fallback.
- Asset URLs include edited variants when required for non-destructive edit support.
- Timeline cache uses `TimelineBucketEntity`, `AssetEntity`, and `TimelineAssetCrossRef`.
- Album and person caches use their cross-reference tables consistently.
- No raw date literals such as `86400000L`; use date/time utilities or named constants.

## Report Format

Classify each finding as one of:

- `Confirmed`: code or a documented contract proves the defect.
- `Risk`: the code has a concrete performance or correctness mechanism, but
  user-visible impact is not statically proven.
- `Measurement needed`: runtime evidence is required before claiming an impact.

Static audits do not require profiling. Never describe debug-build slowness,
recomposition by itself, or the absence of an optional optimization as a
confirmed defect.

```text
**[RULE]** - file:line
Finding description.
Evidence: Confirmed | Risk | Measurement needed.
Fix: what to change.
```

Use visible, searchable labels for Compose performance findings, including
`[RECOMPOSITION]`, `[STABILITY]`, `[STATE_READ_PHASE]`,
`[EFFECT_LIFECYCLE]`, `[LAZY_VIRTUALIZATION]`, `[FLOW_SCOPE]`,
`[MAIN_THREAD]`, `[IMAGE_PIPELINE]`, and `[BUILD_PERF]`.

End with totals by category and evidence level plus a short list of compliant
areas.
