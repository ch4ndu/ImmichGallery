# Mosaic Runtime Scheduling And Chunks

Load this when changing runtime Mosaic queueing, pause/resume behavior, scroll prioritization, progressive chunks, checkpoints, retry behavior, or scheduler thread usage.

For asset-to-band assignment and projection details, also read `docs/ai/mosaic-assignment.md`. For cross-screen architecture rules, read `docs/ai/mosaic-rendering.md`.

## Shared Compute Contract

- Runtime work always enters the domain layer through `MosaicRenderEngine.computeSection(...)`.
- The engine calls `buildMosaicAssignmentsWithProgress(...)` and returns either:
  - `Ready`, with complete assignments, ready display items, and section geometry.
  - `Failed`, with the compute failure.
- Callers provide:
  - an optional `MosaicAssignmentCheckpoint` for resume.
  - `onCheckpoint`, called when stable progress exists or when compute is interrupted.
  - `onProgressChunk`, called when a stable contiguous chunk can be displayed or remembered.
  - `shouldContinue`, used by schedulers to pause/cancel work cooperatively.
- Stale output is rejected by the owning ViewModel/coordinator using the session
  stamp (generation plus long-lived owner epoch), config, revision/fingerprint,
  and owner/section keys. Descendant work carries its originating stamp and must
  not read a newer global session value after suspension.

## Chunk Calculation

- `buildMosaicAssignmentsWithProgress(...)` uses the source-order assignment scan described in `docs/ai/mosaic-assignment.md`.
- A checkpoint records:
  - all assignments found so far.
  - current `sourceIndex`.
  - current `bandIndex`.
  - `chunkStartIndex`, the source index where the next emitted chunk should begin.
  - `lastEmittedBandCount`, the number of assignments already emitted as chunks.
- A progress chunk is considered only after at least `progressBandBatchSize` new assignments have accumulated since the last emitted chunk. The default is `TIMELINE_MOSAIC_PROGRESS_BAND_BATCH_SIZE`.
- The chunk source end is the end of the newest accepted assignment:

```text
chunkEndIndex = candidate.sourceStartIndex + candidate.sourceCount
```

- Before emission, `isStableProgressChunk(...)` projects the candidate chunk in isolation. The chunk emits only when projection can produce display output for that chunk range.
- When a chunk emits:
  - `sourceStartIndex = previous chunkStartIndex`
  - `sourceEndExclusive = chunkEndIndex`
  - `assignments = assignments since lastEmittedBandCount`
  - `chunkStartIndex` advances to `chunkEndIndex`
  - `lastEmittedBandCount` advances to the current assignment count
  - `onCheckpoint(...)` runs before `onProgressChunk(...)`
- At the end of the scan, any remaining assignments that were not emitted as a full batch are emitted as a final chunk.
- If compute throws, the engine still calls `onCheckpoint(currentCheckpoint())` before rethrowing.
- Checkpoint resume is accepted only when it is valid for the current ordered assets: assignment order, source ranges, asset ids, band indexes, and cursor positions must match the current section.
- Partial display remains strict and geometry-gated: valid chunks may show real Mosaic bands only when matching section geometry is already present for that render decision. Gaps, skipped windows, invalid chunks, and not-yet-computed ranges show chunk-aligned exact placeholders. Without geometry, partial chunks remain buffered/resumable and the UI keeps the placeholder/old display.

## Timeline Queue

- Timeline queues work by `timeBucket`, not by section.
- `requestTimelineMosaicCacheReadForBuckets(...)` filters requests to loaded buckets and splits them into runnable versus deferred buckets based on scroll state, visible/target buckets, and `cacheMosaicResults`.
- Timeline Mosaic work carries an explicit source:
  - `RenderDemand` is produced by visible bucket changes, targeted bucket changes, and scroll-settled resume. It may compute runtime Mosaic when cache results are off.
  - `SyncPrecompute` is produced by cold/warm/manual sync and changed-bucket publication. It must never fall through to runtime Mosaic when cache results are off.
- `TimelineMosaicQueueActor` owns Timeline Mosaic queue state. `TimelineViewModel` must not keep direct mutable pending/running/deferred bucket collections. Every enqueue/resume request carries the originating session stamp; actor merges preserve that stamp rather than manufacturing a new one.
- The actor processes commands serially and emits effects through a receive-only channel:
  - `Enqueue` adds runnable bucket requests.
  - `Defer` records paused or interrupted requests.
  - `PauseForScroll`, `ResumeAfterScroll`, `InvalidateBucket`, `BeginReset`, `EndReset`, and `WorkerFinished` mutate actor-owned state only.
  - `ResetBoundary(id)` is emitted after `BeginReset(id)` reaches the actor, giving the ViewModel a serialized point to advance runtime generations and clear runtime state before `EndReset(id)` reopens admission.
  - `StartWork(request, token)` and `CancelWork(request, token)` are consumed by the ViewModel outside the actor loop.
