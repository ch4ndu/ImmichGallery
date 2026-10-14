# Kotlin PSI MCP: Expanded ImmichGallery Audit

Date: 2026-10-14

## 1. Executive decision

Kotlin PSI MCP 0.1.1 is a useful semantic-navigation supplement for ImmichGallery,
but its gradle-sync tool is not a working replacement for the IDE's normal
project import on the tested Android Studio build. The tool reports success
after Gradle resolution while disabling IDE project-data import. A retained
module control remained absent after MCP sync and appeared after a normal
manual IDE sync. Both the shipped plugin and installed IDE bytecode confirm
the mechanism.

Semantic reference searches, platform implementation discovery, and inferred
types remain valuable. They do not provide a complete impact graph, a strict
project fence, or an authoritative source-freshness certificate. Empty name
searches must not be interpreted as proof that a symbol does not exist.

The expanded audit also does not establish lower model-token usage. For the
same four Mosaic construction owners, the raw semantic payload was much larger
than a narrow rg result. Compact semantic locations can be small, but that
requires processing before the payload reaches the model.

Overall decision:

- Conditional use for location-based semantic navigation and typed references.
- Fail as an automatic Gradle model-import/freshness barrier in the tested binary.
- Fail as the sole authority for impact analysis, callback execution, and source
  revision correctness.
- No measured model-token or billed-usage saving is claimed.

This extends the [initial 0.1.1 pilot](research_2026-10-14_kotlin-psi-mcp.md)
and follows the same evaluation areas as the historical stock JetBrains MCP
assessment: topology, declarations, call sites, DI, data flow, impact,
efficiency, freshness, safety, and retained state.

## 2. Questions and acceptance conditions

1. Can MCP gradle-sync apply the changed Gradle model into Android Studio?
   Acceptance requires the expected module and roots to appear, not just a
   success response.
2. Does it repair external add/edit/rename/delete or branch-change freshness?
   Acceptance requires the expected source names, paths, and types after the
   change, with pre-change results preserved for comparison.
3. Can a client distinguish Gradle resolution, IDE import, VFS/PSI visibility,
   and indexing readiness? Each is scored separately.
4. Can two projects stay open while the selected project changes predictably?
   Acceptance requires canonical-root MATCH/MISMATCH and actual query results.
5. Does semantic usage search improve the difficult session/DI/Room questions
   from the historical audit? Named production paths must be checked against
   current source, without treating every reference as runtime execution.
6. Are tool responses smaller enough to justify a token-saving assumption?
   This audit measures serialized response bytes and selected locations;
   model-token savings require a separate end-to-end comparison.
7. Are source changes, temporary modules, processes, and approvals controlled?
   The real application source is preserved, temporary tracked edits restored,
   exact artifact paths retained, and Gradle daemons stopped.

## 3. Scope and evidence classification

Confirmed means a saved runtime response, inspected source, or installed bytecode
establishes the described behavior. Risk means a concrete mechanism exists but
its broader effect was not tested. Measurement needed means a claim such as
general recall, cold-index cost, or token saving has no adequate measurement.

The audit used an explicit HTTP call allowlist. It invoked gradle-sync and
diagnostics as separately authorized operations. It did not invoke rename,
safe-delete, move, extract-interface, formatting, import edits, or quick fixes.

Filesystem mutations were confined to the disposable clone: owned source
probes, one temporary Gradle module per control, settings include lines, and
two local branch-probe commits. No application source was changed in the real
repository. User-assisted native IDE controls were used for normal sync and
project selection; the audit did not automate native IDE UI.

## 4. Frozen environment and provenance

| Item | Recorded value |
| --- | --- |
| Plugin | Published Marketplace Kotlin PSI MCP 0.1.1 |
| Marketplace update | 1192532, plugin 33755 |
| Plugin ZIP SHA-256 | 06c5b68789ebba7e61562feace9c1ae36550187ea880d5887b9c7db9e3f4e3fc |
| Plugin source snapshot | 0410273193141989124c9861dd1d25db7844648e |
| Android Studio | AI-262.9437.185.2621.16467767 |
| Declared plugin compatibility | 251 through 262.* |
| Disposable source baseline | e47f91ab12afab312da643c9e9aa82d19a06a1ac |
| Original pre-audit HEAD | 02cb7df13672e1a2a8fb1b8fa98c54f3edf3f495 |
| Transport | HTTP JSON-RPC, 127.0.0.1:51234/mcp |
| Live tool inventory | 22 tools |
| Project | Kotlin 2.4.10, Android/iOS/JVM shared application |

