## Context

The role-keyed access API (`openspec/specs/table-access-control/spec.md`, "Role-keyed access API" and "Access
configuration generation") has not shipped. It addresses a role as a path segment, which needs Tomcat's
`encodedSolidusHandling=passthrough`, a firewall that accepts `%2F` and `%25` on every endpoint, and a health-endpoint
special case for the `IllegalArgumentException` the passthrough provokes. Every access write takes the definition's
`PESSIMISTIC_WRITE` row lock (`findForUpdate`) and is checked against one table-wide `access_generation`. The revision
read (`TableMapper`, revision path) filters columns by the snapshot's own column policies only.

## Goals / Non-Goals

**Goals:**
- Every role addressable without touching the firewall or the servlet container.
- Writes to different roles never conflict; a retried identical `PUT` never conflicts with itself.
- No read surface, the revision read included, shows a column the current column policies hide.
- A bounded, reported wait when two writes hit one table definition.

**Non-Goals:**
- Per-role generations in the API response: the response keeps one `generation`.
- A conditional `GET`, `If-Match` headers, or an `ETag`.
- Mapping renamed columns back to their old names in revision snapshots.
- Changing how several roles' column policies combine (only documenting it).

## Decisions

### D1. The role is a query parameter; the default firewall is restored

`GET`/`PUT`/`DELETE /v1/tables/{name}/access?role=<role>`; the list stays `GET /v1/tables/{name}/access` (mapped with
`params = "!role"`, the one-role read with `params = "role"`). A query value is decoded once by the container and may
hold any character, so no firewall or connector setting is needed. The `RequestFirewallConfiguration` keeps only the
`RequestRejectedHandler`, which renders a firewall refusal as `ErrorView` `400 bad_request`: the default firewall,
connector and health endpoint behave as before the role-keyed change, and the one remaining difference — an
`ErrorView` body instead of a bare `400` — follows the project rule that every error uses `ErrorView`. The health
special case in `DefaultExceptionHandler` and its test exclusion are removed: with `%2F` rejected by Tomcat it is
unreachable.

*Missing or blank role.* `@InitBinder("role")` registers a `StringTrimmerEditor(true)`, so a blank value converts to
`null` and Spring raises `MissingServletRequestParameterException`, the same as an absent one. Both happen during
argument resolution, before the method-security interceptor that enforces `TABLE_GRANT`, so they answer
`400 bad_request` first, as the existing "binding before privilege" order requires. The service still trims. A
`role` sent more than once would bind comma-joined (`a,b`), a role nobody carries, so the same `@InitBinder` refuses
it with `InvalidRequestParameterException` (`400 bad_request`), also during argument resolution.

*OpenAPI.* OpenAPI has one operation per path and method, and springdoc merges two handlers on one into a single
`GET` whose `role` is required, which leaves a generated client no way to list. The list handler is therefore hidden
from the document, and the one-role read documents both answers: `role` declared at the operation as an optional
query parameter (the handler's own binding hidden), and a `200` whose schema is one of the two response shapes.

*Alternatives rejected:* keeping the path form as an alias (the API has not shipped; two spellings double the
contract); a role in the body for `PUT` only (`GET` and `DELETE` would still need another carrier); base64 in the
path (opaque to operators and to logs).

### D2. One counter, plus each role's last-change mark

`table_definition.access_role_generations JSONB NOT NULL DEFAULT '{}'` holds `{"<role>": <generation>}`, the counter
value at which that role's entry last changed, a removal included (the tombstone is kept, so a client that read
before a removal still conflicts). It is mapped as text through `RoleGenerationCodec`, like `column_policies`, and is
`@NotAudited`, like `access_generation`, so a bump records no revision and snapshots never carry it. It goes into the
unreleased `V57__role_keyed_table_access.sql`; no deployment has applied it.

`PUT ?role=R` with `G`, under the row lock:
1. the existing checks up to the usable-generation `422`;
2. the remaining checks (role, `table_is_system`, body) are run, and their refusal, if any, is held back;
3. if they passed and the validated entry equals R's stored entry (decoded values, as the "unchanged `PUT` records
   nothing" rule already compares them) → `200` with the current generation, nothing written;
4. if `G` > counter, or R's mark > `G` → `409 access_conflict`;
5. the held-back refusal, if any;
6. write, counter + 1, R's mark = counter.

Running the checks before the conflict decision but reporting them after keeps the documented order (a conflict
before an invalid role or body) while letting step 3 compare a validated entry, which is what was stored. A request
the service would refuse is never "unchanged", so a legacy entry that no longer validates (a bundle-named role stored
by an earlier build) is not silently accepted. The checks that can be held back throw only `TableValidationException`
and `SystemTableException` from beans that are not transactional proxies, and every path that holds one back ends in
a throw, so no caught exception marks the transaction rollback-only before a commit.

`DELETE ?role=R[&generation=G]`: the generation is a query parameter, because `DELETE` bodies are poorly supported by
clients and proxies. Order: `403`, table `404`, unusable `G` `422`, no entry `404`, conflict `409`; a missing entry is
reported as `404` even with a stale `G`, which is the simplest rule and loses nothing (there is nothing to delete).

`ColumnPolicyRewriter.apply` bumps the counter once, as before, and marks every role whose list differs from the
stored one. A write that changes nothing touches no mark.

*Alternatives rejected:* per-role counters returned in the API (a second token per role on every read and write,
and a list response that is no longer one consistent snapshot); per-role generations in a separate table (a join on
every access read for the same information); keeping a strict table-wide check but retrying on the server (hides
real conflicts on the same role).

### D3. Revision snapshots intersect with the current policies

`SensitiveColumnAccess.visibleColumnsAtRevision(snapshot)` = `visibleColumns(snapshot)` ∩ `visibleColumns(name)`, the
second reading the live definition (all columns when the table no longer exists). `TableMapper` uses it on the
revision path only, and `columns_restricted` is still judged over the revision's own columns. Names are matched as the
snapshot spells them; a renamed column is therefore hidden by a current list that names only its new name. That fails
closed and needs no rename history. For an enrichment both sides already read the source's current policies, so the
intersection changes nothing there except where the enrichment's own `modify` grant differs between then and now,
which again fails closed.

*Alternative rejected:* the current policies alone (would show, in an old revision, a column the revision's own
policies hid from the caller at the time).

### D4. Bounded wait on the definition row lock

`findForUpdate` moves from a `@Lock` query to a repository fragment (`TableDefinitionLocking` /
`TableDefinitionLockingImpl`) so every caller keeps its call. Hibernate renders the JPA lock-timeout hint on
PostgreSQL only as `NOWAIT` (timeout `0`) and ignores a positive value, so the fragment sets the session's
`lock_timeout` with `set_config('lock_timeout', …, true)` (transaction-local) around the locking query and restores
the previous value after it, so the bound applies to this lock and to no other statement of the transaction. On
SQLSTATE `55P03` (`lock_not_available`) it throws `TableBusyException`, mapped to `409 table_busy`: the request
changed nothing and may be retried, which is what `409` with a stable code tells a client. The timeout is
`tables.lock-timeout` (`TABLES_LOCK_TIMEOUT`, default `10s`), in the existing `tables` group. A value below `1ms`
renders as `0ms`, which PostgreSQL reads as no limit (a negative one fails every write), so it is refused at startup,
as a sub-second ClickHouse timeout is.

A schema definition flips the row to `ACTIVE` in a transaction of its own after the ClickHouse `CREATE`. A
`table_busy` there is raised before anything is written, so it takes the compensation path of a failed `CREATE`:
drop the table and mark the row `FAILED` (left `PENDING` when that mark is busy too). The request can then be sent
again as it was; without this the retry would fail on the existing table.

*Alternatives rejected:* `503` (the service is healthy; the conflict is with another write of the same resource);
reusing `access_conflict` (it tells the client to re-read, which is wrong for a wait); `SET LOCAL lock_timeout` for the
whole transaction (would bound unrelated lock waits later in it and fail them with other errors).

### D5. Documented, not changed: combining two roles' column policies

The rule (`openspec/specs/table-access-control/spec.md`, "Column visibility by caller role") is unchanged; a scenario
and the guides now show its consequence: a caller holding `A` (rows `P1`, every column) that gains `B` (every row,
`["x"]`) sees only `x`. Giving `B` a row policy does not help, since both roles are then confined and only the columns
both allow are shown; listing on `B` what `A`'s readers need, or leaving `B` unrestricted, does.

## Risks / Trade-offs

- [The marks document grows by one key per role ever written on a table, tombstones included] → Roles per table are
  few; a key is a role name and a number. Not pruned.
- [A `PUT` whose entry equals the stored one answers `200` with no check of its generation, so it cannot tell a client
  that the entry changed and changed back since its read] → Intended: the stored state is what the client asked for.
- [A conflict check that ignores other roles lets two clients each write a role that only makes sense together (for
  example a row policy on one role that relies on another role's `*` read)] → Accepted; such coordinated edits are
  read-check-write by one operator, and `ineffective_grants` reports the lifting cases.
- [A write to a busy table now fails after `tables.lock-timeout` instead of waiting] → Holders are short (an access
  write, a metadata update) except a schema `PATCH`, which holds the lock across its ClickHouse `ALTER`; raise the
  timeout if such `PATCH`es make `table_busy` frequent. The background enrichment relayout's catalog step uses the same
  lock; a timeout there fails that step, which, like any step failed past the exchange, the relayout resumes on the
  next start.
- [Old revisions now hide more columns from restricted callers] → Intended (fail closed); a caller holding
  `TABLE_GRANT` still reads the revision's `roles`.

## Migration Plan

The change edits the unreleased `V57__role_keyed_table_access.sql` (adds `access_role_generations`); no deployment has
applied that migration, so no new migration is needed. No data rewrite. One new optional property,
`TABLES_LOCK_TIMEOUT`. Clients use `?role=` from the start.
