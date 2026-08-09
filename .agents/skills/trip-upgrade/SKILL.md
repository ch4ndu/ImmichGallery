---
name: trip-upgrade
description: Safely prepare, validate, and apply a TRIP suite upgrade
---

# TRIP Upgrade Mode

## Initialization Gate

Before any action, require `.agents/trip/initialized.json`. If it is absent, stop and invoke `trip-init`; do not generate or apply an upgrade proposal.

You are now in **upgrade mode** — merging a newer version of the TRIP workflow into this project's existing, customized TRIP skills.

## The Problem

Each project's TRIP skills have two interleaved layers:
1. **Workflow skeleton** — steps, Sol review/final-gate integration, private runtime phases, file structure, process flow
2. **Project customizations** — test commands, checklist sections, version file, technical considerations, guidance sections

A naive copy would destroy layer 2. This skill separates both layers, applies the new skeleton, and re-injects the customizations.

## Prerequisites

The global installer stages the new generic base before this skill runs. Default location: `.local/trip/upgrade/<version>/`.

If `$ARGUMENTS` is provided, treat it as the path to that staging folder. Otherwise select the newest `.local/trip/upgrade/<version>/` folder. It must contain `handoff.json` plus `new-base/skills`, `new-base/runtime`, and `new-base/bin`.

---

## Phase 1: Inventory

### 1.1 Validate Staging Folder

Confirm the staging folder exists and contains TRIP skills:

```bash
ls -R <staging-path>/
```

If missing or empty, tell the user:
> "No staging folder found at `<path>`. Copy the new TRIP workflow's `skills/` folder there first, then re-run."

### 1.2 Categorize Skills

List all skill folders in both locations:

```bash
# Currently installed public skills and private runtime
ls -d .agents/skills/trip-*/ .agents/trip/runtime/*/

# New (staging)
ls -d <staging-path>/*/
```

Categorize each skill into one of:

| Category | Meaning | Action |
|----------|---------|--------|
| **New** | Exists in staging only | Copy directly |
| **Removed** | Exists in installed only | Warn user, leave in place |
| **Unchanged** | Identical in both | Skip |
| **Updated — pure workflow** | Changed, but no project customizations | Replace directly |
| **Updated — customized** | Changed, AND contains project-specific content | Extract → merge → replace |

**Pure workflow skills** (no project customizations): `trip-compact`, `trip-research`, `trip-init`, `trip-upgrade`. The four helper workflows are private runtime modules: `plan-review`, `implementation`, `code-review`, and `ask`.

**Exception — role pins**: do not carry arbitrary model overrides into the new runtime. Preserve Sol `gpt-5.6-sol/xhigh` for plan and code review, Sol `gpt-5.6-sol/high` for implementation and the final gate, and Terra `gpt-5.6-terra/xhigh` for research unless the user explicitly changes this project policy; the launcher fails closed on mismatch.

**Customized skills** (have project-specific content): `trip-1-plan`, `trip-2-implement`, `trip-3-release`, `trip-review`, `trip-test`, `trip-hotfix` (carries `[MAIN_BRANCH]` only)

**Renamed in TRIP v2** — when the installed folder uses an old name, treat it as the same skill under its new name (merge into the new name, then delete the old folder):

| Installed (old) | Staging (new) |
|---|---|
| `trip-3-review` | `trip-review` |
| `trip-4-test` | `trip-test` |

`trip-3-release` is **new in v2** but its project values (version file, week anchor, tutorial config) are **extracted from the old `trip-2-implement`'s post-implementation steps** — categorize it as customized even though no folder exists yet.

**Other non-TRIP skills** in staging (e.g. future additions): warn that they are outside this suite version and leave them out of the proposal. The apply helper accepts exactly the ten owned public skills.

For each skill, diff the installed vs new version to confirm whether it actually changed:

```bash
diff -rq .agents/skills/<skill>/ <staging-path>/new-base/skills/<skill>/
```

### 1.3 Present Inventory

Show a summary table to the user:

```
Skill                 | Status              | Action
--------------------- | ------------------- | ------
trip-1-plan           | Updated (customized) | Extract + merge
trip-2-implement      | Updated (customized) | Extract + merge
trip-3-release        | New (customized)     | New template + values from old trip-2
trip-review           | Renamed + updated    | Extract + merge, delete trip-3-review/
trip-test             | Renamed + updated    | Extract + merge, delete trip-4-test/
trip-compact          | Unchanged            | Skip
trip-hotfix           | Unchanged            | Skip
trip-init             | Updated (pure)       | Replace
trip-research         | Unchanged            | Skip
private runtime        | Updated safely        | Candidate runtime + phase validation
```