Source compatibility declarations are not blanket runtime qualification across
all hosts. Results here apply to the exact installed build and binary above.
GitHub Releases still exposed 0.1.0 at the original inspection; Marketplace
0.1.1 and its packaged descriptor were independently verified.

## 5. Gradle sync: the confirmed import defect

The audit executed twelve MCP gradle-sync calls. All returned success:

- Ten in the broad batch: baseline; source add/edit/rename/delete; temporary
  module add/remove; branch B, branch A, and baseline restoration.
- One retained module/source control, observed at 0, 10, and 30 seconds afterward.
- One edit of an already resolvable function, with immediate and delayed checks.

In the broad batch, the ten callback durations ranged from 1.524 to 16.574
seconds. The model-changing module-add callback took 15.788 seconds. The
ordinary source/branch refresh callbacks were around 1.5-2.1 seconds.

These are callback durations, not verified IDE import/index completion times.
The first post-callback SMART_MODE sample was sometimes followed by DUMB_MODE
on subsequent semantic calls. A green callback plus one green readiness check
is therefore an inadequate completion barrier.

### Exact installed-code chain

1. Shipped GradleSyncTool constructs ImportSpecBuilder and calls its deprecated
   callback(customCallback) method.
2. The installed Android Studio ImportSpecBuilder.callback method sets
   myImportProjectData to false when that callback is non-null.
3. Installed ExternalSystemUtil.handleSyncResult checks
   ImportSpec.shouldImportProjectData before invoking ProjectDataManager.importData.
   With the flag false, it skips import and still invokes the success callback.
4. Mercari's callback only marks syncSuccess and releases its latch. It does
   not perform the omitted data import itself.

This mechanism was verified from the published plugin JAR and both relevant
installed platform JARs, not inferred solely from a changing upstream source
branch. Disassembly text and hashes are retained in the evidence directory.

The response "Gradle sync completed successfully" consequently overstates
what the current implementation establishes on this host. It establishes a
successful resolution callback; it does not establish updated IDE modules.

### Compatibility-aware fix concept, not an implementation

The installed host offers withCallback, which does not disable the import flag.
A fix must preserve normal import and observe a meaningful post-import
completion boundary. Do not assume withCallback exists or behaves identically
on every supported 251-262 host. A compatibility-tested adapter or supported
explicit import path is needed for the declared host range.

Even a corrected import flag cannot make source VFS refresh, document reload,
indexing, and revision identity equivalent to one callback. Those need their
own completion and verification contract. No plugin patch was made in this audit.

## 6. Retained module control: MCP versus normal IDE sync

The control added mcpAuditControl20261014, a small JVM-targeted KMP module,
plus one function in its source and one in each existing commonMain/commonTest
root. The expected module/source files and their hashes were recorded before
the test. The control remained in place across both sync methods.

| Observation | Module-search matches | New module function search |
| --- | ---: | ---: |
| Before MCP sync | 0 | 0 |
| Immediately after successful MCP sync | 0 | 0 |
| 10 seconds after MCP sync | 0 | 0 |
| 30 seconds after MCP sync | 0 | 0 |
| After normal manual IDE sync/indexing | 1 | 1 |

The user ran the IDE's normal Sync Project with Gradle Files action in the
disposable window. No intervening module/source edit was made. The manual
control isolates the missing model-import step and corroborates the installed
bytecode mechanism.

The commonMain/commonTest functions remained absent from find-symbols even
after the manual import, despite successful location-based declaration and
type lookup. This is a separate name-search/freshness limitation, discussed
below; it must not be attributed entirely to the Gradle import flag.

## 7. External source mutation observations

The broad batch added, edited, renamed, and deleted one owned source file in
both commonMain and commonTest. It recorded expected disk hashes, status,
symbol results, and resolved types before and after each successful MCP sync.

