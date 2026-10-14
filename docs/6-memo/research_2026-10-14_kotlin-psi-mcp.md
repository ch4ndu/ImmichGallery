# Research: Mercari Kotlin PSI MCP for ImmichGallery

Date: 2026-10-14

## Decision

Kotlin PSI MCP 0.1.1 is useful for semantic reference discovery, location-based
navigation, Kotlin Multiplatform implementation discovery, and inferred types
in ImmichGallery. The published plugin ran successfully on the installed
Android Studio 262 build. Use it as an additional source-navigation tool;
continue direct source inspection for change impact, Koin registration wiring,
Room SQL/schema effects, navigation behavior, and the Swift host.

The live pilot found all four known production Mosaic construction owners,
all nine platform implementations across three existing expect/actual
families, and the correct targets for two overloads. It also reproduced
misleading call-tree relationships and external-edit freshness failures.
SMART_MODE is an indexing-state check, not a disk-freshness guarantee.

This memo records research and a local runtime pilot. It does not adopt new
project workflow rules or configure a permanent coding-agent integration.

## Scope, versions, and provenance

| Item | Verified value |
| --- | --- |
| ImmichGallery baseline | `e47f91ab12afab312da643c9e9aa82d19a06a1ac` |
| Upstream source snapshot | `0410273193141989124c9861dd1d25db7844648e` |
| Installed Android Studio build | `AI-262.9437.185.2621.16467767` |
| Published Marketplace plugin | Kotlin PSI MCP 0.1.1, update 1192532, plugin 33755 |
| Downloaded ZIP size | 4,827,011 bytes |
| Downloaded ZIP SHA-256 | `06c5b68789ebba7e61562feace9c1ae36550187ea880d5887b9c7db9e3f4e3fc` |
| Packaged plugin ID | `com.mercari.psi.mcp` |
| Packaged compatibility | Since 251, until 262.* |
| Live MCP initialization | Version 0.1.1 |
| Live MCP tool inventory | 22 tools |
| Endpoint used | `http://127.0.0.1:51234/mcp` |
| Checkout used | `.local/research/kotlin-psi-mcp-pilot-20261014/repo` |

The Marketplace API and downloaded plugin descriptor confirmed 0.1.1. GitHub
Releases still exposed only the older 0.1.0 download at inspection time. Use
the Marketplace artifact for this 262 pilot. Source main and a published binary
are distinct provenance records; initialization, tools/list, and observed
responses establish the capabilities actually tested in the binary.

The user installed the downloaded plugin, restarted Android Studio, and opened
the disposable checkout. Its Gradle import was performed through the IDE. No
command-line Gradle build/test, app launch, device flow, or simulator flow was
run by this investigation. Only SDK location was copied into the clone's
local.properties; original development credentials were not copied.

## How the implementation works

The plugin embeds a Jetty HTTP server directly in Android Studio/IntelliJ.
There is no separate Kotlin compiler/index daemon to install. Requests travel
from the MCP client to a registered tool, which resolves the selected IDE
project and uses IntelliJ PSI, search indexes, and Kotlin's K2 Analysis API.
PSI is the IDE's structured representation of source code and references.

Most operations use an absolute/relative file path and 1-based line/column.
Relative paths resolve against the selected project's root. An MCP tool result
contains JSON as a text content item: callers must parse the inner JSON and
check its success field. MCP isError remains false for ordinary returned
success:false payloads; it becomes true only for thrown exceptions.

The important mechanisms in the inspected source are:

| Tool | Mechanism | Meaning for this repo |
| --- | --- | --- |
| find-declaration / find-usages | Kotlin mainReference resolution, K2 resolution fallback, and ReferencesSearch for reverse usages | Better location/overload precision than name-only text search |
| find-usages | Usage classification, snippets, containing declarations, totalCount/byType and truncation | Useful evidence for construction sites and tests; classification still needs interpretation |
| find-implementations | DefinitionsScopedSearch / OverridingMethodsSearch | Platform actuals worked in the tested KMP families |
| get-type-info | K2 expression/declaration type analysis | Inferred generic injection types are available at the right expression location |
| get-call-hierarchy | Custom reference-derived caller tree and PSI-walk callee tree | A source-navigation aid with callback/constructor limitations |
| module-search | IDE module graph, direct dependencies/dependents, grouped Gradle identities and roots | Provides imported project topology, not a complete source-set impact graph |
| check-sync-status | Selected-root comparison and DumbService indexing state | Identifies wrong checkout/indexing, but not stale disk state |

