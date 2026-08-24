# Compose Performance And Correctness Audit

Load this for Compose performance, recomposition, effect, lazy-layout, screen,
or whole-project audits. Also load `docs/ai/ui.md` and any feature-specific
document routed from `AGENTS.md`. This is a static, findings-first audit; runtime
profiling is optional unless the user explicitly requests measurement.

## Contents

- Evidence discipline
- Stability and inputs
- State reads and derived work
- Effects, flows, and lifecycle
- Lazy layouts, media, and animation
- Threads, memory, and build configuration
- Reporting and primary references

## Evidence Discipline

- Trace the exact screen and interaction named by the user through state
  production, collection, composition, layout, drawing, and side effects.
- Report `Confirmed` only when code or a documented project contract proves the
  defect. Report a concrete but unmeasured mechanism as `Risk`.
- Use `Measurement needed` when runtime evidence is required to distinguish a
  real regression from harmless recomposition, allocation, or optional tuning.
- Do not infer release performance from debug or hot-reload behavior. Do not
  treat recomposition itself as a defect; identify why it is avoidable or costly.
- Do not recommend an optimization without naming the invalidation, allocation,
  blocking work, or lifecycle mechanism it addresses.

## Stability And Inputs

- Inspect the Kotlin and Compose compiler configuration before applying
  stability advice. The current Kotlin compiler uses strong skipping by default: restartable
  composables with unstable inputs can still skip, and lambdas created in
  composables are automatically memoized.
- Treat `@Immutable` and `@Stable` as correctness contracts. Flag annotations
  whose public properties, collections, or mutation behavior violate the stated
  contract; do not request annotations solely to make a composable skippable.
- Look for repeated creation of equal-but-new lists, maps, UI models, painters,
  interaction sources, and other inputs on hot paths. With strong skipping,
  unstable inputs are compared by identity, so instance churn can still prevent
  skipping.
- Look for large screen-state objects passed deep into the tree when a smaller
  stable projection would isolate invalidation.
- Do not request manual `remember` for callbacks by default. Require it only for
  an identity-sensitive consumer or evidence that automatic memoization is
  insufficient, and verify the keys preserve callback correctness.

## State Reads And Derived Work

- Collect state at the lowest practical owner and pass only the values needed by
  each component. Flag duplicate collectors or broad state reads that invalidate
  unrelated UI.
- Keep filtering, sorting, grouping, indexing, pagination projection, row
  packing, and Mosaic work out of composables. If a calculation is purely local
  presentation work, verify that `remember` uses every correctness-relevant key.
- Use `derivedStateOf` only when its inputs change more frequently than the value
  consumed by the UI. Flag it when the derived value changes at the same rate or
  when it hides work that belongs in a ViewModel or domain projection.
- For rapidly changing visual state, check whether reading it in a layout or draw
  modifier lambda can avoid composition. Do not move a read to a later phase when
  the UI structure genuinely depends on it.
- Flag state writes during composition, backwards writes after a state value was
  already read, and measure/layout feedback loops such as unconditional
  `onGloballyPositioned` updates.

## Effects, Flows, And Lifecycle

- Verify every `LaunchedEffect`, `DisposableEffect`, and `produceState` key.
  Values that change ownership must restart the effect; changing callbacks that
  should not restart long-lived work should use `rememberUpdatedState`.
- Flag effect restart storms, per-item effects that can be centralized, missing
  disposal, and coroutines launched directly during composition.
- Use `snapshotFlow` for high-frequency snapshot state observed as a stream.
  Confirm downstream operators coalesce or filter events to the frequency the
  consumer actually needs.
- Verify Flow collection and `stateIn`/`shareIn` lifetimes match the visible
  screen, mode, or component. Flag duplicate network, database, or scheduler
  work caused by multiple collectors.
- Preserve cancellation and bound retries, debounce work, prefetch, and parallel
  layout or image jobs.

## Lazy Layouts, Media, And Animation

- Require stable item keys and compatible `contentType` values. Verify a key is
  stable across insertions, removals, pagination, placeholders, and cache-to-live
  replacement.
- Flag bulk repeated content hidden inside one lazy item, same-direction nested
  scrolling without a bounded child, zero-size placeholders that later resize,
  and eager materialization that defeats virtualization.
- Verify near-end pagination has duplicate-request and terminal-page guards and
  does not scan or rebuild the entire displayed list on every scroll tick.
- Keep repeated cells on their plain rendering path unless they are actively
  participating in animation, transition, selection, or overlay behavior.
- Verify image requests have bounded decode sizes, stable models and cache keys,
  appropriate thumbnail variants, and no accidental full-resolution loading in
  lists. Check cancellation and prefetch ownership: viewer prefetch must stay
  bounded to the current IMAGE-only viewport window, retain unchanged
  disposables, and dispose targets that leave the window or leave composition.
- Flag per-frame object creation, layout-state publication, or expensive drawing
  setup. Use draw caching only when it avoids recreating expensive draw objects;
  do not add it to simple drawing code by default.
- For layer-only animations such as Ken Burns, keep structural state in
  composition but read frame progress and calculate layer properties inside the
  `graphicsLayer` lambda. A runtime recomposition trace remains a separate,
  explicitly authorized measurement when no existing headless harness exists.

## Threads, Memory, And Build Configuration

- Flag network, database, image cleanup, parsing, projection, row-packing, and
  Mosaic work that can block the UI dispatcher. Verify dispatcher choice at the
  actual call site rather than trusting a suspend boundary.
- Look for unbounded remembered collections, retained bitmaps or asset lists,
  stale jobs, and caches whose lifetime exceeds their screen or server owner.
- Apply common Compose checks to Android, iOS, and JVM paths. Keep Android-only
  findings such as R8, Baseline Profiles, and Macrobenchmark coverage explicitly
  platform-scoped.
- Report disabled release optimization, missing Baseline Profile coverage, or
  absent benchmark journeys as `[BUILD_PERF]` opportunities unless the project
  has a documented requirement they violate.
- Compose compiler stability reports, Layout Inspector counts, composition
  traces, and release-mode benchmarks may validate a static hypothesis. In a
  static audit, recommend the smallest relevant measurement rather than running
  it automatically.

## Reporting

Use the format from `docs/ai/audit.md`. For each Compose finding, include one
category label and one evidence level. The fix must preserve the exact UI flow
and project-specific contracts; do not trade correctness for fewer
recompositions.

Primary references:

- [Compose performance best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices)
- [Compose stability and strong skipping](https://developer.android.com/develop/ui/compose/performance/stability/strongskipping)
- [Compose phases](https://developer.android.com/develop/ui/compose/phases)
- [Compose side effects](https://developer.android.com/develop/ui/compose/side-effects)
- [Lazy lists and grids](https://developer.android.com/develop/ui/compose/lists)