| Mutation | Immediate behavior around MCP sync |
| --- | --- |
| Add | Both new files were initially reported missing; name searches returned no matches |
| Edit/type/name change | Missing-name results persisted; one type query later saw Int while other operations raced into indexing |
| File rename | New-path lookups were missing or retriable immediately afterward |
| Delete | Names were absent; this alone is weak evidence because those names had never been positively discovered |

These observations prove that the implemented sync/readiness sequence did not
establish expected source state. They are not a statistical recall score or
a complete eventual-convergence measurement. Delete absence is not credited
as strong freshness success when the preceding positive discovery failed.

The separate existing-function control closes the important stale-content case:

| Existing probeDirect declaration | Reported return type |
| --- | --- |
| Verified original fixture | kotlin.String |
| After disk changed it to return Int | kotlin.String |
| Immediately after successful MCP sync | kotlin.String |
| 10-second post-sync check | kotlin.Int |
| After diagnostic reload | kotlin.Int |
| After restoring disk and diagnostic reload | kotlin.String |

The old type was a normal success response, without an authoritative stale
marker. The Int result converged between the immediate and ten-second samples.
Because the experiment did not isolate passive file observation from sync,
it does not prove that MCP sync caused that eventual update.

## 8. Actual disposable-branch changes

Two local test branches were created in the disposable clone using its
configured Git identity. Branch A introduced a String-returning probe; branch
B changed the function name and return type to Int. The audit switched B -> A
-> the original detached baseline and called MCP sync at each stage.

All three callbacks succeeded. Immediate branch-probe discovery/type checks
did not establish the expected new branch state. The file was newly introduced
and had not been positively discovered by the index, so this is not a clean
measurement of a previously indexed old-branch symbol disappearing. It is
evidence that the current callback/readiness recipe failed to establish the
branch's source state.

Both test commits and branches are retained for reproducibility. The checkout
was restored to e47f91a; no tracked disposable changes remain. No branch was
deleted and no reset/stash operation was used.

## 9. Index readiness and retry behavior

check-sync-status reports selected canonical root and DumbService state. Its
SMART_MODE statement means "not indexing at this instant," not "all intended
changes have been observed and indexed."

Several relationship probes first returned explicit retriable DUMB_MODE
responses after a prior SMART_MODE check. Those responses were retained and
not scored as missing references. A bounded read-only rerun used two-second
retries only for explicit retriable failures, with a maximum of fifteen attempts.
The successful subsequent results are used for the semantic corpus below.

This avoids LLM polling while still distinguishing temporary unavailability
from a legitimate empty result. It does not provide a model/VFS/index revision
identifier or make normal-looking stale success responses detectable.

## 10. KMP implementation and topology coverage

The initial pilot found all three platform actuals for createHttpClientEngine,
platformModule, and rememberScreenWakeLock. The expanded audit also found the
Android/iOS/JVM PlatformVideoPlayer implementations. That is 12/12 expected
actual implementation files across four existing families.

The original twelve declaration-file discovery result remains the initial
three-family find-symbols corpus. The fourth family's common declaration was
a known input location; do not claim a new sixteen-file symbol-search score.

module-search exposed imported common, Android, iOS, JVM, and test roots. It
groups source-set modules into Gradle identities and reports direct dependencies
and dependents. It does not expose a complete transitive source-set impact graph.

No Swift/Xcode relationship discovery or native target compilation is claimed.

## 11. Name search: confirmed narrow discovery misses

After normal IDE import and later explicit diagnostics reload:

- New shared commonMain function: find-symbols returned success with count 0.
- New shared commonTest function: success with count 0.
- New JVM-module function: success with count 1.
- Location-based declaration/type lookups resolved all three functions.
- Existing shared probeMessage overloads returned count 2, and probeDirect
  returned count 1 in the stabilized rerun.

This is a confirmed limitation for the tested newly added shared functions,
not a claim that all shared top-level functions are undiscoverable. The source
enumerates candidate names through PsiShortNamesCache before consulting Kotlin
stub indexes for matching candidates. That is a plausible contributor; its
exact role in these misses was not independently instrumented.

Operational consequence: fall back to a precise file/location or rg search
when a known symbol returns an empty name-search result. Treat absence from
find-symbols as insufficient evidence of absence from the project.

