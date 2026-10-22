## 1. Move the artifacts into `grafana/`

- [x] 1.1 `git mv` the seven dashboard JSONs from
  the investigation's `provisioning/dashboards/` to `grafana/dashboards/`
- [x] 1.2 `git mv` `provisioning/datasources/adas.yml` and `provisioning/dashboards/adas.yml` to
  `grafana/provisioning/{datasources,dashboards}/adas.yml`, and repoint the file provider's
  `options.path` at the dashboards mount
- [x] 1.3 `git mv` `verify_dashboards.py` to `grafana/`, updating its `DASHBOARDS` path constant and
  the usage examples in its module docstring
- [x] 1.4 `git mv` `docker-compose.yml` to `grafana/`, add the `./dashboards:/etc/adas-dashboards:ro`
  mount, and rewrite its header comment as a development-preview stack rather than an investigation
  harness (keeping every operational comment: plugin pinning, `GF_PLUGINS_PREINSTALL_DISABLED`,
  loopback bind, no `extra_hosts`)
- [x] 1.5 Write `grafana/README.md`: one paragraph on what this directory is, the file table, and a
  pointer to `docs/grafana-dashboards.md` for anything procedural
- [x] 1.6 Verify the investigation's Grafana folder holds nothing but the experiment log after
  the moves

## 2. Harden the dashboards (metadata and prose only)

- [x] 2.1 Drop the `demo`, `facade` and `local` tags from all seven dashboards, keeping the
  descriptive ones
- [x] 2.2 Replace `adas-session-insights`'s pinned absolute window with `now-30d`
- [x] 2.3 Rewrite the "Read this before trusting a number" note panels so each constraint survives
  (no `$__interval`, cap-driven bucket widths, UTC pinning, dotted-column left-join semantics, the
  two-spine split) while the recreation-exercise framing goes
- [x] 2.4 Restate the dated figures in `adas-session-insights` panel descriptions as definitions, the
  correction the panel-query audit already applied to the other five dashboards
- [x] 2.5 Repoint every investigation-folder reference inside a dashboard description
  or note at `docs/grafana-dashboards.md`
- [x] 2.6 Confirm mechanically that no `targets`, `transformations`, `fieldConfig`, `gridPos` or
  `datasource` value changed in any of the seven files

## 3. Write the operator documentation

- [x] 3.1 Create `docs/grafana-dashboards.md` covering: what the seven dashboards are and what each
  needs (tables, enrichment pipelines); prerequisites (ADAS reachable, `rest-sql-endpoint`, an API
  key whose principal holds `FULL_ADMIN` or `READ_ONLY_ADMIN`)
- [x] 3.2 Document the datasource: Infinity plugin install, uid `adas-rest-sql`, `Api-Key` header
  auth, the mandatory `allowedHosts`, and how to point it at a deployment
- [x] 3.3 Document both install routes — file provisioning (copy `grafana/provisioning` +
  `grafana/dashboards`) and `POST /api/dashboards/db` — including the uid rewrite when reusing an
  existing datasource
- [x] 3.4 Document verification: `verify_dashboards.py` (env vars, exit status, the AT CAP report)
  and what a green run does and does not prove
- [x] 3.5 Document the operating envelope: the 1000-row maximum and its effect on usable ranges and
  bucket widths, UTC pinning, the fixed `client_type` list with the SQL to regenerate it, the
  read-only consequence of provisioning, and a link to `docs/sql-reference.md` for the dialect
  rather than a copy of it
- [x] 3.6 Document the local preview stack as development-only, stating why its posture (anonymous
  Editor, loopback bind) must not be carried into a deployment

## 4. Leave the feasibility record in the investigation

- [x] 4.1 `git mv` the investigation's Grafana `README.md` up one level as its
  Grafana experiment log
- [x] 4.2 Strip its how-to sections (quick start, import steps, files table, running against a
  deployment) and keep every finding: plugin comparison, the InfluxData timestamp defect, the Flight
  SQL YAML, the gotchas, how each verdict was measured, the audit history, the dialect translation
  notes
- [x] 4.3 Add the pointer to `docs/grafana-dashboards.md` and `grafana/` at the top, and update
  the investigation's Grafana verdict page and its README to
  match the new split
- [x] 4.4 Remove stale claims the log carries about the SQL surface (no arithmetic, no `CASE`, no
  joins/CTEs/window functions) or mark them as superseded by `docs/sql-reference.md`

## 5. Sweep cross-references

- [x] 5.1 Update the repo `README.md` BI section to name `grafana/` and the new doc
- [x] 5.2 Update the `local_env/docker-compose.yml` header comment that points at the investigation
  path
- [x] 5.3 Update `docs/release-0.2-upgrade.md`'s Grafana bullet
- [x] 5.4 Grep the repo for the old investigation path and confirm every remaining hit
  is intentional

## 6. Verify

- [x] 6.1 `python grafana/verify_dashboards.py` against a seeded local ADAS — every query of the
  three `dial_usage_log`-only dashboards (`adas-dial-usage`, `adas-ai-supply-chain`,
  `adas-agentic-depth`) returns 200, and the queries of the other four fail only on missing
  entities
- [x] 6.2 Bring up the Grafana stack from its new location and confirm the provisioned datasource and
  all seven dashboards load, with `adas-dial-usage` rendering data through `POST /api/ds/query`
- [x] 6.3 `./gradlew clean build` passes
