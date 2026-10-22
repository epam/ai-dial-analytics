# Mark `dial_usage_log.request_tags` as sensitive

## Why

`request_tags` is populated **verbatim from the record's top-level `headers` object**
(`src/main/resources/mapping/dial-usage-log.yaml`, change `populate-jwt-claims-request-tags`). That
change shipped deliberately with *no allowlist or denylist* — filtering transport/SDK noise was
declared a DIAL Core producer-side concern — and its own proposal flagged the consequence: "note the
PII surface — `claims` and full `headers` may carry sensitive values".

When column-level access control landed (`add-sensitive-columns`), `request_tags` was nonetheless
left visible. `V3__add_sensitive_columns.sql` and Decision 5 of that change's `design.md` record the
rationale — "`request_tags` is routing metadata, not content" — and both note the column is "one
`PATCH set_sensitive` away if the threat model later requires" it.

That rationale under-weighted what verbatim headers actually contain: `Authorization`-adjacent
values, `Api-Key`, cookies, `X-Forwarded-For` and other client IPs, and arbitrary per-user
correlation headers. "Routing metadata" describes the column's *intent*, not the bytes it stores.
This change reverses Decision 5 for `request_tags` and restricts it to `FULL_ADMIN`, alongside
`jwt_claims`, `request_body`, and `response_body`.

## What Changes

- Seed `dial_usage_log.request_tags` as `sensitive = TRUE` in the `column_mapping` catalog via a new
  Flyway migration. Catalog-only metadata: no ClickHouse `ALTER`, no data migration, no row rewrite.
- No code changes. Enforcement already exists and is generic: `CatalogQueryService.buildModel`
  filters sensitive columns out of the resolved query model for non-`FULL_ADMIN` callers, covering
  schema discovery, `POST /v1/queries/execute`, `POST /v1/queries/execute-sql`, Flight SQL, and the
  empty-projection "select all" expansion at one chokepoint.
- **Behavior change in `oidc` mode** for non-`FULL_ADMIN` callers: `request_tags` disappears from
  schema discovery and from `SELECT *` expansion, and naming it in any clause fails as an ordinary
  unknown-field `400` — the column is *hidden*, not forbidden, so no error path reveals it exists.
  In `security.mode=none` the filter fails open and nothing changes.
- `user_hash` stays visible. Its Decision 5 rationale is untouched: it is a de-identified per-user
  surrogate that exists precisely to enable safe per-user analytics for read-only roles.
- `V3__add_sensitive_columns.sql` is **not** edited — it is applied in every environment and editing
  it would break Flyway checksum validation. Its now-superseded comment stays; the new migration
  carries the correction.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `table-management`: adds a "Seeded column sensitivity on the built-in source tables" requirement
  pinning the seeded sensitive set (`dial_usage_log.jwt_claims`, `request_tags`, `request_body`,
  `response_body`; `rate_analytics.comment`), recording why `request_tags` is in it and `user_hash`
  is not, and stating that the seed is operator-overridable via `PATCH`. This capability already
  owns claims about the seeded catalog (see its "dial_usage_log is present after migration"
  scenario), and the seeded set was previously spec'd nowhere — only in migration comments.

The enforcement requirements are untouched: `authentication` ("Sensitive column authorization"),
`query-schema-discovery`, and `structured-query` describe the mechanism generically and enumerate no
columns, so they stay accurate as written.

## Impact

- **Postgres schema**: new Flyway migration `V12__mark_request_tags_sensitive.sql` — a single
  `UPDATE column_mapping SET sensitive = TRUE`. V12 is the next number after the highest merged
  migration (`V11__enrichment_registry.sql` on the default branch); unmerged branches that also draft a
  V12 are not consulted, and whichever MR merges second renumbers.
- **Code**: none.
- **APIs**: no contract change. `/v1/queries/**` responses lose the field for non-admins in `oidc`
  mode; `/v1/tables/**` management responses are deliberately unfiltered and still return the column
  with `sensitive: true` so operators can audit it.
- **Enrichments** — the sharpest edge: `RuleGovernanceValidator` refuses, with `403
  sensitive_column_not_entitled`, any rule whose `filter` or **bound** input binding references a
  sensitive column, and that check is **independent of `security.mode`** (it fires even in `none`).
  No rule seeded by `V11__enrichment_registry.sql` references `request_tags`, so nothing breaks
  today, but after this change no new rule may bind or filter on it. Note that
  parallel enrichment work relaxes exactly this check for granted rules; the two must be
  reconciled.
- **Tests**: assert the seed through `GET /v1/queries/entities/schema/dial_usage_log` in
  `QuerySchemaDiscoveryIntegrationTest`.
- **Specs**: one delta, `specs/table-management/spec.md` (new requirement, no existing requirement
  changes).
- **Docs**: none required — no doc enumerates the sensitive set.
- **Rollback**: a follow-up `UPDATE … SET sensitive = FALSE`, or immediately per environment via
  `PATCH /v1/tables/dial_usage_log/schema` `{"update":[{"name":"request_tags","sensitive":false}]}`.