- Actor merge rules:
  - request identity is `timeBucket`.
  - `RenderDemand` supersedes pending/deferred `SyncPrecompute` for the same bucket.
  - `SyncPrecompute` is ignored when render demand already exists for the same bucket.
  - duplicate same-source requests collapse to one request.
- Timeline runs one actor-started bucket worker at a time. The worker launches on `Dispatchers.IO`, while actual runtime assignment compute uses `TimelineMosaicDispatcherProvider`, a dedicated single-thread `TimelineMosaic` dispatcher.
- Each `StartWork` effect carries a per-work token. ViewModel worker jobs are keyed by that token, `CancelWork` cancels the matching job, and cache/runtime publication must reject inactive tokens plus stale config/revision/geometry.
- `computeRuntimeTimelineMosaicForBuckets` skips any section that is already current `Ready` with in-memory geometry unless it is repair-required, so warmed sections are not recomputed when they become visible.
- Sections in the repair-required set (per-range mismatch detected at render time or by repair preparation) are removed from cached `Ready` publication and added to the requested missing sections, so render-demand work recomputes them on the single Timeline worker. The key leaves the set when that recompute produces `Ready`.
- If `cacheMosaicResults = true`, a queued bucket first attempts a current-config persisted artifact read. Matching assignments plus section and aggregate geometry are enough to publish assignment-backed ready state, even when mixed display records are missing. Missing assignments or geometry are true misses and may enqueue runtime compute only for render-demand requests.
- If `cacheMosaicResults = false`, render-demand requests skip persisted Mosaic artifacts and compute runtime Mosaic for the requested config. Sync-precompute requests are dropped after logging.
- Timeline does not fetch older Mosaic configs as substitutes for missing current-config artifacts.
- Cold sync with cache results off publishes synced assets without Timeline Mosaic cache preparation. Runtime Mosaic starts later from normal visible/target render demand.

## Timeline Pause And Resume

- Lazy-list scroll and scrollbar drag are combined into one interactive-scroll state. Entering either source sends `PauseForScroll` to `TimelineMosaicQueueActor`.
- Pause behavior:
  - the currently running bucket request is allowed to finish; entering scroll does not cancel it.
  - pending and deferred `RenderDemand` requests are discarded so obsolete drag targets cannot accumulate.
  - new `RenderDemand` requests are ignored while paused. Sync-precompute requests may remain deferred, but no new work starts.
- Runtime compute does not use `shouldContinue` to cancel for interactive scroll. Non-scroll invalidation and stale-config changes cancel or reject the worker through its token and generation guards.
- On runtime cancellation from invalidation, preemption, or stale config:
  - the latest valid checkpoint is stored in `TimelineRuntimeStateStore` under the section `MosaicCacheKey`, together with the producing session stamp and the captured global/bucket runtime generations.
  - stale checkpoint saves and removals are rejected if bucket/global Mosaic generation advanced while compute was running, or if the stored checkpoint belongs to a different session stamp.
  - accumulated progress chunks for that section are stored with the checkpoint.
  - the bucket is sent back to the actor as `Defer(RenderDemand)` when the queue is not paused; paused queues discard that obsolete render demand and rely on the settled target request.
- When both scroll sources stop:
  - filters deferred state/geometry to the final scrollbar target and settled visible buckets, then publishes those updates.
  - sends `ResumeAfterScroll(priorityBuckets)` with the final target first and settled visible loaded buckets next as render demand.
  - the actor appends only remaining deferred sync-precompute work; obsolete drag render demand is not resumed.
  - starts radius-neighbor materialization only after `250ms` of continued idle time. Cache-enabled skeleton geometry is already loaded for the whole library and is not scroll-gated.
  - with cache results off, that idle warmup instead walks outward from the settled viewport (nearest first, alternating down/up) until `TIMELINE_CACHE_OFF_WARMUP_SCREENS` viewport heights or `TIMELINE_CACHE_OFF_WARMUP_MAX_BUCKETS_PER_DIRECTION` buckets per direction, skipping buckets whose sections are already current `Ready` with geometry. Unloaded window buckets are materialized (which enqueues `RenderDemand`); already-materialized ones are enqueued explicitly as `RenderDemand`. This bounded idle warmup is render demand; offscreen output finished during a later scroll is still discarded by the settled-target drain and rescheduled by the next window. Materialized assets and runtime geometry are retained for the session (no eviction).
  - flushes eligible progressive chunks immediately.
- Visible-priority render demand may preempt a running offscreen bucket. It must not preempt a currently visible or explicitly targeted running bucket.

## Timeline Progressive Display

