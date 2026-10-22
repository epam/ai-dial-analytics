## Context

See `proposal.md` — Why. What shapes the approach here is the state of the existing material:

- **The dashboards are finished work.** Every panel query was audited against a running ClickHouse,
  each one matched against native ClickHouse SQL written from the panel's intent. Any
  edit that touches a query, a transformation or a `fieldConfig` invalidates that audit; an edit to a
  tag, a description or a default time range does not.
- **One artifact set, two audiences.** The same seven JSONs and the same datasource YAML serve the
  local preview stack and an operator's own Grafana. They already do — the datasource is
  env-parameterised and was driven against a running deployment during the audit.
- **The experiment log is load-bearing in two different ways.** It is simultaneously the only
  how-to and the record of the feasibility work (plugin comparison, the InfluxData timestamp defect,
  the Flight SQL YAML, the gotchas). Splitting it is the point of the change, but nothing in it may
  be lost: the gotchas are the expensive part.
- **Parts of it are stale.** "No arithmetic, no `CASE`, no `JOIN`/CTE/subquery, no window functions"
  describes a SQL surface ADAS has since outgrown — `docs/sql-reference.md` is the current one. The
  new doc must not re-copy a dialect summary that will rot; it links.
- **The local stack cannot exercise four of the seven dashboards.** `local_env/seed/` produces
  `dial_usage_log` only; `sessions`, `conversations` and the enrichment tables come from a
  deployment. The three that read `dial_usage_log` alone (`adas-dial-usage`,
  `adas-ai-supply-chain`, `adas-agentic-depth`) do run locally. Runtime verification is therefore
  partial by construction and must say so.

## Goals / Non-Goals

**Goals:**

- An operator can install these dashboards on their own Grafana from one document, without reading
  the investigation.
- The artifacts live somewhere that says "shipped", with history preserved through the move.
- The feasibility record survives intact in the investigation, minus the how-to.
- Every claim in the new document is true of today's ADAS, checked against `docs/sql-reference.md`
  and the dashboards themselves rather than copied from the log.

**Non-Goals:**

- No change to any panel, query, transformation or field config — the audit must stay valid.
- No new Grafana capability: no alerting rules, no Grafana-managed users, no TLS termination, no
  Kubernetes sidecar packaging (deliberately deferred; the two documented install routes cover it).
- No attempt to make the local Compose stack deployable. It stays a development preview.
- No ADAS service change, and therefore no spec delta — the row-cap and dialect gaps the dashboards
  work around stay recorded as investigation findings, not fixed here.

## Decisions

**A top-level `grafana/`, not `docs/grafana/` or `deploy/grafana/`.** These files are *executed* by
another program — mounted, provisioned, POSTed — not read. `docs/` is prose by convention in this
repo (`configuration.md`, `clickhouse-server.md`, …) and putting 300 KB of JSON under it would blur
that. `deploy/` was rejected because nothing else deployment-shaped exists to group with it yet;
inventing the root for one occupant is speculative. A sibling of `local_env/` and the investigations folder
matches how the repo already separates "runnable thing" from "record of work".

**`dashboards/` is a peer of `provisioning/`, not a child of it.** In the investigation the JSONs
lived at `provisioning/dashboards/`, which framed them as configuration of one particular Grafana.
They are the artifact; provisioning is one of two ways to install them. The file provider is
repointed at a mount of `../dashboards`, and the Compose stack mounts it separately
(`./dashboards:/etc/adas-dashboards:ro`) rather than under `/var/lib/grafana`, which the named
volume already owns.

**Dashboards keep their native provisioning format and the `adas-rest-sql` datasource uid.** Grafana's
"export for sharing" format substitutes `${DS_*}` placeholders in the *import* endpoint only — the
file provisioner stores a dashboard verbatim, so an exported copy provisions with dead datasource
references. Shipping both variants means maintaining both. One format plus a documented one-line
`sed` for a different uid is the smaller surface. This also keeps the seven UIDs stable, so an
operator who already imported them from the investigation path gets an update in place.

**Hardening touches metadata and prose only.** Tags, the default time range on
`adas-session-insights`, and note/description text. No `targets`, `transformations`, `fieldConfig`
or `gridPos` edits, which is what lets the panel-query audit carry over unchanged and makes the diff
reviewable. The rewritten notes keep every constraint they carried (no `$__interval`, cap-driven
bucket widths, UTC pinning, dotted-column left-join semantics) and drop only the framing that
explains them as consequences of a recreation exercise.

**`adas-session-insights` moves to `now-30d`, matching its six siblings.** Its pinned absolute
window was the measurement window of the audit; on any other deployment it
opens on four days of someone else's history, or on nothing. Several of its panel descriptions quote
counts from that window (how many of the labelled sessions fell into a class); those become
definitions rather than dated figures, which is the same correction the panel-query audit already
applied to the other five.

