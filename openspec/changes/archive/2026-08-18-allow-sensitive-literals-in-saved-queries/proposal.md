## Why

Saved queries currently refuse any body that compares a `sensitive` column to a literal value
(`sensitive_literal_not_allowed`, 422). The rule was written on the assumption that the metadata
database is a weaker perimeter than the data store, so a literal like `user_email = 'someone@example.com'`
persisted in a saved query would copy a person out of the controlled perimeter.

That assumption does not hold for the deployments this change targets: **PostgreSQL is a trusted environment on the same
footing as the data store** — same operational controls, same access boundary. With both stores inside one
perimeter the guard defends nothing, while its cost is concrete: a caller entitled to *see* a sensitive
column still cannot *save* the most ordinary query over it (`WHERE user_email = 'x'`), and because saved
queries are not parameterized there is no in-place remedy — the query simply cannot be saved at all.

## What Changes

- **Remove the sensitive-literal restriction entirely** from the saved-query write path. A body that binds
  a literal to a `sensitive` column is persisted like any other body, on both create and replace, for both
  `query`-kind and `sql`-kind bodies.
- **BREAKING** (relaxation only): the `sensitive_literal_not_allowed` machine code is retired. No request
  that previously succeeded changes behavior; requests that previously returned `422` now succeed. No
  client is required to change — a client that special-cased the code can drop that branch.
- Sensitive-column **read** entitlement on the query surfaces is unchanged. Which columns a caller can
  resolve, and the rule that a restricted column is indistinguishable from an absent one, keep working
  exactly as before: a caller who may not see a `sensitive` column still fails with the ordinary
  unresolvable-field `400 bad_request`. Only a caller already entitled to that column can therefore *write*
  such a literal.
- **New, accepted consequence on the saved-query read path.** The write gate does not bound who can later
  *read* the stored value. A `common` saved query is readable by every caller holding an application role,
  and its body is returned unredacted, so a literal a `FULL_ADMIN` bound to a `sensitive` column is
  returned verbatim by `GET /v1/saved-queries/{id}` (and in the `common` list) to a `READ_ONLY_ADMIN` who is
  refused that same column on `/v1/queries/execute`, `execute-sql`, discovery and Flight. Before this change
  no such body could exist, which is what made unredacted reads safe. This is accepted, not overlooked:
  `common` scope is publication, and only `FULL_ADMIN` — the role entitled to sensitive columns — can
  publish there, so the value is disclosed by a deliberate act of a caller entitled to it. It is a genuine
  widening all the same, it is a cross-role disclosure inside the ADAS API rather than a
  PostgreSQL-perimeter question, and it is the second thing this change needs signed off. A `personal`
  saved query discloses nothing new: it is readable only by its own owner.
- Saved-query bodies are still validated by the same translation their execute endpoint runs, so a saved
  query remains executable exactly as stored.
- The read path continues to return stored bodies without redaction — unchanged code, but no longer resting
  on "no sensitive literal can be stored". For a `personal` row the reader is the owner and nothing changes;
  for a `common` row it now rests on `FULL_ADMIN` being the only role that can write there (see the bullet
  above).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `saved-queries`: removes the requirement **"No sensitive literal is persisted"** and its five scenarios;
  restates the capability's data-boundary purpose so that the metadata database is described as being
  inside the trusted perimeter; re-grounds the no-redaction rule on that premise plus the `common`-write
  role. Adds scenarios asserting the new positive behavior (a sensitive literal is accepted and stored
  verbatim) so the relaxation is covered by spec rather than merely absent from it — including the read-side
  consequence, that a caller not entitled to the column does receive the literal from a `common` saved
  query, stated as a requirement so it is signed off rather than inferred.

## Impact

- **API surface**: `POST /v1/queries/saved` and `PUT /v1/queries/saved/{id}` no longer return
  `422 sensitive_literal_not_allowed`. No other status or field changes; `ErrorView` contract untouched.
  The machine code disappears from the service's error vocabulary.
- **Code removed**: `service/query/saved/SensitiveLiteralGuard.java`,
  `exception/SensitiveLiteralNotAllowedException.java`, the guard's `@ExceptionHandler` in
  `web/handler/DefaultExceptionHandler.java`, and the guard call sites plus injected dependency in
  `service/query/saved/SavedQueryBodyResolver.java`.
- **Code updated (docs/comments only)**: class javadoc on `SavedQueryController`, `SavedQueryService`, and
  `SavedQueryBodyResolver`, which currently explain the guard and its ordering.
- **Tests**: `service/query/saved/SensitiveLiteralGuardTest.java` is deleted; the sensitive-literal cases
  in `integration/SavedQueryAuthIntegrationTest.java` are inverted from "rejected with 422" to "accepted
  and persisted", keeping the existing case that a non-entitled caller still gets `400 bad_request`.
- **No changes** to: database schema (no migration), configuration or env vars, the query/SQL execution
  path, sensitive-column read entitlement, or the `table-access-control` and `authentication` capabilities.
- **Security note for reviewers**: this deliberately removes a data-protection boundary, and there are
  **two** distinct consequences to sign off, not one.
  1. *Storage*: personal data bound as a literal is now persisted in PostgreSQL and reaches its backups,
     replicas, and any downstream copy of the metadata database. Accepted on the stated premise that
     PostgreSQL is a trusted environment on the same footing as the data store.
  2. *API read*: a `common` saved query authored by a `FULL_ADMIN` returns that literal verbatim to every
     caller with an application role, including a `READ_ONLY_ADMIN` denied the underlying column on every
     query surface. The trusted-perimeter premise does **not** cover this one — the recipient is an ADAS
     API caller, not a database operator. Accepted on a separate premise: writing `common` is restricted to
     the role that is entitled to sensitive columns, so publishing such a value is a deliberate act by an
     entitled caller. Alternatives that would close it (gating the read on entitlement, or redacting
     literals) are recorded in design.md and were rejected there; a reviewer who disagrees should say so in
     review, since reversing that decision is a change to the read path, not to this relaxation.
