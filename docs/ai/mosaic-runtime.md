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
- Stale output is rejected by the owning ViewModel/coordinator using generation, config, revision/fingerprint, and owner/section keys.

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
- Partial display remains strict: valid chunks may show real Mosaic bands, while gaps, skipped windows, invalid chunks, and not-yet-computed ranges show placeholders.

## Timeline Queue

- Timeline queues work by `timeBucket`, not by section.
- `requestTimelineMosaicCacheReadForBuckets(...)` filters requests to loaded buckets and splits them into runnable versus deferred buckets based on scroll state, visible/target buckets, and `cacheMosaicResults`.
- Timeline Mosaic work carries an explicit source:
  - `RenderDemand` is produced by visible bucket changes, targeted bucket changes, and scroll-settled resume. It may compute runtime Mosaic when cache results are off.
  - `SyncPrecompute` is produced by cold/warm/manual sync and changed-bucket publication. It must never fall through to runtime Mosaic when cache results are off.
- `TimelineMosaicQueueActor` owns Timeline Mosaic queue state. `TimelineViewModel` must not keep direct mutable pending/running/deferred bucket collections.
- The actor processes commands serially and emits effects through a receive-only channel:
  - `Enqueue` adds runnable bucket requests.
  - `Defer` records paused or interrupted requests.
  - `PauseForScroll`, `ResumeAfterScroll`, `InvalidateBucket`, `ClearAll`, and `WorkerFinished` mutate actor-owned state only.
  - `StartWork(request, token)` and `CancelWork(request, token)` are consumed by the ViewModel outside the actor loop.
- Actor merge rules:
  - request identity is `timeBucket`.
  - `RenderDemand` supersedes pending/deferred `SyncPrecompute` for the same bucket.
  - `SyncPrecompute` is ignored when render demand already exists for the same bucket.
  - duplicate same-source requests collapse to one request.
- Timeline runs one actor-started bucket worker at a time. The worker launches on `Dispatchers.IO`, while actual runtime assignment compute uses `TimelineMosaicDispatcherProvider`, a dedicated single-thread `TimelineMosaic` dispatcher.
- Each `StartWork` effect carries a per-work token. ViewModel worker jobs are keyed by that token, `CancelWork` cancels the matching job, and cache/runtime publication must reject inactive tokens plus stale config/revision/geometry.
- If `cacheMosaicResults = true`, a queued bucket first attempts a current-config persisted artifact read. Matching assignments plus section and aggregate geometry are enough to publish assignment-backed ready state, even when mixed display records are missing. Missing assignments or geometry are true misses and may enqueue runtime compute only for render-demand requests.
- If `cacheMosaicResults = false`, render-demand requests skip persisted Mosaic artifacts and compute runtime Mosaic for the requested config. Sync-precompute requests are dropped after logging.
- Timeline does not fetch older Mosaic configs as substitutes for missing current-config artifacts.
- Cold sync with cache results off publishes synced assets without Timeline Mosaic cache preparation. Runtime Mosaic starts later from normal visible/target render demand.

## Timeline Pause And Resume

- When scrolling starts, `onScrollInProgressChanged(true)` sends `PauseForScroll` to `TimelineMosaicQueueActor`.
- Pause behavior:
  - the currently running bucket request, if any, is moved to actor-owned deferred state with its work source.
  - the actor emits `CancelWork` for the running token.
  - pending bucket work stays queued but does not start while paused.
- Runtime compute also receives a cooperative `shouldContinue` callback. If scrolling becomes active during assignment, `shouldContinue` throws cancellation.
- On runtime cancellation:
  - the latest valid checkpoint is stored in `runtimeMosaicResumeStates` under the section `MosaicCacheKey`.
  - accumulated progress chunks for that section are stored with the checkpoint.
  - the bucket is sent back to the actor as `Defer(RenderDemand)`.
  - duplicate defers from pause and cancellation collapse through actor merge rules.
- When scrolling stops, `onScrollInProgressChanged(false)`:
  - publishes any state/geometry updates deferred during scroll.
  - sends `ResumeAfterScroll(priorityBuckets)` with visible loaded buckets first as render demand.
  - the actor appends remaining deferred buckets with their original work source.
  - requests persisted bucket geometry.
  - flushes eligible progressive chunks immediately.
- Visible-priority render demand may preempt a running offscreen bucket. It must not preempt a currently visible or explicitly targeted running bucket.

## Timeline Progressive Display

- Timeline has two progressive paths:
  - cache-preparation/precompute can publish `TimelineMosaicProgressChunk`s into the ViewModel.
  - cache-disabled runtime compute stores progress chunks for resume if it is interrupted; final ready output publishes when compute completes.
- `publishProgressiveMosaicChunk(...)` accepts a chunk only when:
  - config still matches.
  - geometry request still matches.
  - bucket asset revision still matches.
  - chunk range is non-empty.
  - chunk assignments are non-empty.
  - Timeline is not actively scrolling.
- Accepted chunks are stored in `TimelineProgressiveMosaicBuffer`, keyed by `MosaicCacheKey`.
- The buffer de-duplicates chunks by `sourceStartIndex/sourceEndExclusive` and keeps them sorted by source start.
- Visible loaded buckets are eligible for flush.
- Flush is immediate for explicit visible/target events, otherwise delayed by `PROGRESSIVE_MOSAIC_FLUSH_DELAY_MS` (`250ms`).
- Flush converts buffered chunks into `MosaicSectionState.Partial` unless the section is already `Ready`. Existing partial chunks are merged, de-duplicated by source range, and sorted.
- Publishing a `Ready` state clears buffered partial chunks for that key.

## Timeline Publication Rules

- While scrolling, `publishMosaicUpdates(...)` does not mutate visible Mosaic state directly. It stores state and geometry updates in deferred maps.
- After scroll settles, deferred state/geometry maps are published together.
- Failed Timeline sections render placeholders and retry through normal requested-config cache/runtime repair; failure must not display fallback rows or fallback Mosaic bands.
- Ready Timeline sections display validated cached mixed display records when available, or completed ready projection from assignments.

## Album And Person Detail Scheduler

- Album Detail and Person Detail share `PhotoGridDetailLayoutCoordinator`; the full screen experience is documented in `docs/ai/album-person-detail.md`.
- Detail runtime work is keyed by group, not Timeline bucket, and always runs through `MosaicWorkScheduler`.
- `MosaicWorkScheduler` uses `DEFAULT_PARALLELISM = 1`, active owner priority, visible-group priority, and pending-work reprioritization.
- Runtime states are `InFlight`, `Partial`, and `RetryableFailure`; incomplete states render placeholders or strict partial output, never row-packing fallback.

## Detail Pause, Resume, And Retry

- Detail scroll start cancels deferred publish flushes but keeps checkpoints/chunks.
- Detail scroll settle schedules deferred group flush and incomplete Mosaic resume.
- Resume snapshots runtime state before iterating, filters to current keyed groups without final cache, and runs visible targets before prefetch/offscreen targets.
- Retryable failures resume only inside the visible prefetch window and use exponential backoff capped at `5000ms`.

## Detail Progressive Display And Publication

- Progress chunks update group state to `Partial` and project with `projectPartialSectionWithPlaceholders(...)`.
- Visible partial/ready groups may publish immediately; offscreen output may defer until scroll settles.
- `groupDisplayCache` is written only for final ready group output.
- Full in-memory `displayCache` is written only when every current group has final ready output, no runtime/deferred state remains, and the display contains no placeholders.
