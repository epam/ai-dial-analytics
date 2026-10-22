# Design: mark-request-tags-sensitive

## Context

`sensitive` is a catalog-only boolean on `column_mapping` (`ColumnMappingEntity.isSensitive`), never
part of the ClickHouse DDL. `SensitiveColumnAccess.maySeeSensitive()` answers "may this caller see
sensitive columns?" — `true` for `FULL_ADMIN`, and unconditionally `true` when `security.mode=none`
(fail-open). `CatalogQueryService.buildModel` consults it once per model build and drops sensitive
columns from both `bindings` and `baseSchema`, which is why discovery, structured execute,
`execute-sql`, Flight SQL, and empty-projection expansion are all covered by one filter.

That machinery shipped with `add-sensitive-columns`, which seeded four columns as sensitive
(`dial_usage_log.jwt_claims`, `request_body`, `response_body`; `rate_analytics.comment`) and
explicitly excluded `user_hash` and `request_tags`. This change revisits only the `request_tags`
half of that exclusion. Nothing about the mechanism changes.

## Goals / Non-Goals

**Goals:**
- Make `dial_usage_log.request_tags` `FULL_ADMIN`-only in every deployment, existing and fresh.
- Record why the original "routing metadata" rationale no longer holds, in a place that survives
  (the new migration), without editing the applied `V3` migration.

**Non-Goals:**
- Changing `user_hash`. Its rationale is intact and hiding it would remove per-user analytics from
  read-only roles for little marginal protection while `jwt_claims` is already sealed.
- Header filtering or redaction at ingest. `request_tags` stays a verbatim passthrough; filtering
  remains a DIAL Core producer-side concern per `populate-jwt-claims-request-tags`. This change
  controls *who reads* the column, not *what is written* into it.
- Partial/masked visibility (e.g. exposing selected header keys). The flag is all-or-nothing by
  design; a per-key projection is a much larger feature and is not needed to close this exposure.
- Any change to the enforcement chokepoint, the DTO surface, or the `PATCH` API.

## Decisions

1. **Reverse Decision 5 of `add-sensitive-columns` for `request_tags`** — the original decision
   classified the column by its intended semantics ("routing metadata, not content"), but the
   mapping stores the request `headers` object verbatim with no allow/deny list, so the column can
   hold credentials, cookies, forwarded client IPs, and per-user correlation ids. Judged by contents
   rather than intent it belongs with `jwt_claims`. Decision 5 anticipated exactly this
   ("one `PATCH set_sensitive` away if the threat model later requires"), so this is the
   foreseen follow-up, not a contradiction.

2. **Ship it as a Flyway migration, not a runtime `PATCH`** — a `PATCH
   /v1/tables/dial_usage_log/schema` would take effect immediately but leaves no trace in the repo
   and would not apply to a freshly provisioned environment, so the two would silently diverge. A
   migration makes the restriction the shipped default and puts the rationale under review.
   *Alternative — do both:* unnecessary; the migration reaches existing environments too, since it
   is an `UPDATE` against the already-seeded catalog row.

3. **New migration, do not edit `V3`** — `V3__add_sensitive_columns.sql` is applied everywhere;
   changing its text breaks Flyway checksum validation on every existing database. The superseding
   rationale therefore lives in the new migration's header comment, which explicitly names V3 so the
   two read together.

4. **Version `V12`** — the next number after what is actually merged: the default branch tops out
   at `V11__enrichment_registry.sql`. Unmerged branches are deliberately not consulted; numbering
   against speculative MRs leaves permanent gaps in the applied history for migrations that may
   never land. *Alternative — skip ahead to `V15` to dodge the branches that currently draft a
   `V12`–`V14`:* rejected. Whichever MR merges second renumbers; that is a rebase, not a
   correctness problem, and it keeps the merged sequence dense.

5. **Spec the seeded set in `table-management`, leave the enforcement requirements alone** — the
   enforcement rules (`authentication`, `query-schema-discovery`, `structured-query`) are generic
   and enumerate no columns, so they stay accurate untouched. But the seeded set itself was spec'd
   *nowhere* — it lived only in a migration comment, which is how the `request_tags` rationale went
   unreviewed until now. `table-management` already makes claims about the seeded catalog (its
   "dial_usage_log is present after migration" scenario), so a new requirement there gives the seed
   a reviewable home, records the contents-vs-intent argument, and states that the seed is
   operator-overridable rather than an invariant. The one spec text naming `request_tags` in a query
   scenario (`query-translation-endpoint`, bare-select over object columns) also names the
   already-sensitive `jwt_claims` in the same breath, so it is implicitly an admin/`none`-mode
   scenario and does not drift.

## Risks / Trade-offs

- [Enrichment rules that bind or filter on `request_tags` are refused with `403
  sensitive_column_not_entitled`, and `RuleGovernanceValidator` applies that check regardless of
  `security.mode`] → No seeded rule references the column today, so nothing breaks on merge. Flagged
  in the proposal; parallel enrichment work relaxes the check for granted rules and the two must be
  reconciled.
- [A read-only consumer's `SELECT *` silently returns one fewer column in `oidc` mode] → Intended
  and already spec'd (hidden, not forbidden — no existence disclosure). Naming the column yields a
  `400` unknown field, which reads as a bug to an uninformed caller; this is the documented
  trade-off of non-disclosure, unchanged by this change.
- [A local development run keeps showing the column, so the restriction looks like it did not take] → Fail-open in
  `none` mode is the existing, spec'd behavior. The added test asserts the *flag*, not the
  filtering, so it is meaningful under `none`.
- [Someone re-runs the reasoning and re-flips the column] → The migration comment states the
  contents-vs-intent argument inline, next to the `UPDATE`.

## Migration Plan

1. Ship `V12__mark_request_tags_sensitive.sql`. Idempotent in effect and backward compatible: it
   touches one catalog row, no ClickHouse object, no ingested data.
2. On deploy, `oidc` environments immediately stop exposing `request_tags` to non-`FULL_ADMIN`
   callers. `none` environments are unaffected.
3. Rollback: `UPDATE column_mapping SET sensitive = FALSE WHERE table_name = 'dial_usage_log' AND
   source_name = 'request_tags';`, or the equivalent `PATCH` for a single environment. No schema
   reversal is involved.