- Timeline has two progressive paths:
  - cache-preparation/precompute can publish `TimelineMosaicProgressChunk`s into the ViewModel.
  - render-demand runtime compute stores progress chunks for resume and may publish them through the same progressive buffer before final ready output.
- `publishProgressiveMosaicChunk(...)` accepts a chunk only when:
  - config still matches.
  - geometry request still matches.
  - bucket asset revision still matches.
  - chunk range is non-empty.
  - chunk assignments are non-empty.
  - Timeline is not actively scrolling.
- Accepted chunks are stored in `TimelineProgressiveMosaicBuffer`, keyed by `MosaicCacheKey`.
- Every buffered chunk retains its originating session stamp. Delayed flushes
  partition chunks by stamp and enter an isolated `runForStamp` child before
  publishing; stale or closed-admission cancellation discards that partition
  without cancelling the long-lived consumer.
- The buffer de-duplicates chunks by `sourceStartIndex/sourceEndExclusive` and keeps them sorted by source start.
- Visible loaded buckets with matching section geometry ranges are eligible for flush. Chunks without section geometry stay buffered until geometry arrives or stale state is cleared.
- Flushing chunks must never create rough Mosaic partial output. The flushed partial state is renderable only through geometry-backed projection; otherwise the section remains unresolved.
- Flush is immediate for explicit visible/target events and section-geometry publication, otherwise delayed by `PROGRESSIVE_MOSAIC_FLUSH_DELAY_MS` (`250ms`).
- Flush converts buffered chunks into `MosaicSectionState.Partial` unless the section is already `Ready`. Existing partial chunks are merged, de-duplicated by source range, and sorted.
- Publishing a `Ready` state clears buffered partial chunks for that key.

## Timeline Publication Rules

- While scrolling, `publishMosaicUpdates(...)` does not mutate visible Mosaic state directly. It stores state, geometry, and repair fragments in keyed deferred maps.
- After scroll settles, only deferred state/geometry for the final target and settled visible buckets is published; obsolete intermediate drag targets are discarded.
- `TimelineRuntimeStateStore` owns Timeline day-group memoization, runtime Mosaic resume checkpoints, deferred materialized bucket ids, and one deferred-scroll Mosaic publish batch. These entries are mutated through CAS-backed immutable snapshots rather than ad hoc mutable ViewModel collections. Bucket invalidation advances that bucket generation and atomically removes bucket-owned day groups, checkpoints, deferred materialization, and deferred Mosaic publishes. Global invalidation advances the global generation and clears runtime checkpoints/deferred publishes so stale computations cannot reinsert state after invalidation.
- Runtime compute and persisted Mosaic reads capture an immutable origin publish stamp before the expensive work starts. Descendant runtime compute from a persisted-read cache miss inherits that origin stamp rather than minting a new one. Final publish, repair requests, and runtime checkpoint save/remove decisions use that captured stamp so old work cannot adopt a newer runtime generation after invalidation.
- Pending publish and repair work uses keyed newest-value buffers plus a single conflated wakeup, not unbounded channels. Publish fragments are inserted atomically for one `PendingMosaicPublish`; deferred-drain batches also flatten all publishes into one keyed-buffer CAS so a logical batch cannot split across drains. The logical keys are Mosaic state section, Mosaic geometry section, and repair bucket. Each key keeps one newest value by session stamp plus runtime global/bucket generation, so late obsolete same-key output cannot replace current output or accumulate by generation. Distinct logical keys are preserved.
- Deferred Mosaic publishes are accepted only when their captured bucket/global generations still match inside the store transaction. Deferred materialized bucket ids carry bucket-only generation stamps because valid Room asset rows should survive Mosaic config/global invalidation but must not survive bucket content invalidation. Drained materialized ids keep those bucket stamps through the final `_bucketData` publish. If scroll stops before a producer finishes deferring, one conflated settled-drain signal re-runs the normal deferred drain path; if scroll restarts after a batch is drained, the batch is re-deferred through the same generation filter instead of publishing during active scroll or being lost.
- Pending publications, deferred-scroll payloads, runtime checkpoints, and
  persisted-geometry reads all retain the producer's full session stamp.
  Long-lived consumers isolate each stamped sink so stale output is discarded
  without terminating the consumer. A descendant never derives a replacement
  stamp when the sink finally runs.
- Applied Timeline group, Mosaic-policy, and layout-geometry changes are
  serialized with final publication. The publication gate installs the new
  config and clears incompatible visible state, section and aggregate geometry,
  geometry readiness, and progressive chunks while advancing runtime generation
  as one mutation. Only afterward does `BeginReset` cancel/join pre-change actor
  work and acknowledge the reset boundary. A reset rejected by a failed session
  mutation is retried after admission reopens when the same owner survives; an
  owner change abandons the old ViewModel's post-reset reads. Persisted geometry
  is requested before visible Mosaic reads resume.