## Project and IDE restrictions

Multiple projects may be open in Android Studio. Exactly one project is served
at a time, chosen in Settings > Tools > Kotlin PSI MCP. A sole open project is
the automatic fallback. With several open projects and no valid selection,
the resolver returns no served project instead of choosing the first one.

One IDE process can own fixed port 51234 at a time. A second Android Studio or
IntelliJ process running the same plugin cannot bind that port; handoff is
manual. The stock JetBrains MCP bridge uses a different endpoint and is a
separate integration. A worktree or clone must itself be opened/imported/indexed;
it cannot use the original checkout's index just because the sources are alike.

root_project_path is a comparison input for check-sync-status, not a routing
parameter for subsequent calls. The other tools use application-wide project
selection. Recheck the canonical root when selection/open projects change.

The live preflight verified MATCH for the disposable checkout and MISMATCH
when given the original repository root. Its availableProjects list contained
only the disposable project. Multi-project selector switching and a second-IDE
port conflict were inspected in source, not behaviorally exercised.

## Runtime results

The investigation made 49 tool calls across three recorded batches: 28 in the
initial navigation batch, 5 in a focused follow-up batch, and 16 in the
freshness/diagnostics batch. These include readiness checks. There were 29
navigation probes plus the external-mutation and diagnostic checks.

| Probe | Observed result | Assessment |
| --- | --- | --- |
| MosaicRenderEngine usages | 28 results: 16 Kotlin and 12 Markdown references; all four production constructor owners and commonTest references present | Useful improvement for the known construction-owner question |
| Required Mosaic owners | PrepareTimelineMosaicCacheAction, RepairTimelineMosaicCacheAction, TimelineViewModel, PhotoGridDetailLayoutCoordinator | All 4/4 verified against source |
| AssetEntity usages | 71 results: 65 Kotlin and 6 Markdown; AppDatabase, AssetDao, AlbumDao, PersonDao, repository/mapping and test paths included | Good typed-reference coverage; not proof of SQL/schema impact completeness |
| Usage limit | limit=2 returned 2, totalCount=71, truncated=true | Explicit limit accounting worked |
| Three existing KMP families | createHttpClientEngine, platformModule, rememberScreenWakeLock: four declarations each and all three actuals each | 12/12 declaration files and 9/9 actuals found in this corpus |
| KMP from a usage location | find-implementations on the createHttpClientEngine call in AppModule returned all three actuals | Worked beyond declaration-position lookup for this call site |
| Koin type | Identifier get had no type; whole get() expression inferred ServerConfigRepository | Useful type information, position-sensitive |
| Koin declaration | get resolved to generic Scope.get in Koin metadata | Does not establish registration-to-consumer wiring |
| Typed route | AlbumDetailRoute returned 7 usages | References help locate registration/calls; runtime routing/back behavior remains a source question |
| Overloads | String and Int calls resolved to the two correct declarations | Passed |
| Negative literal | A string-literal position returned success:false, not an enclosing declaration | Honest miss; MCP isError remained false |
| Trailing lambda | Parameter usage search found its content() call and one synthetic trailing-lambda argument | Worked in the focused fixture |
| Diagnostic control | Valid fixture reported no errors; intentionally invalid String-returning function reported RETURN_TYPE_MISMATCH | Positive and negative file controls behaved as expected |
| Missing diagnostic file | Outer success:true; missing file carried error:File not found | Per-file results must be checked |

Markdown references appeared even with include_comments at its default false,
and were classified as read. Raw counts should not be described as counts of
production code usages. Filter by file kind, source set, and usage location
when answering a code relationship question.

### Call hierarchy limitations reproduced

The incoming caller tree labeled probeReference, which only returns
::probeMessage, as a caller of probeMessage. The outgoing tree attributed a
call inside a returned deferred lambda to the enclosing function. A nested
local function's calls also appeared at the outer function's first level.
Conversely, a simple Kotlin class constructor in probeFactory was omitted:
its outgoing tree had no children.

