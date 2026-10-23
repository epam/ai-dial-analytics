# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**AI DIAL Analytics** hosts the **Analytics Data Access Service (ADAS)** — the runtime gateway
between analytics consumers and the underlying data stores (primarily ClickHouse). Functional
surface is added incrementally as spec-driven changes. The querying surface is the structured query DSL at
`POST /v1/queries/execute` with schema discovery under `/v1/queries` (capabilities
`structured-query` and `query-schema-discovery`), plus ad-hoc SQL over REST at
`POST /v1/queries/execute-sql` (capability `rest-sql-endpoint`, same governed pipeline), their CSV-download twins `execute-csv` /
`execute-sql-csv` (capability `query-csv-export`, same pipeline, RFC 4180 body), and the
translation-only pair `POST /v1/queries/translate` / `translate-sql` (capability
`query-translation-endpoint`, DSL ↔ SQL without execution); the `data-sync` path ingests DIAL
usage logs from blob storage into ClickHouse. `POST /v1/catalog/export` / `import` (each with a `/preview`
twin; capability `catalog-export-import`) promote user tables and pipelines (never grants) between environments.

## Source of Truth

- **Project conventions**: the `context:` section of `openspec/config.yaml` — tech stack/versions,
  package layout, API + error conventions, configuration, testing, operational invariants. OpenSpec
  ships it with every request, so it needs no separate read; keep it current. (It replaced the old
  `openspec/project.md`.)
- **Specs**: `openspec/specs/<capability>/` — the source of truth for shipped behavior.
- **In-flight changes**: `openspec/changes/<change>/` — proposals, design, tasks, and delta
  specs for work in progress; completed changes move to `openspec/changes/archive/`.

## Essential Commands

```bash
./gradlew clean build                       # compile + checkstyle + tests
./gradlew bootRun                           # run locally on http://localhost:5000
./gradlew checkstyleMain checkstyleTest     # code style (Google Java Style, 180-char lines)
./gradlew test                              # run the test suite
```

Smoke test a running instance:

```bash
curl -s localhost:9464/actuator/health      # {"status":"UP"} (management port)
curl -s localhost:5000/v1/does-not-exist    # stable ErrorView JSON (404 not_found)
```

## Spec-Driven Development (OpenSpec)

This project uses **OpenSpec** (not spec-kit) for feature development. The workflow runs through
`/opsx:*` slash commands (skills under `.claude/skills/`, commands under `.claude/commands/opsx/`):

- `/opsx:propose "<idea>"` — create a change with proposal, design, and tasks
- `/opsx:apply` — implement the current change's tasks
- `/opsx:archive` — finalize: merge delta specs into `openspec/specs/` and archive the change
- `/opsx:explore`, `/opsx:sync` — explore ideas / sync specs

CLI: `openspec list`, `openspec show <change>`, `openspec validate <change>`.

### Spec maintenance is part of every code change

When you change behavior, contracts, or invariants, update the relevant
`openspec/specs/<capability>/` spec in the same change — it isn't optional polish. Before
reporting a change complete, confirm the spec matches the shipped behavior (or state explicitly
that no spec update was needed).

## Conventions quick reference

See the `context:` section of `openspec/config.yaml` for the full set. Highlights:

- Base package `com.epam.aidial.analytics`; layered `web/`, `service/`, `configuration/`.
- Functional endpoints under `/v1`; operational endpoints under `/actuator/*`.
- All errors use the `ErrorView` contract (`{status, error, message, path, method}`); `error` is a
  stable machine code. Add `@ExceptionHandler`s to `DefaultExceptionHandler` for new failure modes.
- Config is env-driven (`${ENV_VAR:default}`). **Property descriptions live ONLY in
  `docs/configuration.md`** (one table per config group). Do **not** add explanatory comments to
  `application.yml` (keep it bare `key: ${ENV:default}` lines) and do **not** add env-var description
  tables/snippets to `README.md` — README may name a var inline in prose and link to
  `configuration.md`, but the authoritative descriptions stay in one place.
