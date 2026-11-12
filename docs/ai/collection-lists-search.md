# Collection Lists, Explore, Search, Favorites, And Memories Architecture

Load this when changing the Albums list, People list, People name filtering,
Explore, Search modes/pagination/result layout, Favorites, or Memories.
Album/Person detail is documented separately in
`docs/ai/album-person-detail.md`.

## Albums List

- `AlbumListViewModel` observes `GetAlbumsUseCase.observe()` as the Room-backed
  source of truth and starts a server sync when created.
- With no cached albums, the screen shows the blocking preparation/error path.
  With cached albums, it keeps the grid visible while syncing and shows a
  dismissible banner on failure.
- The main top-bar refresh callback runs the same sync path.
- Startup and refresh use one screen-local sync gate; duplicate refresh/startup
  requests while a list sync is active are ignored until the active sync
  releases the gate.
- `AlbumRepository.syncAlbums()` replaces the Album list transactionally. It
  also clears relations, Detail Mosaic artifacts, and detail sync metadata for
  owners removed by the server.
- `AlbumListScreen` is a fixed three-column collection grid. Cards show the
  authenticated cover thumbnail, name, and server asset count; selecting a card
  navigates to `AlbumDetailRoute`.
- Album list cards do not expose photo-row or Mosaic controls.

## People List

- `PeopleViewModel` observes `GetPeopleUseCase.observe()` from Room and starts a
  server sync when created.
- Startup and refresh use one screen-local sync gate; duplicate refresh/startup
  requests while a People sync is active are ignored until the active sync
  releases the gate.
- `PeopleRepository.syncPeople()` requests `withHidden=true` and follows every
  `/api/people` page in memory, then replaces the list only after the complete
  fetch succeeds. Hidden rows remain in Room so the raw accumulated count
  matches `total`; the DAO keeps them out of the visible list. Explicit
  `hasNextPage=true` continues, explicit `false` is terminal only when the raw
  count equals `total`, and an omitted flag continues while the count is below
  `total`. Empty/duplicate/no-new-ID progress, over-count, or early terminal
  responses are compatibility failures that preserve the current list and
  relationships. Removed owners clear their detail refs, Detail Mosaic
  artifacts, and detail sync metadata in the same successful transaction.
- Empty-cache and cached-refresh error behavior matches Albums.
- Name filtering is local, case-insensitive, and derived from the Room-emitted
  list on `Dispatchers.Default`; editing the filter does not call the server.
- People scroll resets only after a changed query's matching filtered result
  publishes. Room refreshes for the same query preserve the keyed scroll anchor,
  and initial/restored composition does not force the grid back to the top.
- `PeopleScreen` uses a fixed three-column collection grid with circular
  authenticated face thumbnails. Selecting a person navigates to
  `PersonDetailRoute(personId, personName)`.
- The People list does not preload Person Detail assets and does not expose
  photo-row or Mosaic controls.

## Search Requests

- `ExploreRoute` replaces the old nested Search route and label while retaining
  `SearchScreen`, `SearchViewModel`, Search density, modes, pagination, saved
  navigation state, and viewer behavior.
- The blank idle Explore state starts below the compact Search controls with
  People, Albums, conditional Tags, and Date filter shortcuts plus compact
  Favorites and Memories entries. The entries have no descriptive prose and
  reflow from a row to a stack when width or text size requires it. A typed but
  unsubmitted query leaves the result area empty; executed Search states keep
  the loading/error/empty/results behavior. Clearing restores idle discovery.
- Explore cards perform no collection request. The corresponding ViewModel is
  created only after navigating to root `FavoritesRoute` or `MemoriesRoute`.

- Search is live and network-backed. Results are held in `SearchState`; there is
  no Search Room table or offline result cache.
- Server Search features and user preferences load independently for the
  `SearchViewModel` lifetime and remain explicitly retryable. Confirmed
  `search=false` blocks execution; Context and OCR require positive feature
  knowledge. Filename, Description, and filter-only Metadata Search remain
  usable while optional feature or preference reads load or fail.
- People and Album options observe the existing Room-backed list UseCases as
  soon as `SearchViewModel` starts. Each existing sync runs once when its picker
  first opens, keeps cached values visible during refresh/failure, and supports
  explicit retry. Search does not inject the collection screen ViewModels or
  add entity caches.