## 12. DI constructor contracts and platform bindings

Whole get() expressions in AppModule resolved all four expected public
ReplaceServerSessionAction constructor dependencies:

1. ServerConfigRepository
2. SessionStateRepository
3. ServerScopedImageCache
4. ServerScopedStateClearer

This is useful semantic type inference. Clicking the bare get identifier may
give no value type, and declaration lookup still resolves Koin's generic Scope.get.
The tool does not directly prove qualifiers, binding lifetime, or the selected
registration instance supplying the consumer.

LocalNetworkAccessController usage search returned 14 references and included
the typed binding sites in Android, iOS, and JVM platform modules. Source
verification established the real Android controller and the no-op iOS/JVM
bindings. This improves the initial discovery question that the historical
stock bridge did not consistently answer.

Implementation search also returned concrete interface implementers, including
test code. Implementers are distinct from the platform registration sites;
counts must not be interpreted as one unique implementation per platform.

## 13. Session ownership and data-flow corpus

| Target | Semantic references | Required source paths found |
| --- | ---: | --- |
| SessionOperationStamp | 110 | SessionStateRepository, SessionOperationRunner, manual-sync Action/coordinator, Timeline runtime/ViewModel/queue, Android foreground service, and tests |
| ReplaceServerSessionAction | 10 | AppModule, LoginViewModel, and its tests |
| ServerScopedStateClearer | 5 | AppModule, logout/session replacement Actions, and tests |
| LocalNetworkAccessController | 14 | All three platform binding modules, Android/no-op implementations, Actions, and tests |

These counts include the tool's reference categories and are not universal
recall percentages. They identify useful source locations; temporal ordering,
generation admission, owner epochs, cache commits, and publication rules still
require source reading.

Current source establishes the session replacement sequence: obtain current
configuration; normalize/compare; enter the mutation boundary; rotate image
cache around server-state clearing; persist replacement configuration.
The public constructor adapts repositories/services into function-valued
collaborators used by invoke. The semantic tools do not return this as a
complete structured dependency/data-flow graph.

ServerScopedStateClearer captures seven concrete cleanup callbacks: Timeline,
Albums, People, Assets, Detail Mosaic, Timeline Mosaic, and Immich API session
state. The clear call tree showed forEach, not those seven concrete callback
targets. Source inspection is decisive for that relationship.

## 14. Impact and call-tree boundaries

The initial Mosaic reference query found all four named production construction
owners, including the Album/Person detail coordinator, and commonTest uses.
AssetEntity references included AppDatabase, the three asset-related DAOs,
repository preparation/change detection/cache paths, mapping, and tests.

Those are positive direct-reference results. Room SQL strings, exported schema,
migration behavior, injected runtime instances, typed-route behavior, and Swift
host relationships remain separate impact categories. No general 90% impact
coverage score is claimed.

The focused call-tree fixtures reproduced:

- A callable reference reported as a caller despite no invocation there.
- Deferred lambda and nested-function bodies attributed at the enclosing level.
- A Kotlin constructor omitted from an outgoing tree.
- Real application callback indirection not resolved to concrete cache-clearing
  or session-mutation operations.

For ReplaceServerSessionAction.invoke, the returned tree included the result
helper, trimEnd, and normalized. It omitted the concrete mutation/image-cache/
clear/persist collaborators invoked through captured function values.

These are source-navigation trees. They cannot be used as complete execution,
callback, or DI graphs. Source additionally shows bounded child counts,
global visited-node pruning, and swallowed search exceptions; the broader
effects of those mechanisms were not stress-tested.

## 15. Multiple open projects and selection

Both same-named ImmichGallery projects were open during the expanded audit.
check-sync-status enumerated their different canonical paths. The initial
selected root was the disposable clone. After the user changed selection:

- Original root: MATCH.
- Disposable clone root: MISMATCH.
- Queries in the original resolved original source.
- The server was left with the original project selected for normal work.

Therefore, one open project is not required. One selected project is served
at a time. The fixed machine port still permits one server-owning IDE process;
the second-process bind-conflict path was inspected in source, not exercised
through a second plugin installation.

root_project_path only checks selection. It does not bind subsequent calls
to a request-scoped project or immutable source generation.

## 16. Project fencing and nested-checkout contamination

