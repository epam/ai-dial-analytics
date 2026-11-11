## Why

A row policy filter can only compare columns with constants, so "each caller reads only its own rows"
needs one policy per person, which nobody can maintain. A policy must be able to compare a column with
who the caller is, from a small fixed set of identity values rather than arbitrary token content.

## What Changes

- A row policy filter may reference two **caller values**, `caller.user_hash` and `caller.project`,
  written as `param` expressions with those reserved names. Every other `param` stays refused.
- A caller value is resolved on each read and inlined as a literal. A value the caller does not have is
  **unknown** and behaves as SQL `NULL`: a comparison with it matches no row, also under negation, while
  an `OR` with another branch still matches through that branch.
- `caller.user_hash` is computed the way DIAL Core derives the `user.id` field of its usage log, from
  three new settings per identity provider (claim name, salt, obfuscation flag) and the same three for
  per-user API keys. `caller.project` is the project of a project API key.
- The API-key identity on the Flight surface carries the same caller values as on HTTP.
- Row policies stay attached to roles. The confinement rule, the grant lists, the privileges and every
  table schema are unchanged; no enrichment needs a policy of its own for this.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `table-access-control`: a row policy filter accepts caller values; how they resolve on a read and what
  an unknown value means.
- `authentication`: how caller values are derived on each authentication entry point, the new settings,
  and that they are uniform across entry points sharing an introspection cache entry.

## Impact

- **API**: `PUT /v1/tables/{name}/row-policies/...` accepts `{"type": "param", "name": "caller.user_hash"}`
  and `caller.project` inside a filter; no new endpoint, no response change.
- **Config**: `providers.<name>.logging-key`, `.logging-salt`, `.obfuscate-user-email`, and
  `security.api-key.logging-key`, `.logging-salt`, `.obfuscate-user-email`. The salt is a new credential.
- **Code**: row policy validation and rendering, the row policy filter, the four authentication entry
  points and the caller identity they build.
- **Docs**: `docs/configuration.md` (six rows); behaviour in the two specs above.
- **No migration**, no catalog schema change, no change to DIAL Core.