- Tags load only after their preference is positively enabled and their editor
  is requested. If that request happens while preferences are loading, the
  lazy Tag read starts after the positive result. Tag failure retains any
  previously loaded options and remains retryable.
- Country, State, City, Camera Make, Camera Model, and Lens Model suggestions
  load lazily. Requests retain only supported normalized parent dependencies;
  successful responses cache by that full tuple for the ViewModel lifetime.
  A changed tuple cancels the older request for the same suggestion type, and
  only the current tuple may publish. Nullable values map to explicit Unknown,
  while duplicate non-null values keep server order.
- Search input and filters normalize into immutable `SearchCriteria` before a
  request starts. Inactive request fields are omitted; explicit Unknown,
  Untagged, and Unrated constraints are encoded as JSON `null`.
- A nonblank Context query posts `query` to `/api/search/smart`. Filename,
  Description, OCR, and filter-only criteria post to `/api/search/metadata`
  with the matching endpoint-specific text field, when text is present.
- Applied criteria remain separate from the submitted `SearchSession`. The
  submitted session freezes normalized criteria, endpoint, generation, current
  page, next page, and consumed pages for the complete paginated result set.
- Both endpoints request 50 items per page and accept only the server-returned
  `nextPage` token. Only supported `IMAGE` and `VIDEO` responses enter the
  domain result list.
- Editing the query or changing mode cancels search, load-more, and row-layout
  work; increments the request generation; and clears the executed result set.
- Search results, load-more results, and RowPacking publication are accepted
  only while normalized criteria, endpoint, and generation still match the
  submitted session.
- A new search replaces results. A successful load-more appends results and
  de-duplicates asset ids in first-seen order. Successfully decoded pages are
  consumed even when they contain no supported assets; repeated, backward, or
  already-consumed server tokens terminate pagination.
- Initial request failure shows the Search error state. Load-more failure keeps
  existing results and makes the server token manually retryable. The footer
  shows a resource-backed failure message and Retry; a new attempt or criteria
  change clears that feedback without discarding the retained results on retry.

## Advanced Search Controls And Editors

- Search starts with one compact text field, an explicit arrow submit action,
  IME submission, and a clear action. Its second row contains a descriptive
  **Search by** choice surface and a labeled **Filters** action with the active
  category count. Meaning maps to the existing Context request mode; Filename,
  Description, and Text in photos map to the existing metadata modes. Context
  and OCR remain disabled until positively supported, and capability failure
  remains retryable without changing selected text.
- The expanded choice surface remains inline and uses one column below 600dp
  and two columns at wider widths. Its choice body alone scrolls within a
  viewport-derived height cap; the compact controls, applied chips, and a
  usable result or discovery region remain outside that scroll on short or
  enlarged-text layouts.
- Executed results do not retain a permanent category strip or sidebar.
  Applied People remain individually removable; larger selections use the
  first resolved value plus a count. Missing option rows retain an unavailable
  label and remain removable.
- Filters and idle discovery shortcuts open one atomic draft editor. Widths
  below 600dp use a `ModalBottomSheet` with horizontal category navigation;
  wider layouts use a bounded dialog with side navigation. Categories are
  People, Albums, conditional Tags, Date, Media, Camera, Location, conditional
  Rating, and Display. Switching categories preserves the complete draft and
  each entity search query. Apply submits one normalized snapshot. Cancel,
  Back, Escape, outside click, and sheet swipe discard the draft without
  changing results. A reversed date range disables Apply even after navigating
  away from Date.
- People/Album syncs, Tag reads, and Camera/Location suggestion reads start
  only when their category is first visited. Preference failure remains visible
  and retryable from the editor even though Tags and Rating require a positive
  preference; immediately observed cached People/Album options and existing
  per-source retry behavior remain intact.
- People, Albums, and Tags support local case-insensitive search, stable
  selected-first ordering while the query is blank, stable-id multi-selection,
  Clear, and selected-state visibility. People use circular authenticated face
  thumbnails with Unknown fallback; Albums use authenticated covers and counts;
  Tags use full hierarchy values, optional colors, and mutually exclusive
  Untagged. Multiple selected entity values retain Immich intersection semantics.
