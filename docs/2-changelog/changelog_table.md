# Changelog Table

| Version | Week | Commit Message |
| --- | --- | --- |
| `1.2.0` | 2 | release: ImmichGallery 1.2.0 |
| `1.1.0` | 2 | release: ImmichGallery 1.1.0 |
| `1.0.1` | 1 | chore: initialize TRIP workflow |

# Changelog Summary

- **v1.2.0 (Advanced Search - Week 2, 09-08-2026)**:
  - **Text search**: Added Context, Filename, Description, and OCR modes with
    capability-aware Smart and Metadata endpoint selection.
  - **People**: Added a searchable, authenticated-thumbnail multi-select
    Person picker with draft Cancel/Apply behavior and direct applied-chip
    removal.
  - **Filters**: Added Album, Tag/Untagged, camera, lens, location, date,
    media/motion, Favorite, Not-in-album, visibility, and Rating/Unrated
    controls in adaptive sheet/dialog editors.
  - **Reliability**: Added submitted-session pagination, exact omitted/null
    request semantics, lazy option loading, stale-request cancellation, and
    retryable failures without issuing blank unfiltered searches.
  - **Privacy and compatibility**: Corrected Immich's lens-suggestion wire
    value and sanitized suggestion-failure diagnostics so request-derived
    values are not logged.
- **v1.1.0 (Explore collections and viewer improvements - Week 2, 09-08-2026)**:
  - **Explore**: Replaced the Search tab with an Explore destination that
    retains smart and filename search and adds entry cards for Favorites and
    Memories.
  - **Favorites**: Added a live, authenticated-owner-filtered Favorites
    collection with guarded server pagination and retryable later-page errors.
  - **Memories**: Added current-local-date On This Day memory cards, preserved
    cover-card return behavior, and added width-aware continuously animated
    page progress with a segmented fallback for larger memories.
  - **Stacks**: Added read-only viewer-scoped stack discovery and nested stack
    browsing without modifying source grids, Timeline ordering, or Room.
  - **Reliability**: Preserved base-viewer state under modal stack layers and
    added observable delegated-runtime progress for the local TRIP workflow.
- **v1.0.1 (trip-initialization - Week 1, 09-08-2026)**:
  - **Setup**: Installed and adapted the project-local TRIP 1.0.0 workflow from
    the verified OpenTasks base.
  - **Documentation**: Added the system-level `docs/ARCHI.md`, architecture
    maintenance rules, changelog ledger, and testing guide.
  - **Workflow**: Added project-specific planning, implementation, release,
    review, testing, hotfix, research, compaction, and upgrade skills.
  - **Configuration**: Set ImmichGallery build, test, version, default-branch,
    platform-impact, and documentation gates.