`request_user_input`: "Here's the upgrade plan. Proceed?"
Options: "Yes, start upgrade" (recommended) / "Let me review the new files first" / "Abort"

---

## Phase 2: Extract Project Context

Before touching any installed files, read every customized skill and extract all project-specific values into a context block. This is your safety net — everything here gets re-injected later.

### 2.1 Read All Installed Skills

Read every file in the installed skills directory that will be affected.

### 2.2 Extract Customizations

Build a context block by extracting these values from the installed skills:

**From trip-1-plan/SKILL.md:**
- `PROJECT_NAME` — the text that replaced `[PROJECT_NAME]` (appears in the `**planning mode** for` line; the `# Planning Mode` header itself is bare)
- `TECHNICAL_CONSIDERATIONS` — the full content of the `## Technical Considerations` section in the plan template (everything between `## Technical Considerations` and the next `##` heading)
- `GUIDANCE_SECTIONS` — everything after the plan template's closing section that replaced `[ADAPT_TO_PROJECT: Guidance Sections]` (project-specific per-component guidance at the bottom of the file)

**From trip-2-implement/SKILL.md** (in v1 installs, the release values below live in its Post-Implementation steps; in v2 installs they live in `trip-3-release/SKILL.md`):
- `PROJECT_NAME` — (confirm matches trip-1-plan)
- `VERSION_FILE` — the text that replaced `[VERSION_FILE]` in Step 2
- `WEEK_ANCHOR_EPOCH` — the value that replaced `[WEEK_ANCHOR_EPOCH]` in Step 1 (older installs used a `[WEEK_ANCHOR_DATE]` date string — convert to the Monday-midnight epoch seconds)
- `MAIN_BRANCH` — the branch name in the Step 11 fast-forward merge (also used by `trip-hotfix` Steps 2 and 8)
- `TUTORIAL_CONFIG` — if tutorials are enabled: the full Tutorial step block with user context. If disabled: note "tutorials disabled"
- `LINT_COMMAND` — if present (may not exist in older versions)
- `TYPECHECK_COMMAND` — if present
- `TEST_COMMAND` — if present

**From trip-review/SKILL.md — or `trip-3-review/` in v1 installs (checklist.md if already split):**
- `REVIEW_CHECKLIST` — the full checklist content. In older versions this is inline in SKILL.md. In newer versions it's in `checklist.md`. Extract it wherever it lives.
- `CR_TEMPLATE` — if `cr-template.md` exists, extract it. Otherwise note "no template file — using inline template"

**From trip-test/SKILL.md — or `trip-4-test/` in v1 installs:**
- `TEST_COMMANDS` — the full Commands section
- `TEST_STRUCTURE` — the test structure description
- `TESTING_PRIORITIES` — the full testing priorities section

### 2.3 Present Extracted Context

Show the user a summary of what was extracted:

```
Extracted project context:
- Project name: [name]
- Version file: [path]
- Week anchor: [date]
- Tutorials: [enabled/disabled]
- Test commands: [lint] / [typecheck] / [test]
- Checklist sections: [count] sections ([list names])
- Guidance sections: [count] sections ([list names])
- Technical considerations: [count] items
```

`request_user_input`: "Extracted project context looks correct?"
Options: "Yes, continue" / "No, let me correct something"

If "No": let the user specify corrections, update the context block.

---

## Phase 3: Handle Structural Migrations

Before merging, handle any structural changes between the old and new workflow versions. Read both old and new files to detect what changed structurally.

### 3.1 Checklist Extraction (trip-review)

**Old structure** (early v1): Checklist inline in `trip-3-review/SKILL.md`
**New structure** (v2): Checklist in separate `trip-review/checklist.md`, template in `trip-review/cr-template.md` (late-v1 installs have these same files under `trip-3-review/`)

If the installed version has the checklist inline in SKILL.md (no separate `checklist.md`):
1. The extracted `REVIEW_CHECKLIST` from Phase 2 is the project-customized checklist
2. It will be injected into the new `checklist.md` in Phase 4

If the installed version already has `checklist.md`:
1. The extracted content is already in the right format
2. Merge normally in Phase 4

### 3.2 Sol Review Integration (trip-1-plan, trip-2-implement)

**Old structure**: No delegated review steps
**New structure**: trip-1-plan has Step 3 (Sol plan review), trip-2-implement has a Sol Code Review section

These are pure workflow additions — no project-specific content to migrate. They will be applied from the new template. The only project-specific part is the test commands in trip-2-implement's Sol review pre-step, which come from the extracted context.