- Camera and Location expose Any plus dependent suggestions. Unknown appears
  only when the server includes it or the current selection is already Unknown,
  including while a failed refresh retains that selection.
  Country clears State/City, State clears City, Make clears Model/Lens, and
  Model clears Lens in the draft before new dependency-keyed reads publish.
  Suggestion failure does not clear the selected draft value.
- Date uses separate calendar pickers for start and inclusive end. Reversed
  drafts remain visible and disable Apply; start-only, end-only, same-day, and
  leap-day selections remain valid. Media exposes All/Image/Video and
  Motion-only; Motion implies Image and Video clears Motion. Display owns
  Favorite, Not in album, and All Accessible/Timeline/Archive visibility.
  Rating exposes Any, Unrated, and one through five only when enabled.
- Removing an applied chip immediately submits current visible text/mode plus
  the remaining criteria. Removing the final criterion with blank text cancels
  the session and restores the Explore cards. Results, paging, RowPacking,
  viewer/stack behavior, and return targeting are otherwise unchanged.

## Search Pagination And Layout

- Search uses standard `packIntoRows(...)`; it never uses Mosaic.
- Width, viewport bounds, and the Search-scoped saved row-height preference are
  owned by `SearchViewModel`. Pinch zoom and Ctrl/Cmd-wheel desktop zoom update
  that preference and schedule guarded RowPacking work.
- Near-end detection can automatically attempt one request per
  request-generation/page pair. It will not repeatedly auto-retry a failed
  page.
- `loadMore()` atomically claims the submitted session and exact next-page
  token. An unrelated state publication retries that claim without a fixed
  attempt limit; every retry rechecks the same session/page eligibility,
  duplicate guard, and invalid-token termination before starting one request.
- Ordinary pagination is automatic and has no **Load more** control. A failed
  continuation keeps the current results and exposes an explicit Retry. A
  terminal marker remains measurable even when a page contributes no supported
  assets, so sparse or unsupported-only pages continue through the same
  generation/page guard and server `nextPage` tokens without an auto-retry loop.
- Search has no main-shell refresh action because executing the query is its
  refresh boundary.

## Search Media Viewer

- Selecting a result opens `StaticPhotoOverlay` over the current ordered result
  list.
- Opening uses explicit `Found`/`Pending`/`Absent` source resolution. A pending
  or paginated result source keeps the open request waiting, while a complete
  absent source cancels the open request instead of falling back to page zero.
  Resolution is keyed by the search request generation, ordered result ids,
  loading state, and continuation state.
- The overlay fetches expanded asset information on demand and may navigate to
  a recognized person's detail screen.
- On dismiss, `lastViewedAssetId` maps the current asset back to its current
  RowPacking row so an off-screen return source can be made visible before the
  shared-element exit. Search awaits that source preparation before dismissing
  the host overlay.
- Shared media-viewer behavior lives in `docs/ai/media-viewer.md`.

## Favorites

- Favorites is live-only. Opening `FavoritesRoute` resolves `/api/users/me`,
  then requests `/api/search/metadata` with `isFavorite=true`, descending order,
  page size 50, and `withStacked=true`. Immich interprets
  `withStacked=false` as excluding every asset with a stack id, including the
  stack primary, so Favorites must not use that value. Metadata-search results
  do not carry nested stack metadata; the viewer resolves it lazily through the
  shared asset-detail/stack preflight.
- Metadata search may include timeline-enabled partner assets. Every page is
  filtered by `AssetResponse.ownerId` before supported image/video mapping;
  `isFavorite` is not trusted for ownership because partner values may be
  masked in the response.
- One logical load continues across partner-only or unsupported-only pages
  until owned supported assets or a terminal token is reached. Successfully
  decoded pages are consumed even when they contribute no visible asset.
- `FavoritesLoadOutcome` carries visible assets, the next valid server page,
  and cumulative consumed pages. `FavoritesLoadException` preserves cumulative
  consumed pages, the first unconsumed retry page, a permission/request kind,
  and the original cause. Cancellation remains control flow.
- Repeated, backward, malformed, and already-consumed next tokens are terminal
  after retaining any owned supported assets on the current page. Later-page
  failures keep existing visible results and expose the first unconsumed page
  through the explicit **Load more** retry.
