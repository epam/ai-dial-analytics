## Why

`usage-client-identity-live` can stop advancing and clamp `sessions_rollup` behind it. Its Class-1
materialize write is refused by the 2 GiB `pipeline.budget.*` ceiling with
`Code: 241 … (while reading column jwt_claims)`, and the window-narrowing shipped by `prune-keyed-json-joins`
cannot rescue it: the cost is one read stream per JSON path per part, so halving the window changes
nothing.

`prune-keyed-json-joins` shipped the mechanism that would fix it — a keyed grain join projects only the
JSON paths a statement addresses — but `usage-client-identity` cannot use it. Five of its six outputs
read `jwt_claims` through single-path `json_extract_string` and would each prune to one subcolumn. The
sixth is `json_extract_array("dial_usage_log_payload.jwt_claims", 'roles')` for `user_roles`, and
because `json_extract_array` is classified as reading the serialized document, it marks the whole
column unprunable — costing the other five their pruning too. One output blocks the pipeline.

That classification is wrong. It rests on "an array member comes back typed `Dynamic`, so there is no
subcolumn to address". The member is `Dynamic`, but that does not make it unaddressable: serializing
**that subcolumn** back to JSON and extracting from it returns the same array and reads one stream
instead of one per path.

**The stakes are higher than the query surface suggests.** Measured over the same two JSON columns
under the `FINAL` that every Class-1 materialize write sets: the whole columns cost on the order of a GiB
peak and over a minute, the pruned subcolumns on the order of a hundred MiB and seconds. `FINAL` amplifies
the whole-column cost by an order of magnitude, so pruning is not a saving on that path — it is what keeps
a Class-1 write under the ceiling at all.

**One rendering of this is silently wrong, and it is the obvious one.** `JSONExtract(toString(<col>.<key>),
'Array(String)')` reads naturally and reuses the `toString` the four scalar extractors already share.
`toString` over an array subcolumn renders *ClickHouse* array syntax — `['a','b']`, single-quoted — which
`JSONExtract` cannot parse, so it returns the empty array for every array of strings while still
answering correctly for an all-integer array, whose two syntaxes coincide. Measured against real data it
agreed with the shipped form on **none** of the sampled rows of `jwt_claims.roles`, nor on any of the
distinct role sets among them. A `user_roles` column silently emptied is worse than a stalled
pipeline, so the rendering that ships is the one verified value-for-value instead.

## What Changes

- **`json_extract_array` over an `object` field reads the addressed subcolumn**, rendered as
  `JSONExtract(assumeNotNull(ifNull(toJSONString(<col>.<key>), '')), 'Array(String)')`. Its results are
  unchanged: the same elements for an array member, and the empty array — never null — for an absent
  path, a non-array member or an unmatched `LEFT JOIN`.
- **It therefore stops forcing the whole-column fallback.** A statement whose only reference to an
  `object` column is `json_extract_array` now prunes that column's projection in a keyed grain join,
  as a single-path read already does, and no longer costs the column's other literal-path reads their
  pruning.
- **Registration stops reporting it.** The advisory note exists to tell an author they chose the
  paths x parts form; `json_extract_array` is no longer that form, so reporting it would send the
  author looking for a cheaper spelling that does not exist because this is it.
- **`json_extract_string_ci` is untouched.** A case-insensitive match has no statically known subcolumn
  — not knowing the key's spelling is the entire point — so it keeps the serialized-text rendering, the
  whole-column fallback and the registration note.

No new configuration, no migration, no wire-contract change. The statements ADAS renders change shape;
the set of declarations it accepts does not change at all, and no result value changes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `structured-query`: the requirement that both late extractors read an `object` column through its
  serialized text now covers `json_extract_string_ci` alone; the keyed-join pruning requirement drops
  `json_extract_array` from the references that fall back to the whole column; and the paths x parts
  cost requirement narrows what registration reports to the one function that still earns it.

## Impact

**Code.** `service/query/JsonExtractFunctions` (the classification both the renderer and registration
resolve from); `service/query/translate/ExprTranslator` (`subcolumnExtract`, plus the new
`subcolumnArray` rendering); `service/query/ObjectPathUsage` and
`service/pipeline/PipelineJsonReadNotes` (which read that classification rather than restating it);
`web/dto/pipeline/PipelineDto` (the `notes` schema description).

**Surfaces.** No wire change. `POST /v1/pipelines` and `PATCH /v1/pipelines/{name}` stop emitting a
`notes` entry for `json_extract_array` over an `object` column. The OpenAPI snapshot moves only by that
description's text.

**Acceptance.** `usage-client-identity-live` materializes within the budget against a running deployment
and `sessions_rollup` advances behind it.

**Out of scope.** `json_extract_string_ci`, which genuinely cannot be pruned. The budget boundary and
the window narrowing, both settled by `prune-keyed-json-joins`. Re-keying or re-partitioning
`dial_usage_log_payload`.
