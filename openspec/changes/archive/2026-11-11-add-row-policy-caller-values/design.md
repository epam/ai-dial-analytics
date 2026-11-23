## Context

See `proposal.md` for the motivation. The pieces this design builds on:

- A row policy is a filter tree stored per role on a table and rendered to a ClickHouse predicate on every
  read; the predicates travel in the `additional_table_filters` setting, which ClickHouse applies to every
  reference of the table in the statement. See
  [table-access-control](../../../specs/table-access-control/spec.md).
- A policy refuses every `param` today, because nothing could bind one. The refusal exists in the policy
  scope check and again in the expression translator and the SQL renderer.
- `dial_usage_log` identifies the user only by `user_hash`, which is DIAL Core's `user.id` log field:
  SHA-256 over a per-provider salt followed by one configured claim, or the raw claim when Core's
  obfuscation is off. Rows written for a project key or a user without that claim store an empty string.
- An enrichment such as `dial_usage_log_payload` is never a statement's `FROM`; it is only joined from its
  source, on the DSL, ad-hoc SQL, CSV and Flight SQL paths alike. A policy on the source therefore covers
  the enrichment's rows.

## Goals / Non-Goals

**Goals:**

- One policy on one role expresses "each caller reads its own rows".
- The caller's identity reachable from a policy is a closed set of two values.
- A caller without a value never sees more than a caller with one.

**Non-Goals:**

- Grants or policies naming one person; an "every caller" subject; an all-rows clearance privilege or an
  exception list on a policy. The existing rule already lets a role with no policy read every row.
- Email or free-form claim paths as caller values; ready-made "own rows" presets.
- Any change to the confinement rule, to table schemas, or to DIAL Core.
- Policies on tables derived from the usage log. Row policies do not propagate to derived tables; each
  table holding `user_hash` gets its own policy.

## Decisions

The first four are the maintainers' decisions. The rest are implementation choices made in this design.

**1. Caller values are a closed set: `caller.user_hash` and `caller.project`.** Every mature row-security
system exposes a few identity functions rather than raw credential access; a closed set keeps a policy
reviewable and keeps token content out of rendered SQL. Rejected: a claim-path expression, which would let
a policy depend on any claim a provider happens to emit.

**2. ADAS recomputes the user hash from Core's three settings.** It needs no Core change and no call to
Core for JWT callers. Rejected for now: Core returning its computed hash from its user-info endpoint. That
would remove the settings, the drift risk and the provider-selection problem below, at the cost of a Core
change and a Core round trip per caller; it remains the preferred direction if those risks materialise.

**3. An absent value is unknown and renders as `NULL`.** Three-valued logic gives the required behaviour
with no special cases: no match, no match under `NOT`, and `OR` still works, so
`user_hash = caller.user_hash OR project_id = caller.project` serves both kinds of caller. Rejected:
"comparison is false" (inverts under negation and opens every row); "a policy referencing an absent value
matches nothing" (breaks the `OR` form, since no caller has both values).

**4. Policies stay on roles; the lifting rule is untouched.** An administrator role has no policy and reads
everything, also for a person holding both roles. The cost is recorded under Risks.

**5. Syntax: reserved `param` names.** The filter grammar already has `param`; the wire format, the stable
serialization and the stored tree need no new node. The policy scope check admits exactly the two names.

**6. A caller value is only a direct operand of `=` or `!=` against a `string` field, and substitution
happens before translation.** On a read, the stored tree is rewritten and only then handed to the existing
filter translator: a known value replaces the `param` with a string value node; an unknown value replaces
the **whole comparison** with a predicate that is `NULL` for every row. The translator and renderer keep
refusing `param`, keep their rule that `= null` means `IS NULL`, and keep refusing a null function
argument, because a null never reaches them through this path. At save time the rewrite runs with a
512-byte placeholder string, which gives the worst-case size. The placement rule is what makes this sound:
`coalesce(caller.user_hash, '')` or a `case` branch could turn an unknown value into a constant and match
the empty-hash rows; an `in` list would need per-item null semantics; an enum, uuid or object field would
make the rendering, or a read-time error, depend on the caller's value. Negation stays available through
`not` and `!=`. Rejected: allowing caller values anywhere an expression may go, which needs a typed-null
node accepted on every function path and cannot stop a function from erasing the unknown.