- `docs/configuration.md` is a **property reference**: one row per variable, **one line** per
  description, a `Required` column that is `Yes`/`No` or a short condition. Behaviour, rationale and
  cost go to `openspec/specs/<capability>/spec.md`; a change adding no property adds nothing to
  `configuration.md`. Operator knowledge that is neither gets its own `docs/` topic doc
  (`clickhouse-server.md`, `column-mapping.md`, `kubernetes-exposure.md`).
- A change is done only when `./gradlew clean build` passes.

## Public-repo hygiene

This repository is open source. Everything tracked — code, comments, Javadoc, tests, `application.yml`,
docs, Grafana dashboards, `openspec/specs/` and `openspec/changes/` (archive included) — must read as the
project's own neutral engineering record, not as one company's operations log.

- **No environment attribution.** Don't write "on dev", "UAT", "prod", "in production", "the reference
  deployment", or cluster, namespace, host, bucket or tenant names. Say "a running deployment" or
  "existing deployments", or describe the condition itself. "Local development" and "production code"
  (main source as opposed to tests) are fine.
- **No operational history.** No incident or outage dates and times, deploy or drain narratives, MR or
  ticket numbers, branch or merge chatter (`origin/development`, "renumbered at merge"), people, teams,
  or "the user decided". A design decision is attributed to "the maintainers", or simply stated with
  its reasons.
- **No real-data figures.** Never copy row counts, percentages, sizes, rates or timings measured on a
  real environment. Use order-of-magnitude wording ("millions of rows", "on the order of GiB"). Spec
  scenarios and test fixtures use invented numbers and patterned ids (`00000000-0000-4000-8000-000000000001`),
  never ids or values copied from logs.
- **Entity names: real is fine, framing is not.** Pipeline and table names such as `sessions_rollup`,
  `usage_client_identity` or `session-insights-live` may appear as examples and fixture names. Describe
  them by what they do, never as live, deployed or ours, and never by their history ("the live
  `session_insights` defect", "four working production rollups"). Don't make near-copy renames
  (`usage_client_identity` → `client_identity`): they fix nothing. Pick one rule and apply it to every
  name.
- **Only reference what ships.** Don't point at untracked files (`docs/*.local.md`), deleted
  directories, or internal design ids that resolve nowhere (`D5`, `Phase-1`, `ADR 0006`, "task 7.4").
  Link the `openspec/specs/<capability>/spec.md` that owns the behaviour instead.
- **No machine-specific content.** Keep local socket overrides (`DOCKER_HOST=…/.rd/docker.sock`), home
  paths and personal tooling out of tracked files. A task line says `./gradlew clean build`.
- **No temporary scaffolding.** No "TODO: remove before prod" code or demo pages. Remove it, or make it
  a documented, opt-in feature.
- **Applied Flyway migrations are frozen.** Their comments are checksummed, so never edit one to
  reword it. Write new migrations to these rules from the start, and keep their comments minimal: at
  most a one-line header that names what the migration does and links the owning
  `openspec/specs/<capability>/spec.md`. Rationale, cost, rejected alternatives and operator ordering
  belong in that spec or the change's `design.md`, which can still be corrected later. A stale
  multi-paragraph header can't be.
- **Verify with PCRE.** `git grep -E` on macOS ignores `\b`, so a sweep built on it silently misses
  hits. Use `git grep -nP`, for example:
  `git grep -nIP '\b(dev|UAT|prod)\b|reference[- ]deployment|in production|DevOps|MR ?!\d+|\.local\.md'`.
  A line-based grep misses phrases wrapped across lines, so add a multiline pass, for example:
  `git ls-files -z | xargs -0 perl -0ne 'print "$ARGV\n" if /reference[-\s]+deployment|in\s+production/'`.