The routing checks found different tool boundaries:

- With the clone selected, find-usages on the original AssetEntity path returned
  not indexed by the selected project.
- With the clone selected, get-type-info nevertheless resolved kotlin.Boolean
  from an original-project function outside that selected root.
- With the original selected, find-usages accepted the nested clone's entity
  path and returned 13 Markdown references. It did not reject the foreign
  checkout as a uniform root fence would require.

The last case is affected by audit placement: the clone lives under the original
repository's .local tree, inside the ancestor project's broad content boundary.
The original query found 230 total references, returned its limit of 200, and
reported truncation. Returned references included Markdown files inside the
nested research checkout. That count is not comparable to the clone's earlier
71-reference query as a semantic recall improvement.

This does not prove arbitrary filesystem access through every tool. It does
prove that canonical selection plus one tool's content check is not a uniform
access/relevance fence. get-type-info lacks the content-root guard used by
find-usages. Project content roots also differ from Gradle source roots and
tracked application files.

Future isolated benchmarks should use a trusted disposable checkout outside
the indexed repository. Normal clients should validate target paths and returned
locations against the intended checkout/source roots, and exclude research/docs
when answering production code-use questions. No IDE exclusion settings were
silently changed during this audit.

## 17. Diagnostics and reload behavior

get-diagnostics has a real file reload mechanism: VFS refresh, document/PSI
reload from disk, document commit, and PSI cache dropping. It is not a pure
read of the current unsaved editor state. Only saved, owned disposable probes
were passed to it; unsaved-buffer overwrite was not behaviorally tested.

The initial valid/invalid/missing controls behaved as described in the pilot:
the intentional return mismatch was reported, and a missing file error lived
inside an outer success:true batch response.

The expanded existing-file control produced another anomaly. After the small
return-type edit, diagnostics emitted nine unresolved-reference reports for
names declared in the same fixture, including label and probeMessage. After
restoring the fixture and reloading again, it reported zero errors. Both source
variants have those declarations; the transient reports are retained as a
diagnostic-stability observation, not erased or presented as an app-build result.
The exact cache/index cause was not established by a separate compiler comparison.

Point type lookup had already converged to Int before the diagnostic call.
Consequently, the experiment does not attribute that Int convergence to reload.
The restored String response was verified after reloading restored disk content.

Diagnostics reload did not repair the two shared-function name-search misses.
VFS/PSI readability, index name discovery, model import, and compiler/inspection
reports are distinct observations.

## 18. Efficiency and token-volume evidence

The initial mixed navigation batch's timings remain in the pilot. New sync
callbacks cost seconds rather than fractions of a second and cannot be charged
as successful freshness barriers just because they returned promptly.

For the same four current production Mosaic construction owners:

| Representation | Serialized text bytes |
| --- | ---: |
| Whole original semantic usage payload | 20,985 |
| Narrow rg construction query output | 748 |
| Extracted relative semantic construction locations | 600 |
| All 22 tool definitions | 25,574 |

These are serialized payload/text sizes, not tokenizer counts, HTTP billing,
quota usage, or full engineering-session cost. The raw semantic payload includes
imports, tests, and Markdown. The rg query is narrower and does not provide
the same overload/reference guarantees. Their text sizes answer a bounded
payload question, not a universal tool-quality comparison.

The 600-byte representation was computed after extracting the relevant locations.
The server does not offer that representation as a compact construction-owner
mode. It saves model context only if a client/wrapper performs the extraction
before the model receives the full response. Having the model read 20 KB and
then summarize it does not undo those input tokens.

The raw semantic payload is approximately 28 times the narrow rg output here.
Tool definitions add another overhead whose repetition/caching depends on the
client. No 25% relationship-efficiency improvement or end-to-end token saving
is established. Useful semantic answers can reduce discovery work, but lower
total model usage must be measured separately with equal tasks and correctness.

## 19. Transport, tool exposure, and errors

The live HTTP controls returned:

- Native loopback health request: 200.
- Foreign Host header: 403.
- Browser Origin header: 403.

This verifies the tested request-guard behavior. Loopback binding and absence
of browser CORS are supported by source. No LAN exposure test or arbitrary
browser automation was attempted.

