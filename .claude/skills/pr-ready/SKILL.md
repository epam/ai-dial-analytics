---
name: pr-ready
description: Check a branch before opening a pull request or pushing to an open one. Use before `gh pr create`, before pushing new commits to a PR, or when the user asks whether a PR is ready.
allowed-tools: Bash(openspec:*), Bash(git:*), Bash(gh:*), Bash(grep:*), Bash(tr:*), Bash(./gradlew:*)
---

Run every step and report each one as pass or fail. Fix what fails before opening or pushing the PR.
`<base>` below is the PR's base branch as a remote ref, e.g. `origin/development` (use the remote
that points at the GitHub repository).

1. **OpenSpec change archived.** If the branch adds or edits a change under `openspec/changes/`
   (outside `archive/`), archive it in this PR (`/opsx:archive`). Every task in `tasks.md` is ticked
   first, and a verification task is ticked only once it has actually been run.

2. **OpenSpec validates.** Run `openspec validate <change> --strict` for any unarchived change, then
   `openspec validate --specs --strict`. Without `--strict` a missing `SHALL`/`MUST` is only a warning.
   - The keyword must be in the requirement's body text; `SHALL` in the `### Requirement:` heading
     alone does not count.
   - New requirements go inside `## Requirements`, before any later `##` section such as
     `## Implementation notes`.

3. **Hygiene sweep on added lines.** Take the patterns from the `CLAUDE.md` "Public-repo hygiene"
   section and run them over the lines this branch adds, not over whole files:
   - Line pass: `git diff -U0 <base>...HEAD | grep -P '^\+(?!\+\+)' | grep -nP '<line pattern>'`
   - Wrapped-phrase pass: `git diff -U0 <base>...HEAD | grep -P '^\+(?!\+\+)' | tr '\n' ' ' | grep -oP '<multiline pattern>'`

   Judge each hit. Text that quotes a forbidden word as a rule or an example (such as the hygiene
   section itself) is fine. Anything else gets rewritten.

4. **PR title.** The repo squashes with the PR title only, so the title is the only commit text in
   history. It is a conventional-commit line (`feat:`, `fix:`, `docs:`, `!` for breaking) that names
   the main thing the PR adds and follows the hygiene rules.

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
