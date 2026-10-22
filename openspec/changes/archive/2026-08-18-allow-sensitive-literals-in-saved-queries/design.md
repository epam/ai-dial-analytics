## Context

See proposal.md — Why. Current state, in the terms the implementation is expressed in:

- `SensitiveLiteralGuard` (`service/query/saved/`) walks a `StructuredQuery`'s `filter` and `having`,
  resolving `select` aliases in both directions, and throws `SensitiveLiteralNotAllowedException` when a
  single comparison holds both a reference to a `sensitive` column and a literal.
- `SavedQueryBodyResolver` injects the guard and calls it at two points: for a structured body, *before*
  translation (so the guard's error wins over any translation error); for a SQL body, *after* translation
  (translation is what identifies the entity the guard needs).
- `DefaultExceptionHandler` maps the exception to `422 sensitive_literal_not_allowed`.
- Coverage lives in `SensitiveLiteralGuardTest` (unit, the walker's corner cases) and
  `SavedQueryAuthIntegrationTest` (end-to-end, both body kinds plus the non-entitled caller).

The guard is a leaf: nothing outside the saved-query write path constructs it, catches its exception, or
depends on its ordering. Sensitive-column *read* filtering lives elsewhere entirely — `CatalogQueryService`
builds the caller's `QueryEntityModel`, and `SensitiveColumnAccess` decides entitlement — and this change
does not touch either.

## Goals / Non-Goals

**Goals:**

- Delete the restriction and everything that exists only to serve it, so no dormant walker remains to be
  mistaken for a live control later.
- Keep the two behaviors the removed requirement also carried — no read-side redaction, and a non-entitled
  caller stopped at field resolution with no existence signal — demonstrably intact, by test rather than by
  assertion.
- Leave the guard's own ordering concerns behind: with no guard, `SavedQueryBodyResolver` collapses to
  "translate, then take the entity".

**Non-Goals:**

- Not touching sensitive-column read entitlement, `SensitiveColumnAccess`, or the indistinguishability rule.
- Not adding parameterization to saved queries. The removed requirement cited its absence as why a rejection
  had no remedy; the remedy chosen here is to allow the literal, not to introduce placeholders.
- No configuration flag to re-enable the restriction (see Decisions).
- No data migration or backfill: nothing stored is invalidated by a relaxation.

## Decisions

**Delete rather than disable.** The alternative was a config flag
(`SAVED_QUERY_ALLOW_SENSITIVE_LITERALS`, default true) keeping the walker in place. Rejected: a security
control that is off by default is worse than no control — it reads as protection in code review while
providing none, and the walker is ~90 lines of alias-resolution subtlety that would go untested and rot.
The premise driving this change is a property of the deployment (PostgreSQL is trusted), not a per-tenant
toggle, and the git history keeps the walker recoverable if the premise is ever revisited.

**Retire the machine code rather than keep it reserved.** `sensitive_literal_not_allowed` is removed from
`DefaultExceptionHandler` outright. `ErrorView`'s `error` field is a stable code *per failure mode*, and this
failure mode ceases to exist; leaving an unreachable handler behind would only mislead. This is a
relaxation, so no client breaks: nothing that used to succeed changes, and a client branching on the code
simply never sees it again.

**Guard call sites removed, translation order preserved.** In `resolveStructured` the guard ran before
translation *specifically* so its error outranked translation's; with the guard gone that ordering
constraint disappears and the method is just `requireModel` + `queryTranslation.toSql`. `resolveSql` keeps
translating first — it needs the translated entity regardless. Neither path's externally visible errors
change, because translation was always the authority on everything except the guard.

**Invert the integration tests instead of deleting them.** `SensitiveLiteralGuardTest` covers only the
deleted walker and goes away with it. The `SavedQueryAuthIntegrationTest` cases, by contrast, describe
externally visible behavior that still exists — the same requests, with the opposite outcome. Rewriting
them to assert `201` + a stored body that still contains the literal makes the relaxation a tested
property, so a future accidental reintroduction of a sensitivity check on the write path fails the build.
The non-entitled-caller case is kept verbatim: it is the one assertion in that file this change must not
alter.

**Leave the read path ungated, and say so in the spec.** Removing the write gate changes what the
existing, unchanged read path can hand out. `SavedQueryRepository.findVisibleInScope` makes any `common`
row visible to every caller (`s.scope = :common OR s.ownerId = :author`); `SavedQueryAccess.requireWritable`
gates only *writes* to `common` on `FULL_ADMIN`, and nothing on the read path consults
`SensitiveColumnAccess`. So a literal a `FULL_ADMIN` binds to a `sensitive` column in a `common` body is
returned verbatim to a `READ_ONLY_ADMIN` who is refused that column on every query surface. That is a
cross-role disclosure *inside the ADAS API*, which the trusted-perimeter premise does not cover — the
recipient is an API caller, not a database operator.

It is accepted here on a second, narrower premise: writing `common` requires `FULL_ADMIN`, the same role
`SensitiveColumnAccess.maySeeSensitive()` grants sensitive columns to, so every such value is published by a
caller already entitled to it, into the scope whose defined meaning is "shared with everyone holding an
application role". `personal` rows are readable only by their owner and disclose nothing new. Two
alternatives were considered and rejected for this change:

- *Gate the read* — hide (404) or refuse a saved query whose body references a `sensitive` column the caller
  cannot resolve. Rejected: it contradicts two decisions this capability already made and shipped —
  "`common` rows are readable by any application role" and "column names are schema facts and stay visible;
  a client that cannot resolve one renders an unavailable-field state". Detecting the condition also needs
  either the alias-resolving walker this change deletes or a translate-on-read, and translate-on-read would
  make any body that has since gone stale (dropped column, renamed entity) unreadable and undeletable by
  everyone. It is a read-path access-control change deserving its own reviewed design, not a rider here.
- *Redact or omit the literal on read* — rejected: the DTO's body is a query a client posts straight to
  `/v1/queries/execute`, so a partially redacted body is an unexecutable body, and `query`/`sql` both null
  breaks the exactly-one-body contract every client relies on.

Because the behavior is therefore load-bearing rather than incidental, it is written into the delta spec as
a requirement with its own scenario, and covered by an integration test —
`commonSensitiveLiteralIsReadableByAnyApplicationRole` — so a later reader sees a deliberate, tested
property instead of an accident.

**Update the capability Purpose in the main spec directly.** `openspec/specs/saved-queries/spec.md` opens
with a Purpose paragraph asserting "No data observed in the data store is copied here" as *the boundary
this capability defends*. That statement becomes false, and a delta cannot restate a Purpose for an
existing capability (the delta's Purpose is ignored at archive). It is therefore edited in the main spec as
part of this change, alongside the delta.

## Risks / Trade-offs

- **A real data-protection boundary is removed; personal data will land in PostgreSQL backups, replicas, and
  any downstream copy of the metadata database.** → Accepted deliberately on the stated premise that
  PostgreSQL is a trusted environment on the same footing as the data store. Mitigation is organizational,
  not code: the premise is recorded in the spec Purpose and the proposal so a future reader sees *why* the
  control is absent rather than assuming it was overlooked. Called out for explicit human sign-off in review.
- **A `common` saved query becomes a channel for one sensitive value per query, readable by roles denied
  that column.** A `FULL_ADMIN` who saves `WHERE user_email = 'someone@example.com'` at `common` scope
  publishes that address to every `READ_ONLY_ADMIN` in the deployment, none of whom could have read the
  column themselves. → Accepted on the `common`-write-role premise above, with the alternatives that would
  close it recorded there; bounded to values an entitled caller deliberately typed into a shared definition
  (no bulk egress: the row values themselves stay behind the query surfaces' own entitlement check, and the
  non-entitled caller still cannot execute the saved query). Called out for explicit human sign-off in
  review as a distinct decision from the PostgreSQL-storage one. A deployment that cannot accept it should keep
  sensitive-literal queries at `personal` scope, where only the owner can read them.
- **The premise is deployment-wide, so a deployment where PostgreSQL is *not* trusted inherits the
  relaxation silently.** → No mitigation in this change (a flag was considered and rejected above); the
  proposal states the premise as a precondition of the change rather than a universal property.
- **Saved queries with embedded personal data become subject to whatever retention the data store had.** The
  data store's own retention no longer reaches these copies — deleting a person from the data store leaves
  their email in a saved query body. → Out of scope here; flagged as a follow-up for whoever owns retention
  policy, not silently absorbed.
- **Losing the walker loses the alias-resolution logic**, should a sensitivity check ever be wanted again on
  any write path. → Recoverable from git history; the design records what it did and why it was subtle.

## Migration Plan

Single deployment, no coordination needed:

1. Deploy. Every previously-valid request stays valid; the `422 sensitive_literal_not_allowed` response
   simply stops occurring.
2. No schema migration, no config change, no data backfill. Nothing stored is invalidated.
3. Rollback is a revert of the commit. Note the asymmetry: saved queries created while the relaxation was
   live and containing sensitive literals will, after a rollback, still be *readable and executable* — the
   guard only ever ran on write. A rollback that must also purge such bodies needs a separate, deliberate
   cleanup step; it is not part of this change and reverting alone will not do it.
