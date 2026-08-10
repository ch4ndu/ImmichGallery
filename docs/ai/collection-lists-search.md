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
- `PeopleRepository.syncPeople()` follows every `/api/people` page in memory,
  then replaces the list only after the complete fetch succeeds. Removed owners
  clear their detail refs, Detail Mosaic artifacts, and detail sync metadata in
  the same transaction.
- Empty-cache and cached-refresh error behavior matches Albums.
- Name filtering is local, case-insensitive, and derived from the Room-emitted
  list on `Dispatchers.Default`; editing the filter does not call the server.
- `PeopleScreen` uses a fixed three-column collection grid with circular
  authenticated face thumbnails. Selecting a person navigates to
  `PersonDetailRoute(personId, personName)`.
- The People list does not preload Person Detail assets and does not expose
  photo-row or Mosaic controls.

## Search Requests

- `ExploreRoute` replaces the old nested Search route and label while retaining
  `SearchScreen`, `SearchViewModel`, Search density, modes, pagination, saved
  navigation state, and viewer behavior.
- The blank idle Explore state shows static Favorites and Memories entry cards.
  A typed query keeps the existing Search hint; executed Search states keep the
  existing loading/error/empty/results behavior. Clearing restores the cards.
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
  existing results and makes the server token manually retryable.

## Advanced Search Controls And Editors

- Query, compact mode selector, Tune, category chips, and applied summaries
  remain visible above Explore/results and are hidden only while the media
  overlay is active. Context and OCR are disabled until positively supported;
  capability failure remains retryable without changing the selected text.
- The category row prioritizes People, Albums, Date, Media, and Camera, then
  exposes Location, conditional Tags/Rating, and Display. Applied People are
  individually removable; larger selections use the first resolved value plus
  a count. Missing option rows retain an unavailable label and remain removable.
- Tune and category actions open the same draft editor. Widths below 600dp use
  a `ModalBottomSheet`; wider layouts use a bounded scrollable dialog. Apply
  submits one normalized snapshot. Cancel, Back, Escape, outside click, and
  sheet swipe discard the draft without changing results.
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
- Whenever `nextPage` remains non-null, the explicit **Load more** footer remains
  available, including when an API page contains no supported assets after
  filtering.
- Search has no main-shell refresh action because executing the query is its
  refresh boundary.

## Search Media Viewer

- Selecting a result opens `StaticPhotoOverlay` over the current ordered result
  list.
- The overlay fetches expanded asset information on demand and may navigate to
  a recognized person's detail screen.
- On dismiss, `lastViewedAssetId` maps the current asset back to its current
  RowPacking row so an off-screen return source can be made visible before the
  shared-element exit.
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
  hides and exits to the selected cover. A nested stack closes to the unchanged
  memory pager before the base viewer exits to that cover.

## Verification Targets

- Album and People cached content remains visible when refresh fails;
- People name filtering is local and case-insensitive;
- a People list sync follows all pages and does not publish a partial replace;
- removed Album/Person owners clear owner-scoped detail data transactionally;
- query/mode changes reject stale network and row-layout results;
- load-more is duplicate-guarded, follows the server token, and retries only by
  a new explicit/eligible attempt;
- Search remains RowPacking-only and restores the currently viewed asset row on
  overlay dismissal.
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