- Manual-sync Mosaic precompute captures its UI runtime generation and asset
  revisions before the platform producer starts. Completion validates that
  origin in the same publication-gated mutation that applies the sync's own
  bucket invalidations and revision changes, then derives the post-invalidation
  publication stamp. Superseded summaries and failures are discarded and
  replaced by a current render-demand read rather than being relabeled at sink
  time.
- Blocking Mosaic config preparation uses a preserve reset: it cancels and
  joins obsolete work and removes transient partial chunks while keeping the
  previously completed layout visible. Applying a prepared config uses a full
  reset and clears the completed layout because its width/config identity is no
  longer current. A failed reset completes its waiter exceptionally; callers
  must not schedule post-reset reads after a failed clear.
- Progressive-buffer drains retain global, bucket, and per-key clear epochs.
  A bucket, terminal-state, or global clear invalidates an already-drained
  batch before either publication or scroll requeue. Bucket/global epoch
  advances also compact obsolete per-key epoch entries.
- Failed Timeline sections render placeholders and retry through normal requested-config cache/runtime repair; failure must not display fallback rows or fallback Mosaic bands.
- Ready Timeline sections display validated cached mixed display records when available, or completed ready projection from assignments.
- A cache-enabled render-demand miss persists successful repair only after every current bucket section is ready. Valid cached siblings are reused, invalid sections are recomputed, aggregate geometry is recalculated, and the repository atomically replaces the bucket. Ready state is queued for publication before repair persistence. Repair requests preserve the producer's session stamp, then run through one conflated serial writer that keeps only the newest request per bucket, pauses writes during interactive scroll, and revalidates session stamp, config, revision, families, and geometry immediately before writing.

## Album And Person Detail Scheduler

- Album Detail and Person Detail share `PhotoGridDetailLayoutCoordinator`; the full screen experience is documented in `docs/ai/album-person-detail.md`.
- Detail runtime work is keyed by group, not Timeline bucket, and always runs through `MosaicWorkScheduler`.
- Detail preparation, full layout scheduling, and incomplete-layout resume enter
  the owning session boundary before capturing asset/runtime snapshots. This
  prevents an old owner from reading pre-mutation state and later registering
  the resulting artifact under a new session.
- `MosaicWorkScheduler` uses `DEFAULT_PARALLELISM = 1`, active owner priority, visible-group priority, and pending-work reprioritization guarded by a monotonic per-owner visibility revision (applying the latest visible request set on submission so out-of-order visibility updates or newly submitted work cannot invert priority).
- Cancelling a scheduler waiter cancels its independent execution job and waits
  for that job's completion in non-cancellable cleanup. Session mutation cannot
  proceed merely because the waiter stopped while a late progress callback is
  still executing. Progress/runtime/publication mutations recheck both the
  execution job and work token immediately before writing.
- Detail visibility reprioritization and delayed/deferred flushes enter the
  coordinator's owner epoch before touching runtime state.
- Detail layout/config/asset invalidations are serialized by the coordinator's
  runtime-state transaction: old runtime/deferred state is cleared, the Mosaic
  layout generation is advanced, and replacement layout work is scheduled before
  another invalidation can clear or schedule against a newer generation. Cache
  reads and Mosaic assignment compute remain outside that short transaction.
- Runtime states are `InFlight`, `Partial`, and `RetryableFailure`; incomplete states render placeholders or strict partial output, never row-packing fallback.
- Cache-enabled Mosaic settings preparation broadcasts cancellation through `MosaicConfigPreparationCoordinator` before expensive preparation work starts. Live Timeline/detail owners cancel pending geometry calculations, layout runners, deferred flush/resume jobs, and scheduler work that belongs to obsolete configs while preserving ready display caches and persisted artifacts.

## Detail Pause, Resume, And Retry

- Detail scroll start cancels deferred publish flushes but keeps checkpoints/chunks.
- Detail scroll settle schedules deferred group flush and incomplete Mosaic resume.
- Resume snapshots runtime state before iterating, filters to current keyed groups without final cache, and runs only the visible-plus-neighbor window with visible targets first.
- Retryable failures resume only inside that same visible-plus-neighbor window and use exponential backoff capped at `5000ms`.

## Detail Progressive Display And Publication

- Progress chunks update group state to `Partial` only for resumability. Visible partial output must use geometry-backed projection; `projectPartialSectionWithPlaceholders(...)` is not a Timeline/detail UI display path.
- Visible partial/ready groups may publish immediately; offscreen output may defer until scroll settles.
- `groupDisplayCache` is written only for final ready group output.
- Full in-memory `displayCache` is written only when every current group has final ready output, no runtime/deferred state remains, and the display contains no placeholders.
