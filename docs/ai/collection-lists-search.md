# Collection Lists And Search Architecture

Load this when changing the Albums list, People list, People name filtering,
Search modes, Search pagination, or Search result layout. Album/Person detail is
documented separately in `docs/ai/album-person-detail.md`.

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

- Search is live and network-backed. Results are held in `SearchState`; there is
  no Search Room table or offline result cache.
- Smart mode posts natural-language queries to `/api/search/smart`.
- Filename mode posts `originalFileName` to `/api/search/metadata`.
- Both endpoints request 50 items per page and accept only the server-returned
  `nextPage` token. Only supported `IMAGE` and `VIDEO` responses enter the
  domain result list.
- Editing the query or changing mode cancels search, load-more, and row-layout
  work; increments the request generation; and clears the executed result set.
- Search results, load-more results, and RowPacking publication are accepted
  only when query, mode, and generation still match current state.
- A new search replaces results. A successful load-more appends results and
  records the loaded page so a repeated server token cannot loop.
- Initial request failure shows the Search error state. Load-more failure keeps
  existing results and makes the server token manually retryable.

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