### 3.3 Private Sol/Terra Runtime Modules

If not installed yet, these are entirely new private assets — include them only through the staged runtime. The code-review phases reference `trip-review/checklist.md` and `trip-review/cr-template.md`, which will be populated with project content. If already installed, stage them with the candidate runtime; legacy paths and one-line collapsed prompts must not survive validation.

---

## Phase 4: Merge & Apply

For each skill, build the appropriate action from the Phase 1 inventory, but do not alter installed files in this phase. The deterministic three-way proposal below replaces all manual copy/delete commands.

```bash
python3 .agents/trip/bin/merge_upgrade.py \
  --base .agents/trip/base/<installed-version>/skills \
  --current .agents/skills \
  --new-base <staging-path>/new-base/skills \
  --output <staging-path>/proposal
```

### 4.1 New Skills — Copy Directly

```bash
printf '%s\n' 'The proposal helper stages new skills; do not copy directly.'
```

Mutable invocation records stay under `.local/trip/`; never create public-skill runtime-record directories.

### 4.2 Pure Workflow Skills — Replace Directly

```bash
printf '%s\n' 'The approved apply helper replaces only suite-owned paths transactionally.'
```

### 4.3 Customized Skills — Extract + Merge

For each customized skill, take the **new template** from staging and inject the **extracted project context** from Phase 2. This is the core of the upgrade.

**General approach**: Read the new template file. Find each placeholder or generic section. Replace with the corresponding extracted value in the proposal, never in the current installation. Resolve conflicts before any apply.

#### trip-1-plan/SKILL.md

1. Start from the new template (staging)
2. Replace `[PROJECT_NAME]` with extracted `PROJECT_NAME`
3. Replace the generic `## Technical Considerations` block in the plan template with extracted `TECHNICAL_CONSIDERATIONS`
4. Replace the `[ADAPT_TO_PROJECT: Guidance Sections]` comment block with extracted `GUIDANCE_SECTIONS`

#### trip-2-implement/SKILL.md

1. Start from the new template (staging)
2. Replace `[PROJECT_NAME]` with extracted `PROJECT_NAME`
3. Replace `[LINT_COMMAND]`, `[TYPECHECK_COMMAND]`, `[TEST_COMMAND]` in the Testing Gate with extracted commands
   - If the old version didn't have delegated review (no test commands extracted), check the old trip-4-test for test commands, or ask the user
4. Adapt the Integration impact check comment block to the project's integration/E2E tooling (from the old trip-4-test content if present)

#### trip-3-release/SKILL.md (new in v2 — values come from the old trip-2)

1. Start from the new template (staging)
2. Replace `[PROJECT_NAME]` with extracted `PROJECT_NAME`
3. Replace `[VERSION_FILE]` with extracted `VERSION_FILE`
4. Replace `[WEEK_ANCHOR_EPOCH]` with extracted `WEEK_ANCHOR_EPOCH`
5. Replace `[MAIN_BRANCH]` with the repo's default branch name
6. Replace the standalone-verification commands with the same extracted lint/typecheck/test commands
7. Handle tutorial config:
   - If tutorials were disabled: remove the `[TUTORIAL_STEP]` block
   - If tutorials were enabled: replace the `[TUTORIAL_STEP]` block with extracted `TUTORIAL_CONFIG` and renumber subsequent steps

#### trip-hotfix/SKILL.md

1. Start from the new template (staging)
2. Replace `[MAIN_BRANCH]` (Steps 2 and 8) with extracted `MAIN_BRANCH`

#### trip-review/SKILL.md + checklist.md + cr-template.md (was `trip-3-review` in v1)

1. `SKILL.md`: Start from the new template. Replace `[PROJECT_NAME]`.
2. `checklist.md`: Start from the new template. Replace the `[ADAPT_TO_PROJECT]` comment block with the project-specific checklist sections from the extracted `REVIEW_CHECKLIST`.
   - The new template has generic sections 1-3 (Functional, Code Quality, Architectural) and 4-6 (Error Handling, Security, Performance). The project customization goes between section 3 and 4 (where the comment marker is), and may also modify sections 3-6.
   - If the old checklist had custom sections (numbered 4+), insert them at the `[ADAPT_TO_PROJECT]` marker and renumber if needed.
   - Preserve the Severity Classification and Approval Gate from the **new** template unless the project had custom overrides.
3. `cr-template.md`: Start from the new template. Update the Checklist section names to match the actual sections in the merged `checklist.md`.

