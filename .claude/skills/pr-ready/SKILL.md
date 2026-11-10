---
name: pr-ready
description: Check a branch before opening a pull request or pushing to an open one. Use before `gh pr create`, before pushing new commits to a PR, or when the user asks whether a PR is ready.
allowed-tools: Bash(openspec:*), Bash(git:*), Bash(gh:*)
---

Run every step and report each one as pass or fail. Fix what fails before opening or pushing the PR.

1. **OpenSpec change archived.** If the branch adds or edits a change under `openspec/changes/`
   (outside `archive/`), it must be archived in this PR (`/opsx:archive`): every task in `tasks.md`
   ticked, and any acceptance or verification step actually run. Never archive an unticked
   "Not yet run".

2. **OpenSpec validates.** Run `openspec validate <change> --strict` for any unarchived change and
   `openspec validate --specs`. Every requirement needs `SHALL`/`MUST`, best in its opening sentence
   (older OpenSpec versions read only the first paragraph). New requirements go inside
   `## Requirements`, before any later `##` section such as `## Implementation notes`.

3. **Hygiene sweep.** Run the PCRE sweep from the `CLAUDE.md` "Public-repo hygiene" section over the
   files the branch changes, plus its multiline pass.

4. **PR title.** The repo squashes with the PR title only, so the title is the commit in history: a
   conventional-commit line (`feat:`, `fix:`, `docs:`, `!` for breaking) that follows the hygiene rules.

5. **PR description.** Use `.github/pull_request_template.md`:
   - `Applicable issues`: link a GitHub issue, or `n/a` for a small change.
   - `Description of changes`.
   - `Checklist` with the Apache 2.0 line.

   The PR page is public, so the hygiene rules apply to it, though GitHub `#N` links are fine. On an
   open PR, refresh the description after every material push: no stale "Draft", "not built yet" or
   "stacked on #N".

6. **Deferred work.** Every follow-up the PR mentions has its own GitHub issue, linked from the PR,
   not just a sentence in the description.

7. **Build.** `./gradlew clean build` passes on the branch head.