The plugin still exposes all 22 tools, including editing/refactoring and
Gradle sync. No stock per-tool disable or server-side read-only setting exists
in the inspected implementation. The audit's client allowlist is call discipline,
not proof that other local clients cannot invoke exposed tools. No credential
authentication boundary was observed in the transport.

Clients must parse the inner JSON, not rely on MCP isError. Normal returned
failures, empty results, per-file errors, retries, and truncation have different
meanings. The audit preserved explicit indexing failures and only retried
those marked retriable within a bounded loop.

## 20. Gate score and confidence

| Gate | Result | Evidence |
| --- | --- | --- |
| Exact binary/IDE qualification | Pass for this host | Descriptor, initialization, installed bytecode |
| Multiple projects open with selected root | Pass | Two roots enumerated and user selection change verified |
| Strict project/path fence | Fail as a uniform guarantee | Outside-root typing and nested peer reference acceptance |
| KMP implementation discovery | Pass for named corpus | 12/12 expected actuals across four existing families |
| Known constructor type inference | Pass | Four whole get() expressions resolved expected types |
| Direct Mosaic construction-owner discovery | Pass for named corpus | All four production owners found |
| Semantic session/platform-binding references | Pass for named paths | Current usage results and source oracle |
| New shared-function name discovery | Fail for two probes | Empty search despite declaration/type success |
| Complete callback/DI/data-flow graph | Fail | Concrete captured operations omitted from call trees |
| MCP Gradle model import | Fail | Import flag suppressed; manual module control passes |
| Immediate external-edit freshness | Fail | Successful stale type response and missing source observations |
| Eventual source freshness | Partial observation | Existing type converged between immediate and 10s checks |
| Authoritative freshness marker | Fail | No source/index/model revision proof |
| Read-only server surface | Fail | Mutation tools exposed; client allowlist only |
| HTTP request guards | Pass for tested cases | 200/403/403 |
| Token/cost improvement | Measurement needed | Byte counts only; raw payload larger in one task |
| Cold index time/per-project size | Measurement needed | Existing shared IDE caches used |
| Original app source preservation | Pass | Only research documentation changes in real checkout |
| Disposable tracked restoration | Pass | Detached baseline restored; tracked diff empty |
| Gradle process cleanup | Pass | Two daemons stopped |

The gate matrix deliberately mixes confirmed passes, failures, and measurement
boundaries. Completed scripts with exit_code=0 mean probe execution completed;
they do not mean every plugin capability passed.

## 21. Recommendations and remaining measurements

1. Keep semantic navigation as a supplement. Prefer location-based declaration,
   usage, implementation, and type lookup once the intended source is visible.
2. Do not rely on published 0.1.1 gradle-sync for automatic IDE model import.
   Use a normal IDE sync until a compatibility-tested plugin fix is available.
3. Separate model import, file reload, index readiness, and semantic-state
   equality. A corrected callback alone cannot certify all four.
4. Treat empty name searches as inconclusive and use precise locations or rg
   when a known shared symbol is missing.
5. Enforce target/result source-root filtering in a client integration. Keep
   testing checkouts outside the real project's indexed content boundary.
6. Use a small tool set and bounded/compact results. Measure equal-task model
   input/output/reasoning and cached categories before claiming token savings.
7. Keep diagnostic reload separate from navigation, save relevant buffers, and
   inspect per-file/analyzer outcomes. Retain normal build/test verification for
   implementation work.

Unmeasured areas remain explicit: cold isolated indexing, per-project cache
size, same-build stock-262 comparison, full model-token A/B accounting, second
IDE bind conflict, unsaved-buffer reload behavior, and broad statistical KMP/
reference recall. The branch trial is not credited as a clean old-index
invalidation benchmark because its new file was not first positively indexed.
No device/simulator or application build was necessary for the verified sync
mechanism, and none is claimed.

## 22. Cleanup, retained state, and verification

The original project remains selected in Kotlin PSI MCP. Both projects may
remain open. The user ran normal IDE sync for control cleanup before changing
selection. The clone's settings and owned control source/build files were
restored/removed with hash checks. Its tracked diff is empty and its detached
HEAD is again e47f91a.