**7. Values longer than 512 bytes are unknown.** The 16 KiB predicate limit exists because the predicate
travels in the request URL, and rendering also runs on reads; without a bound, a long raw claim could turn
a read into a `400`. A hash is 64 characters and project names are short, so the bound costs nothing real.

**8. Blank is unknown, although Core hashes a blank claim.** Core produces one shared hash for every user
with an empty claim. Treating that as a known value would put those users in one shared scope.

**9. Settings mirror Core's names**: `providers.<name>.logging-key`, `.logging-salt`,
`.obfuscate-user-email`, and the same three under `security.api-key.*`. An operator copies them from
Core's identity-provider configuration. The salt is a **new credential**: supplied through the
environment, never logged, never returned.

**10. Caller values live on the caller identity built at authentication**, next to the email, and are
read through the existing security-claims accessor. The Flight API-key path built its token without claims
or project while sharing its cache entry with HTTP; both surfaces now call one builder, so the cached
identity carries the same caller values whichever surface populated it.

**11. Diagnosability without a new endpoint.** When a confining policy references a caller value that is
unknown, the read logs it at debug level and sets a span attribute naming the value (not its content).
Rejected: a "who am I" endpoint returning the resolved hash; no consumer asks for it yet.

## Risks / Trade-offs

- [The salt in a second service widens who can test candidate claim values against `user_hash`] → treat it
  as a secret with the same handling as other credentials; decision 2 names the way out.
- [Settings drift from Core, or a salt rotation in Core] → users read no rows, never other users' rows;
  the debug log and span attribute in decision 11 show which value was unknown, and a fixture test pins the
  formula. Rows written under an earlier salt stay unreachable for own-rows policies.
- [Per-user API keys do not name their provider] → one `security.api-key.*` setting; a deployment with
  different salts per provider leaves it unset and those callers have no hash.
- [Confinement is per role] → a caller who also holds any role that reads the table with no policy is
  unconfined. This is the existing rule; `rows_restricted` reports the outcome per caller.
- [Other tables carry `user_hash`] → `rate_analytics` and pipeline targets built from the usage log need
  their own policy; a confined role should not hold `TABLE_WRITE` on them.
- [Cost of a `user_hash` policy] → measured on a synthetic local table of millions of rows with the
  `dial_usage_log` sort key, partitioning and skip index. A policy never makes a read more expensive than
  the same read by an unconfined caller, but an own-rows policy does not make it much cheaper either: the
  bloom index on `user_hash` is consulted and selects a small share of the granules, and then, because the
  read is `FINAL`, ClickHouse expands the selection back to every granule whose primary-key range
  intersects it, which for a user spread across projects and time is the whole range read. What does narrow
  a confined read is what narrows any read: a time window (partition pruning) and, for a
  `project_id = caller.project` policy, the sort-key prefix. Dashboards for confined callers should carry a
  time filter, as they already should.
- [Cost of the enrichment join] → the policy is invisible to the enrichment join planner, so a confined
  query with no filter of its own joins the enrichment unkeyed. Measured the same way, that join was
  cheaper for a confined caller than for an unconfined one, and forcing the keyed form (`event_id IN
  (SELECT event_id FROM source)`) was slower than leaving it. The planner is therefore left unchanged.

## Migration Plan

No data or catalog migration. The feature is inert until a policy references a caller value. To enable:
set the three settings from Core's configuration, then store the policy. To roll back: delete or replace
the policy; the settings can stay.