These results match the inspected implementation: caller edges come from
references within enclosing functions/properties without requiring direct
invocation; outgoing traversal walks nested PSI and accepts named functions,
Java methods, and accessor-backed properties, but not Kotlin class constructor
targets. Use these trees as discovery hints, not proof of execution order or
complete direct-call relationships. Source also shows swallowed search
exceptions and globally visited nodes that can prune repeated subgraphs.

### External-edit freshness limitations reproduced

The disposable source-only probe tested add, edit/symbol rename/type change,
file rename, and delete, with immediate status and semantic queries after each
mutation. No polling loop or automatic Gradle sync was used.

After add and edit, check-sync-status reported SMART_MODE, while the newly
written symbol was absent from find-symbols and get-type-info reported File
not found. Immediately after file rename, the new symbol was still absent.
After delete the IDE was observed in DUMB_MODE and symbol search returned a
retriable failure. After the separate diagnostics refresh, status was SMART_MODE
and the deleted symbol was absent.

This proves an immediate freshness gap, not a measured eventual-convergence
latency or a universal rename/delete recall rate. No per-response source hash,
index generation, or authoritative disk-snapshot marker was observed. Before
trusting semantic answers after external edits, use an explicit IDE reload or
sync as appropriate and validate that affected source/symbols are visible.
SMART_MODE alone is insufficient. Diagnostics' refresh is an intrusive
intervention and should not become an automatic general freshness workaround.

### Timings and value boundary

The 28 initial tool calls totaled approximately 3.856 seconds of HTTP call time,
with median 0.0147 seconds and maximum 0.7214 seconds on an already imported
project. Representative first calls were Mosaic usages 0.646s, AssetEntity
usages 0.375s, and initial Mosaic symbol search 0.721s. Cached/small follow-up
calls were much faster.

These are mixed local request latencies, not cold-index measurements or
end-to-end engineering time. The earlier stock JetBrains 261 pilot is historical
context; this is not a same-build A/B comparison against the current stock 262
bridge or a measured percentage improvement over rg/source reading.

## Tool exposure and diagnostics behavior

The live inventory exposed all 22 tools, including rename, safe-delete,
move-file, format-code, quick fixes, and gradle-sync. The inspected settings
provide a server on/off switch and project selection, but no server-side
read-only or per-tool disable mode. The navigation harness enforced its own
allowlist and did not invoke refactoring/edit/Gradle-sync tools.

The server binds IPv4 loopback and rejects non-loopback Host and any Origin
header. This is useful transport protection, but it does not make the endpoint
read-only or authenticate local callers. No enforcement test of disabled
mutation tools was possible because there is no such stock mode.

get-diagnostics refreshes and reloads supplied files from disk in an IDE write
action, commits documents, and drops PSI caches. Treat that as capable of
replacing unsaved document contents. Only disposable, newly created/saved test
files were supplied in this pilot. Unsaved-buffer behavior was not tested.
Some analyzer/inspection exceptions are swallowed in source, so an empty
diagnostic list is not a build or complete-analysis certificate.

## Recommended adoption plan

1. Keep 0.1.1 as a semantic-navigation candidate on this IDE build. Record the
   canonical selected checkout before each investigation and after IDE/project
   selection changes.
2. Start with find-declaration, find-usages, find-symbols, find-implementations,
   get-type-info, module-search, and KDoc/context tools. Parse inner success,
   retries, full-set counts, truncation, and result locations. Use whole call
   expressions when querying inferred generic types.
3. Establish explicit source reload/sync handling for external file changes.
   Reject an interpretation of SMART_MODE as freshness proof. Keep each coding
   worktree independently opened/indexed if it needs semantic assistance.
4. Use a trustworthy client tool allowlist before attaching the full plugin to
   routine autonomous work. The tested custom HTTP harness's call discipline
   is not a server-side read-only guarantee.
5. Treat call trees as hints and separately inspect callbacks, constructors,
   DI binding, SQL/schema/migration, typed-route behavior, and Swift/Xcode paths.
   Keep diagnostics separate from pure navigation and retain normal build/test
   verification for implementation work.
6. If deciding whether it should replace another integration, run a small
   same-checkout comparison against the current stock 262 bridge and rg/source
   reading. Measure time to a correct answer, manually opened files, missing
   relationships, retries, and response size. The current pilot supports using
   the semantic tools; it does not prove a general productivity percentage.

