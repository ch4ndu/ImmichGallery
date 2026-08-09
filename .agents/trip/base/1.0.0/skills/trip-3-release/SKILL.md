---
name: trip-3-release
description: Release a verified implementation with explicit confirmation
---

# Release Mode

## Initialization Gate

Before any action, require `.agents/trip/initialized.json`. If it is absent, stop and invoke `trip-init`; do not bump versions, write release documents, commit, tag, merge, or push.

You are now in **release mode** for **[PROJECT_NAME]**.

Release: $ARGUMENTS

This skill runs after `trip-2-implement` has converged (implementation done, testing gate green, Terra code review `APPROVED` or explicitly skipped). It is normally chained from trip-2 in the same session, but can be invoked standalone in a fresh session.

---

## Prerequisites

- Implementation complete and user-confirmed.
- Testing gate green: affected unit tests pass.
- Terra code review converged (`APPROVED`), or explicitly skipped by the user.
- Sol final gate completed with a metadata-verified `gpt-5.6-sol/high` session and `APPROVED`.
- Lint and type-check/build green.

### Standalone verification (fresh session, not chained from trip-2)

If this skill was NOT chained from a trip-2 session in the current conversation, verify before any release step:

```bash
[LINT_COMMAND]
[TYPECHECK_COMMAND]
# Plus the affected suite(s) filtered per the plan's Test Impact section:
[TEST_COMMAND]
```

All must be green. Also verify a completed, metadata-verified Terra review invocation and an approved, metadata-verified Sol `final-gate` invocation exist under `.local/trip/runtime/invocations/code-review/` for the given plan path/label (see Step 3 below). A missing Terra review may use the explicitly skipped manual-CR fallback; a missing or unapproved Sol final gate blocks release.

Any failure blocks the release — fix or return to `trip-2-implement` first.

---

## Step 1: Get Current Date/Week

Run this command to get date and project week (`[WEEK_ANCHOR_EPOCH]` is the epoch-seconds timestamp of the Monday of the project's Week 1 — trip-init fills it in; the formula is portable across BSD/macOS and GNU/Linux):

```bash
date '+%d-%m-%Y %H:%M' && echo "Project week: $(( ( $(date +%s) - [WEEK_ANCHOR_EPOCH] ) / 604800 + 1 ))"
```

Use the project week in all subsequent steps.

## Step 2: Version Update

- If not already done in the plan phase, propose new SemVer version (x.y.z, with optional pre-release suffix such as `-alpha1`)
- Update the version in `[VERSION_FILE]`
- Do not modify anything else in this file

## Step 3: Promote Code Review

Now that week (`a`) and version (`x.y.z`) are known:

1. Locate the completed Terra review body for the target under `synthesize/round-*` (multi-round) or `start/round-*` (Turn 1), and the newest Sol decision under `final-gate/round-*`. Read each `metadata.json` and `final.txt`; require `selected.verified: true` with the phase's exact model/effort.
   ```bash
   find .local/trip/runtime/invocations/code-review -path '*/synthesize/round-*/metadata.json' -o -path '*/start/round-*/metadata.json' -o -path '*/final-gate/round-*/metadata.json'
   ```

2. Content source:
   - **Multi-round loop**: the synthesized invocation's final report has the consolidated review + `PROMOTION_READY`. Strip sentinel.
   - **Turn 1 convergence**: the verified invocation's final report has the full review already.
   - **Skipped Terra**: write CR from `.agents/skills/trip-review/cr-template.md` with body "Code review skipped — trivial change." Verdict: `APPROVED with observations`.

   In every case, the Sol `final-gate` output must end in `APPROVED`; otherwise return to `trip-2-implement`.

3. Replace `<x.y.z>` with actual version. Fill any remaining `<...>` placeholders.

4. Save to `docs/3-code-review/CR_wa_vx.y.z.md`.

5. Verify: no `<...>` placeholders, no `PROMOTION_READY`, version matches version file.

## Step 4: Commit Message

Propose a one-line commit message.

## Step 5: Changelog File

Create `docs/2-changelog/wa_vx.y.z.md` (a=project week, x.y.z=version):

```markdown
# Changelog - Week a, DD-MM-YYYY, V. x.y.z

**Release Date**: Week a, DD-MM-YYYY at HH:MM
**Version**: x.y.z (previously x0.y0.z0)
**Object**: the commit message
**Code review**: `docs/3-code-review/CR_wa_vx.y.z.md` (Terra loop, N rounds -> verdict)

## Changes

[Describe what changed]
```

## Step 6: Changelog Table

Add entry on top of `docs/2-changelog/changelog_table.md`:

```markdown
| `x.y.z` | a | the commit message |
```

Also add a summary entry in the Changelog Summary section.

## Step 7: Architecture Update

1. Read fully @docs/ARCHI-rules.md
2. Update @docs/ARCHI.md following the rules
3. Run `bash .agents/trip/bin/count-tokens.sh docs/ARCHI.md` to check token count

**Warning: If ARCHI.md exceeds ~20,000 tokens**, warn the user:

> "ARCHI.md is at ~X tokens. Consider running `trip-compact` to reduce it before committing."

[TUTORIAL_STEP]

<!-- trip-init either removes the [TUTORIAL_STEP] block above (tutorials
disabled — the step numbers below are already correct) or replaces it with a
"### Step 8: Tutorial" section carrying the user's learning context, then
renumbers the following steps: README 8→9, Commit 9→10, Tag 10→11,
Merge 11→12, Push 12→13. -->

## Step 8: README Update

Update `README.md` with the new version number.
Also update relevant sections whenever needed.

---

After completing all documentation steps, **use the `request_user_input` tool** to ask:

- **Question**: "All documentation steps are complete. Ready to commit?"
- **Options**: "Yes, commit now" (proceed with git commit and tag), "Not yet" (review changes first)

**ONLY after user selects "Yes"**, proceed:

## Step 9: Commit

```bash
git add -A && git commit -m "<commit message from Step 4>"
```

**Important**: Only use the commit message. Do NOT add Co-Authored-By or any other trailer.

## Step 10: Tag

```bash
git tag vx.y.z
```

## Step 11: Merge (fast-forward)

Merge the feature branch back into the main branch, keeping a single clean linear history:

```bash
git checkout [MAIN_BRANCH]
git merge --ff-only <feature-branch>
git branch -d <feature-branch>
```

If `--ff-only` fails, the main branch moved during implementation — rebase the feature branch onto it, then retry. **Never create a merge commit.**

## Step 12: Push

**Use the `request_user_input` tool** to ask:

- **Question**: "Release vx.y.z is committed, tagged, and merged. Push to remote?"
- **Options**: "Yes, push now" (push branch and tags), "Not yet" (push manually later)

**If "Yes"**:

```bash
git push && git push --tags
```
