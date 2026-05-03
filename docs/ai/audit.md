# Architecture Audit

Load this for code review, architecture audit, or "check the codebase" requests. Report violations only unless explicitly asked to fix them.

For any UI, Compose, or screen audit, also load `docs/ai/ui.md` and apply its Compose-sensitive rules.

For Room, repository, cache, API, or date/time audits, also load `docs/ai/data-cache-time.md`.

## ViewModel And Domain Checks

- ViewModels are screen-specific and do not own another screen's behavior.
- ViewModels inject UseCases and Actions, not repositories.
- Reads flow through UseCases; writes and side effects flow through Actions.
- Screens do not inject repositories. Avoid screen-level UseCase injection except established app bootstrap/auth navigation.
- DI wiring in `AppModule.kt` is complete.
- DB and network writes use `Dispatchers.IO`.
- CPU-heavy transforms such as filtering, grouping, and row packing use `Dispatchers.Default` when they can block user interaction.
- Flow transforms use `.flowOn(Dispatchers.Default)` where appropriate.
- UI state flows use `WhileSubscribed(5000)` where state is exposed as `StateFlow`.
- Row packing is performed in ViewModels, never in composables.
- Row computation happens atomically with related data or layout state changes.

## UI Checks

- One composable represents one UI component.
- Screens and bottom sheets use a Screen/Content split.
- State is collected at the lowest practical scope.
- User-visible text comes from `stringResource()`.
- `dp` values come from `Dimens`, except 0-2dp inline spacing and preview containers.
- Screens account for translucent overlay top and bottom bars.
- Scrollbars receive explicit top and bottom padding.
- Lazy layouts use stable keys.
- Filtering, sorting, grouping, mapping, and row packing are not done in composables.
- Shared patterns are reused or extracted instead of duplicated.
- Thumbnail cells use `rememberAsyncImagePainter` with bounded request size and `Precision.EXACT`.
- Shared element transitions preserve the `lastSelectedAssetId` style exit-content pattern.

## Compose Recomposition And Performance Checks

Every architecture or code audit must include a Compose recomposition and performance pass. For UI, Compose, or screen-specific audits, load `docs/ai/ui.md` alongside this file and treat its state and performance rules as audit requirements.

- `[FLOW_SCOPE]` Look for state or flow collection that remains active beyond the UI state, mode, or component that needs it.
- `[RECOMPOSITION]` Look for broad state propagation, unstable inputs, or expensive derivation work that can cause avoidable recomposition.
- `[RECOMPOSITION]` Look for screen state that should be projected, indexed, row-packed, or precomputed before reaching composables.
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

```text
**[RULE]** - file:line
Violation description.
Fix: what to change.
```

Use visible, searchable labels for Compose performance findings, including `[RECOMPOSITION]`, `[LAZY_VIRTUALIZATION]`, and `[FLOW_SCOPE]`.

End with total violations by category and a short list of compliant areas.