The initial four valid pilot fixtures, downloaded ZIP, raw logs, disassembly,
two branch-probe commits, build/cache artifacts, and IDE-generated untracked
Gradle daemon settings are retained. No branch or cache directory was deleted.
Two Gradle daemons were stopped with the clone's ./gradlew --stop. The user-
installed IDE plugin/server is intentionally retained. No permanent Codex MCP
configuration was written and no changes were pushed.

verify-expanded-results.py checked the twelve successful callbacks, failed
MCP module control, successful manual import control, source/type observations,
semantic path oracle, request guards, routing asymmetry, and tracked restoration
against saved evidence. Completion receipts were verified and marked handled.

The project workflow's advisory follow-up ran in the existing verified thread
with the pinned model/effort. Its distinction between resolution/import/VFS/
indexing and its cautions about post-extraction bytes were incorporated.
No application architecture or feature behavior rules changed.

## 23. Evidence inventory

All relative paths below are under .local/research/kotlin-psi-mcp-pilot-20261014:

- runs/expanded-audit-01: ten sync callbacks, source/module/branch snapshots,
  four inferred types, HTTP guards, and initial routing/volume records.
- runs/sync-control-01: retained setup, 0/10/30-second observations, manual
  control, owned-file hashes, and cleanup receipt.
- runs/semantic-expansion-01: first probes including indexing failures.
- runs/semantic-expansion-02: bounded retry results used for semantic scoring.
- runs/refresh-controls-01: diagnostic reload, existing-file edit/restore,
  outside-root typing, and transient diagnostic reports.
- runs/routing-final-01: final original-root selection and peer path results.
- verified-expanded-results.json: checked facts and bytecode text hashes.
- GradleSyncTool-installed.javap.txt: published plugin call to callback.
- ImportSpecBuilder-installed.javap.txt: installed callback clears import flag.
- ExternalSystemUtil-installed.javap.txt: installed conditional import and
  subsequent success callback.
- ExternalSystemUtil-upstream.java / ImportSpecBuilder-upstream.java: supporting
  public platform source, distinct from installed-bytecode proof.

Advisory artifacts are under
.local/trip/runtime/invocations/ask/kotlin-psi-mcp-20261014-host/follow-up/round-02.
The historical stock-261 memo remains
.local/research/android-studio-mcp-cmp-evaluation.md.

## 24. Primary references

- [Published Marketplace 0.1.1](https://plugins.jetbrains.com/plugin/33755-kotlin-psi-mcp/versions/stable/1192532)
- [Mercari GradleSyncTool at inspected commit](https://github.com/mercari/kotlin-psi-mcp/blob/0410273193141989124c9861dd1d25db7844648e/src/main/kotlin/com/mercari/psi/mcp/tools/GradleSyncTool.kt)
- [Mercari project selection](https://github.com/mercari/kotlin-psi-mcp/blob/0410273193141989124c9861dd1d25db7844648e/src/main/kotlin/com/mercari/psi/mcp/PsiMcpServerManager.kt)
- [Mercari usage search](https://github.com/mercari/kotlin-psi-mcp/blob/0410273193141989124c9861dd1d25db7844648e/src/main/kotlin/com/mercari/psi/mcp/tools/FindUsagesTool.kt)
- [Mercari symbol search](https://github.com/mercari/kotlin-psi-mcp/blob/0410273193141989124c9861dd1d25db7844648e/src/main/kotlin/com/mercari/psi/mcp/tools/FindSymbolsTool.kt)
- [Mercari diagnostics](https://github.com/mercari/kotlin-psi-mcp/blob/0410273193141989124c9861dd1d25db7844648e/src/main/kotlin/com/mercari/psi/mcp/tools/GetDiagnosticsTool.kt)
- [JetBrains ImportSpecBuilder source](https://github.com/JetBrains/intellij-community/blob/master/platform/external-system-api/src/com/intellij/openapi/externalSystem/importing/ImportSpecBuilder.java)
- [JetBrains ExternalSystemUtil source](https://github.com/JetBrains/intellij-community/blob/master/platform/external-system-impl/src/com/intellij/openapi/externalSystem/util/ExternalSystemUtil.java)
- [OpenAI MCP tool/context guidance](https://developers.openai.com/api/docs/guides/tools-connectors-mcp)