## Verification and retained state

verify-results.py checked the named production-owner oracle, Room reference
paths, usage truncation, all tested KMP families, overload targets, negative
positions, inferred type, observed call-tree limitations, and valid/invalid/
missing diagnostic outcomes against saved live responses. All completion
receipts had exit_code=0; this records completed probe execution, not universal
tool correctness. Expected limitations are retained in the results above.

No application source was changed in the real repository. Four valid probe
files remain only in the disposable clone; temporary freshness and invalid
diagnostic fixtures were removed by their owner script. The downloaded plugin,
clone, harnesses, raw responses, timings, and source checkout are retained for
reproducibility. Installation/selected-project state was changed by the user;
no permanent Codex MCP configuration was written by this investigation.

No app compile/build, device/simulator test, branch-switch trial, multi-project
selector-switch trial, second-IDE port-conflict trial, or current-stock-262 A/B
benchmark is claimed. No architecture or feature rules changed.

The project research workflow's independent source cross-check was completed
with its pinned Terra runtime and verified persisted model/effort metadata.
Its cautions about binary provenance, project selection, diagnostic reloads,
inner error handling, and non-call relationships were incorporated; runtime
observations above supersede its untested source hypotheses where applicable.

## Evidence locations

- Raw navigation: `.local/research/kotlin-psi-mcp-pilot-20261014/runs/navigation-01/`
- Focused follow-up: `.local/research/kotlin-psi-mcp-pilot-20261014/runs/supplemental-01/`
- Freshness/diagnostics: `.local/research/kotlin-psi-mcp-pilot-20261014/runs/freshness-diagnostics-01/`
- Verified facts: `.local/research/kotlin-psi-mcp-pilot-20261014/verified-results.json`
- Harness/manifest: `.local/research/kotlin-psi-mcp-pilot-20261014/`
- Source checkout: `/private/tmp/immichgallery-kotlin-psi-mcp-20261014`
- Advisory: `.local/trip/runtime/invocations/ask/kotlin-psi-mcp-20261014-host/start/round-01/`
- Earlier historical evaluation: `.local/research/android-studio-mcp-cmp-evaluation.md`

## Primary upstream references

- [Marketplace version 0.1.1](https://plugins.jetbrains.com/plugin/33755-kotlin-psi-mcp/versions/stable/1192532)
- [Marketplace update metadata](https://plugins.jetbrains.com/api/plugins/33755/updates?size=5)
- [GitHub Releases](https://github.com/mercari/kotlin-psi-mcp/releases)
- [Architecture at inspected commit](https://github.com/mercari/kotlin-psi-mcp/blob/0410273193141989124c9861dd1d25db7844648e/ARCHITECTURE.md)
- [Project selection and registration](https://github.com/mercari/kotlin-psi-mcp/blob/0410273193141989124c9861dd1d25db7844648e/src/main/kotlin/com/mercari/psi/mcp/PsiMcpServerManager.kt)
- [Readiness and freshness caveat](https://github.com/mercari/kotlin-psi-mcp/blob/0410273193141989124c9861dd1d25db7844648e/src/main/kotlin/com/mercari/psi/mcp/tools/CheckSyncStatusTool.kt)
- [Usage search](https://github.com/mercari/kotlin-psi-mcp/blob/0410273193141989124c9861dd1d25db7844648e/src/main/kotlin/com/mercari/psi/mcp/tools/FindUsagesTool.kt)
- [Custom call tree](https://github.com/mercari/kotlin-psi-mcp/blob/0410273193141989124c9861dd1d25db7844648e/src/main/kotlin/com/mercari/psi/mcp/tools/GetCallHierarchyTool.kt)
- [Diagnostics reload and aggregation](https://github.com/mercari/kotlin-psi-mcp/blob/0410273193141989124c9861dd1d25db7844648e/src/main/kotlin/com/mercari/psi/mcp/tools/GetDiagnosticsTool.kt)
- [Transport and error envelope](https://github.com/mercari/kotlin-psi-mcp/blob/0410273193141989124c9861dd1d25db7844648e/src/main/kotlin/com/mercari/psi/mcp/server/HttpServer.kt)