#### trip-test/SKILL.md (was `trip-4-test` in v1)

1. Start from the new template (staging)
2. Replace `[PROJECT_NAME]` with extracted `PROJECT_NAME`
3. Replace `[TEST_COMMAND_*]` placeholders with extracted `TEST_COMMANDS`
4. Replace test structure placeholder with extracted `TEST_STRUCTURE`
5. Replace testing priorities placeholder with extracted `TESTING_PRIORITIES`

### 4.4 Write All Files

After building all merged content in memory, write only `<staging-path>/proposal/merged/`. Do NOT write partial results to the installed suite.

---

## Phase 5: Validate

After producing the proposal, run a validation pass. A nonempty conflict list blocks the apply path.

### 5.1 Placeholder Check

Scan the proposed upgraded skill files for leftover placeholders:

```bash
grep -rn '\[ADAPT_TO_PROJECT\|\[PROJECT_NAME\]\|\[VERSION_FILE\]\|\[WEEK_ANCHOR_DATE\]\|\[WEEK_ANCHOR_EPOCH\]\|\[TEST_COMMAND\|\[LINT_COMMAND\]\|\[TYPECHECK_COMMAND\]\|\[TUTORIAL_STEP\]\|\[MAIN_BRANCH\]' <staging-path>/proposal/merged/trip-*/
```

If any are found, fill them from context or ask the user.

### 5.2 Cross-Reference Check

- `checklist.md` section names must match `cr-template.md` checklist section names
- `.agents/trip/runtime/code-review/phases/{start,resume,synthesize}.md` reference `.agents/skills/trip-review/checklist.md` and `cr-template.md` where required — confirm they exist and no template points at `trip-3-review/`.
- `trip-1-plan`, `trip-2-implement`, and `trip-research` invoke only `.agents/trip/bin/launch_runtime.py` with a validated private `--phase`.
- Each private module config lists its source-derived phases, and launch metadata cannot mark dry-run or unverified model/effort as verified.

### 5.3 Present Summary

Show what changed:

```
Upgrade proposal ready:
- New skills added: [list]
- Skills updated: [list]
- Skills unchanged: [list]
- Project customizations preserved: [list key ones]
```

`request_user_input`: "The conflict-free upgrade proposal is validated. Apply it transactionally?"
Options: "Apply validated upgrade" / "Show me the proposal diff" / "Abort"

If "Show me the proposal diff": compare `<staging-path>/proposal/merged/` with `.agents/skills/` and present it.
If "Abort": leave the installed suite and staging intact.

---

## Phase 6: Clean Up

Only after the user explicitly selects “Apply validated upgrade”:

1. Apply the proposal transactionally:
   ```bash
   python3 .agents/trip/bin/apply_upgrade.py \
       --project . --proposal <staging-path>/proposal --approve
   ```

2. Validate the rotated manifest/base snapshot and report the result. Keep staging for audit; do not delete it automatically.

---

## Edge Cases

### Old version has no delegated review skills at all
This is the most common upgrade path. The private runtime modules are new and are staged with the generic base. The Sol review integration in trip-1-plan and trip-2-implement comes from the new template and needs no project-specific content except test commands.

### Old version has inline checklist but no separate files
The structural migration in Phase 3.1 handles this. Extract the custom checklist content from the old SKILL.md, inject into the new `checklist.md`.

### Old version already has the new structure
Everything categorizes as "Unchanged" or minor updates. The merge is trivial.

### Project has extra custom skills not in the new workflow
These are "Removed" in the inventory — warn the user but leave them in place. Never delete skills that exist only in the installed version.

### Test commands not available anywhere
If the old version predates the delegated review pre-step and trip-4-test doesn't have extractable commands, ask the user:

`request_user_input`: "The new workflow needs lint/typecheck/test commands for Sol code review. What are the commands for this project?"
Options: "Let me provide them" (user types commands) / "Abort upgrade" (do not leave placeholders)

---

## Notes for the Agent

- **Read before writing.** Read every file you plan to modify. Never write from memory alone.
- **Preserve semantics, not bytes.** If the old checklist had 10 custom sections, they all need to survive, even if their numbering changes.
- **New workflow features get project context.** When Sol code review is new, the test commands still need to be filled from the project's existing test setup.
- **When in doubt, ask.** If you can't confidently extract a customization, show the user the relevant section and ask what to keep.
- **Atomic application.** Build all merged content before writing any files. If something goes wrong mid-merge, the installed skills should still be intact.
- **Never delete user-created files.** If the project has extra files in a skill directory (like project-specific fixtures or notes), leave them alone.