- `FavoritesViewModel` generation-guards owner/page reads and row projection,
  atomically claims load-more work, and owns a separate Favorites row-density
  setting. Favorites uses justified rows only, plus the shared return-source,
  static viewer, Info, slideshow, person navigation, and stack session.
  Opening uses explicit `Found`/`Pending`/`Absent` source resolution and never
  substitutes page zero for a missing favorite. Owner/request identity,
  ordered asset ids, loading state, and continuation/retry state determine
  whether a missing id is pending or authoritatively absent. Dismiss awaits
  return-source preparation before the host exit.

## Memories

- Memories is live-only. Opening `MemoriesRoute` derives a `LocalDate` from
  `Clock.System.now()` and `TimeZone.currentSystemDefault()`, then requests
  `GET /api/memories?for=YYYY-MM-DD`. Retry recomputes the date; there is no
  background midnight timer or request from Explore/Timeline.
- `GetMemoriesForDateUseCase` keeps only `on_this_day` responses with supported
  image/video assets, preserves first-occurrence asset order, and removes
  duplicate memory/asset ids. Cards are sorted by descending `data.year` while
  separate memories within one year retain server order.
- `MemoriesViewModel` generation-guards initial and retry reads and exposes
  permission, unavailable-server, and transient failures as typed states.
  Memories never writes Room or marks a memory seen, saved, hidden, or changed.
- The screen is an above-shell lazy list of large cover cards. Each card uses
  the first supported asset and shows the year plus actual supported count.
  Selecting it opens only that memory's ordered assets through
  `StaticPhotoOverlay`, retaining Info, slideshow, person navigation, mixed
  image/video playback, and viewer-scoped stacks.
- Memory viewers opt into origin return: paging to a non-cover asset still
  hides and exits to the selected cover. Opening uses explicit
  `Found`/`Pending`/`Absent` source resolution and never substitutes page zero
  for a missing memory asset. Resolution is keyed by request generation,
  pending/error state, selected owner, and every loaded memory's ordered asset
  ids. A failed read stays pending so an explicit Retry can recover the saved
  viewer; only a successful terminal source can declare the cover absent.
  After host-state restoration, the selected memory is recovered by its saved
  cover. Only when that owner no longer exists may resolution search loaded
  memory covers in display order; it never transfers the origin to a non-cover
  asset or away from a surviving owner. A nested stack closes to the unchanged
  memory pager before the base viewer exits to that cover.

Desktop detached slideshow requests capture Search's submitted endpoint/criteria,
not editable draft filters, and independently finish pagination. Favorites reuses
the current-owner and sparse-page loader. A Memories request snapshots the selected
memory's complete assets, not all memories for the date. These requests do not
retain the collection screen; window lifecycle and full preparation are specified
in `media-viewer.md`.

## Verification Targets

- Album and People cached content remains visible when refresh fails;
- Album and People startup/refresh syncs are single-flight;
- People name filtering is local and case-insensitive;
- a People list sync follows all pages and does not publish a partial replace;
- removed Album/Person owners clear owner-scoped detail data transactionally;
- query/mode changes reject stale network and row-layout results;
- load-more is duplicate-guarded, follows the server token, advances across
  sparse pages automatically, and retries a failed page only by explicit Retry;
- Search remains RowPacking-only, does not page-zero fallback on open, and
  restores the currently viewed asset row on overlay dismissal.
- Search option tests cover independent feature/preference failure, positive
  Context/OCR gates, Room-observed People/Albums with cached failure, Tag
  enablement/lazy retry, normalized suggestion caches, and stale dependent
  suggestion rejection.
- Presentation tests cover selected-first entity projection, local filtering,
  Unknown/duplicate identities, compact summaries, conditional sections,
  draft Cancel/Apply, dependency conflicts, and final-filter Explore return.
- Explore cards appear only in blank idle Search state and never prefetch;
- Favorites excludes partner assets, crosses sparse pages, preserves typed
  retry progress, rejects stale/duplicate work, and returns to the settled
  asset's justified row.
- Memories derives the correct local date around UTC midnight, keeps stable
  descending-year cards, retries typed failures, and returns every pager page
  to its cover after any nested stack closes.
