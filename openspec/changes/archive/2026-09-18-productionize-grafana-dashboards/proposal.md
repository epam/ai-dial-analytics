## Why

Seven Grafana dashboards now run entirely on `POST /v1/queries/execute-sql` — the transport
dashboard plus six port-for-port recreations of the direct-ClickHouse analytics dashboards — and every
panel query was audited against a running ClickHouse. They are the only worked example
of what an analytics consumer can build on ADAS without a second query gateway, and they are good
enough to hand to an operator.

Nothing about how they are shipped says so. They sit in the BI-connectivity investigation folder (since removed from the repository),
which the investigation's own README defines as "investigation reports, not product documentation";
they carry `demo`, `facade` and `local` tags; one pins an absolute four-day window from the
measurement run; several panel notes explain the *recreation exercise* rather than the data; and the
only instructions are a 650-line experiment log that interleaves a how-to with feasibility verdicts,
plugin comparisons and a Flight SQL investigation that no longer ships. An operator who wants these
dashboards on their own Grafana has to read the whole log and reverse-engineer the steps, and some of
what they would read is stale — the SQL surface has grown arithmetic, `CASE`, joins, CTEs and window
functions since those paragraphs were written.

## What Changes

- **New top-level `grafana/`** holding the shipped artifacts, moved with `git mv` so history follows:
  - `dashboards/*.json` — the seven dashboards, flattened out of `provisioning/dashboards/`
  - `provisioning/datasources/adas.yml`, `provisioning/dashboards/adas.yml` — reference provisioning,
    pointed at `../dashboards`
  - `verify_dashboards.py` — re-runs every panel query against a live ADAS, no Grafana needed
  - `docker-compose.yml` — the local preview stack, kept and explicitly labelled development-only
  - `README.md` — a short index: what each file is, one pointer to the operator doc
- **New `docs/grafana-dashboards.md`** — the operator instruction this change exists to write:
  prerequisites per dashboard (which tables and enrichment pipelines each one needs), Infinity plugin
  install, datasource creation with uid `adas-rest-sql` including the `Api-Key` credential and the
  non-obvious `allowedHosts` requirement, the two install routes (file provisioning and
  `POST /api/dashboards/db`), verification with `verify_dashboards.py`, and the operating envelope —
  the 1000-row cap's effect on usable time ranges, the UTC pinning, the fixed `client_type` list, and
  what a reader must adjust when adopting.
- **Dashboard hardening** (content, not layout — panels, queries and field configs stay as audited):
  - drop the `demo`, `facade` and `local` tags; keep the descriptive ones
  - `adas-session-insights` moves off its pinned absolute window to a relative one
  - panel notes that narrate the recreation exercise become operator-facing caveats stating the same
    constraints (no `$__interval`, cap-driven bucket widths, UTC, left-join semantics of dotted
    columns)
  - every investigation-folder path reference in a dashboard description now points
    at `docs/grafana-dashboards.md`
- **The investigation keeps the feasibility record and only that.** Its Grafana verdict page
  stays; the experiment log's findings — the plugin comparison, the Flight SQL YAML
  and timestamp defect, the gotchas hit while setting the harness up, how each verdict was measured,
  the audit history and the ClickHouse→ADAS translation notes — are preserved in the investigation
  beside it, with the how-to sections removed and a pointer to the new home.
- **Cross-references updated** in the repo `README.md`, `local_env/docker-compose.yml` and
  `docs/release-0.2-upgrade.md`, which all currently point at the investigation path.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

None. This change ships no service behavior: no endpoint, contract, catalog or configuration
property changes, and no Java is touched. The dashboards are consumers of the already-specified
`rest-sql-endpoint` and `sql-query-translation` surfaces and add nothing to them. Per the project's
archive guidance, the change declares `skip_specs: true` in its `.openspec.yaml` rather than
inventing an empty delta.

## Impact

- **Files moved**: the investigation's `provisioning/`, `verify_dashboards.py` and `docker-compose.yml`
  → `grafana/`. Anyone with the old investigation-path `docker compose -f` command in shell history
  gets a missing-file error, not a silent failure.
- **Documentation**: one new topic doc under `docs/`. No configuration property is added, so
  `docs/configuration.md` gains nothing (project rule).
- **Code, API, schema, dependencies**: unchanged. `./gradlew clean build` is unaffected and remains
  the completion gate.
- **Dashboard consumers**: the seven UIDs are unchanged, so an already-imported dashboard updates in
  place rather than duplicating.