**The `client_type` variable stays a fixed list.** A provisioned Infinity *query* variable does not
resolve — its option list comes back empty, `${client_type:singlequote}` expands to nothing, and
every panel reads `client_type IN ()` → "No data". The variable is load-bearing for eleven panels,
so removing it is not available either. The operator doc carries the SQL to regenerate the list and
says when it needs regenerating. Rejected: converting the panels to unfiltered queries (loses the
dashboard's main control) and shipping a Grafana-managed dashboard instead of a provisioned one
(gives up read-only provisioning to fix a cosmetic gap).

**One datasource file serves both local and deployment use.** `auth_method: apiKey` with
`Api-Key`/`$ADAS_API_KEY` is provisioned unconditionally: a `security.mode=none` ADAS ignores the
header, an `oidc` deployment requires it, so switching target is two environment variables rather
than a file edit. `allowedHosts` must hold the base URL — Infinity refuses to send credentials to an
un-allowlisted host and the failure reads "URL is not in the allowed list", which points nowhere
near the cause. Rejected: separate local and deployment datasource files, which would diverge silently.

**No new credential, migration or configuration key.** The `Api-Key` value the datasource sends is
an existing DIAL API key belonging to a principal with `FULL_ADMIN` or `READ_ONLY_ADMIN`; nothing in
ADAS is configured by this change, so `docs/configuration.md` gains no row (project rule: a change
adding no property adds nothing to that file).

**The investigation folder collapses to two files.** With the artifacts gone,
the investigation's Grafana folder would hold one README, so the log moves up one level
(via `git mv`, keeping its history) next
to the existing Grafana verdict page. The how-to sections — quick start, import steps, files
table, "running against a deployment" — leave it for `docs/grafana-dashboards.md`; everything
evidential stays, including the Flight SQL YAML that is no longer provisioned anywhere.

**`verify_dashboards.py` ships with the dashboards, as the executable half of the doc.** It reads
the SQL back out of the JSONs, so the dashboards remain the single source of truth and the
documented verification step cannot drift from what is installed. Its `DASHBOARDS` path constant
changes with the layout; nothing else does.

## Risks / Trade-offs

- **A dangling reference to the old path** in a file the sweep misses → grep the whole repo for
  the old investigation path after the move, and again in review; the known three
  (`README.md`, `local_env/docker-compose.yml`, `docs/release-0.2-upgrade.md`) are named as tasks.
- **Concurrent branches** carry the same files at the old path, so a `git mv` guarantees a conflict
  for anyone rebasing a dashboard edit → the move is mechanical and conflicts resolve by taking the
  new location; no in-flight change currently edits these files.
- **Rewritten notes could lose a caveat a reader depended on** → the constraint inventory (cap,
  buckets, UTC, macros, left-join semantics, two spines) is carried forward panel by panel, and the
  review round reads the note diff against the original.
- **Runtime verification is partial**: only the two `dial_usage_log` dashboards can return data
  against `local_env` + seed; the other five need a deployment with `sessions`, `conversations` and
  enrichment. → verify them for SQL acceptance where the tables exist, and state plainly in the review
  notes which dashboards were not exercised with data rather than implying a full pass.
- **The doc asserts an operating envelope that ADAS can change under it** (the 1000-row maximum, the
  closed function catalog) → the envelope is stated by pointing at `docs/sql-reference.md` and the
  `rest-sql-endpoint` spec for the numbers, not by restating them where they would rot.
- **Infinity is a third-party plugin** and the Compose stack pins 3.11.1 while a current Grafana
  ships 4.x → the dashboards were confirmed importing and rendering on a vanilla Grafana 13.2.2 with
  Infinity 4.0.0 during the investigation; the doc records both the pinned and the verified-newer
  versions instead of claiming version independence.

## Migration Plan

1. `git mv` the artifacts into `grafana/`, adjusting the two path constants that follow them (the
   dashboard provider's `options.path`, `verify_dashboards.py`'s `DASHBOARDS`).
2. Harden the seven JSONs (metadata and prose only), re-running `verify_dashboards.py` afterwards to
   confirm no query text was disturbed.
3. Write `docs/grafana-dashboards.md`; strip the how-to from the experiment log and cross-link.
4. Sweep the repo for references to the old path.
5. `./gradlew clean build` (unaffected, but it is the gate), then bring up `local_env` + `bootRun` +
   the Grafana stack and verify at the two levels available locally: `verify_dashboards.py` against
   the seeded ADAS, and the transport dashboard rendered through Grafana's own `POST /api/ds/query`.

Rollback is `git revert` of a single commit: no state, no schema, no configuration is involved. An
operator who already provisioned from the old path keeps working until they update their checkout.
