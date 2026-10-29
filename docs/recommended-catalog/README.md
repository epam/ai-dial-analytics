# Recommended catalog

[`adas-catalog.json`](adas-catalog.json), next to this file, is a catalog bundle with the **recommended
configuration** for a new ADAS installation: the user tables and pipelines that the analytics UI and the evaluation
framework expect. Import it once instead of creating each object by hand. Behaviour of the import itself is
specified in [`openspec/specs/catalog-export-import/spec.md`](../../openspec/specs/catalog-export-import/spec.md).

The bundle holds **definitions only**: table schemas, column metadata and pipeline definitions. It holds no rows, no
grants and no Enrichment Runner state. The pipelines fill the tables from `dial_usage_log` once they are armed.

## What it contains

| Object                       | Kind                          | What it does                                                                                              |
|------------------------------|-------------------------------|-----------------------------------------------------------------------------------------------------------|
| `usage_client_identity`      | enrichment table              | Per event: client (Chat, Claude Code, Claude Desktop, Codex, OpenCode), normalised session id, auth kind. |
| `usage-client-identity-live` | `enrich` pipeline (SQL)       | Fills `usage_client_identity` from request headers and JWT claims.                                        |
| `sessions`                   | source table, `upsert_by_key` | One row per client session: turns, tokens, cost, durations, principal.                                    |
| `sessions_rollup`            | `aggregate` pipeline          | Rolls `dial_usage_log` up into `sessions` every 15 minutes.                                               |
| `usage_request_baggage`      | enrichment table, 1-year TTL  | Per event that sent one: the W3C `baggage` request header.                                                |
| `usage-request-baggage-live` | `enrich` pipeline (SQL)       | Fills `usage_request_baggage` from the captured request tags.                                             |

The bundle requires the `dial_usage_log` and `dial_usage_log_payload` system tables (listed under
`required_system_tables`). `usage_request_baggage` keeps the header for a year, the usage log's default retention, where
`dial_usage_log_payload.request_tags` expires sooner. No pipeline calls a model, so the bundle needs no DIAL deployment on
the target.

## Why it is useful

- **Sessions tab.** The analytics UI always ships a Sessions tab, which reads `sessions` and `usage_client_identity`.
  Without these two tables and their pipelines the tab has nothing to show.
- **Evaluation framework.** Parts of the evaluation framework read `usage_request_baggage`: evaluation traffic tags
  its requests with `baggage` keys such as `eval.run.id` and `testcase.id`, and this table is where they are kept and
  filtered.

The table and column descriptions in the bundle document each column in detail; after the import they are served by
`GET /v1/tables/{name}`.

## Importing it

Import `adas-catalog.json` using the UI. Review the import preview before confirming: it must report no validation
errors, and objects that already exist are kept unchanged. The general sequence is in
[`catalog-promotion.md`](../catalog-promotion.md).

Points specific to this bundle:

- **`dial_usage_log_payload` must be ACTIVE.** Both enrichments read `request_tags` from it.
- **`env_specific` lists the cron triggers.** Adjust them after the import if the defaults do not suit the
  installation.
- **Grant access.** The imported tables have no grants. `usage_client_identity` and `sessions` carry personal-data
  columns marked `sensitive` (user email and display name); grant them only to roles that may see personal data.
- **Arm in order.** Enable `usage-client-identity-live` and `usage-request-baggage-live` first, then
  `sessions_rollup`, whose filter and grouping read `usage_client_identity`.
- **Expect a first rebuild.** `sessions_rollup` starts with no cursor and rescans `dial_usage_log` on its first runs
  (see step 5 of [`catalog-promotion.md`](../catalog-promotion.md)). The Sessions tab fills in as the pipelines catch up.
