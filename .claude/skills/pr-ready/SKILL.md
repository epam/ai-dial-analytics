---
name: pr-ready
description: Check a branch before opening a pull request or pushing to an open one. Use before `gh pr create`, before pushing new commits to a PR, or when the user asks whether a PR is ready.
allowed-tools: Bash(git fetch:*), Bash(git diff:*), Bash(git log:*), Bash(git grep:*), Bash(git remote -v), Bash(gh pr view:*), Bash(openspec validate:*), Bash(perl .claude/skills/pr-ready/hygiene-sweep.pl:*), Bash(./gradlew:*)
---

This skill only checks. Report every step as **pass**, **fail** or **n/a**, and propose a fix for
each fail. Ask before anything that commits, pushes, edits the PR or opens an issue.

**Tier.** Before a push to an open PR, run steps 0–3, 5 and 6, and step 7 as `./gradlew build` only
when build inputs changed (`src/`, `build.gradle`, `settings.gradle`, `gradle/`, `config/`). Before
`gh pr create`, or before marking a PR ready, run every step, with step 7 as `./gradlew clean build`.

0. **Base.** Find the remote whose URL is the GitHub repository (`git remote -v`, matching
   `epam/ai-dial-analytics`), and the base branch: `gh pr view --json baseRefName -q .baseRefName`
   for an open PR, otherwise `development`. Run `git fetch <remote> <branch>`. `<base>` below is
   `<remote>/<branch>`.

1. **OpenSpec validates.** n/a when the branch touches nothing under `openspec/changes/` outside
   `archive/`. Otherwise run `openspec validate <change> --strict`. Always pass `--strict`: depending
   on the OpenSpec version, a missing `SHALL`/`MUST` is an error or only a warning. The keyword must be
   in the requirement's body text; `SHALL` in the `### Requirement:` heading alone does not count.

2. **OpenSpec change archived, specs valid.** Archive any change from step 1 in this PR
   (`/opsx:archive`). Every task in `tasks.md` is ticked first, and a verification task is ticked only
   once it has actually been run. Then, whenever the branch touches anything under `openspec/`
   (archived changes and direct spec edits included; n/a otherwise), run
   `openspec validate --specs --strict`. A `### Requirement:` after a later `##` section such as
   `## Implementation notes` is an error even without `--strict`; move it back inside
   `## Requirements`.

3. **Hygiene sweep of added lines.** `perl .claude/skills/pr-ready/hygiene-sweep.pl <base>`. It
   prints `file:line: [rule] text` for each hit, including phrases wrapped across lines and comment
   prefixes. Exit 0 is clean, 1 means hits to judge, 2 is an error such as an unknown base or empty
   stdin (never read it as clean). Judge
   each hit: text that quotes a forbidden word as a rule or an example is fine, anything else gets
   rewritten. The script catches patterns, not people's names or real-data figures, so read the
   added prose for those too.

4. **PR title.** n/a before the PR exists unless a title is drafted. CI checks the format; check the
   content. The repo squashes with the PR title only, so the title is the only commit text in
   history: it names the main thing the PR adds (type e.g. `feat:`, `fix:`, `docs:`, `chore:`,
   optional scope, `!` for breaking).

5. **PR description.** n/a before the PR exists unless a description is drafted. It uses
   `.github/pull_request_template.md`: `Applicable issues` (a GitHub issue, or `n/a` for a small
   change), `Description of changes`, and `Checklist` with the Apache 2.0 line. On an open PR it
   matches the branch after this push: no stale "Draft", "not built yet" or "stacked on #N". Sweep the
   title and description with
   `gh pr view --json title,body -q '.title + "\n" + .body' | perl .claude/skills/pr-ready/hygiene-sweep.pl -`
   (or pipe the draft text). GitHub `#N` links are fine.

6. **Deferred work.** Every follow-up the PR mentions has its own GitHub issue, linked from the PR,
   not just a sentence in the description.

7. **Build.** `./gradlew clean build` (or `./gradlew build`, per the tier) passes on the branch head.
