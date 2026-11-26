## Why

A caller whose roles each carry their own row and column policy reads the union of the roles' rows, but sees a column
only when it is allowed on **every** row it reads (see `table-access-control`, "Column visibility by caller role").
The rule fails closed because the row union does not record which role admitted a row, so a column one confined role
allows is hidden altogether as soon as the caller gains a second role that does not list it. Gaining a role can
therefore take columns away, and a dashboard that worked for one role stops working with `unknown field` when the
user is given another. The data store can answer the question the rule cannot: whether the role that allows a column
admits a given row.

## What Changes

- A column some governing role allows, but that the current rule hides, becomes **masked** instead: it is listed and
  queryable, and on each row its value is the stored one where a role that allows it admits the row and absent
  (`NULL`) elsewhere. A column no governing role allows stays **hidden** exactly as today; a column visible today stays
  visible. The masked value is what every reference reads — projection, filter, grouping, ordering, join, function
  argument, aggregate input — so no part of a query can observe a value the caller may not see.
- **BREAKING** JSON query responses (`POST /v1/queries/execute` and `/execute-sql`, and so the saved queries and
  dashboard widgets a client runs through them) carry
  a reserved `$masked` member on each row that has absent cells, listing the output columns whose value was computed
  with an input absent for lack of permission. An aggregate or window cell is listed when any row it summarizes had
  such an input (a partial value). Rows with no absent cell carry no `$masked`. A client that treats every row member
  as a column sees one more member on rows where masking applies.
- **BREAKING** An output column named `$masked` is refused `400 bad_request` on every query surface.
- Two queries that reference a masked field are refused `400 bad_request`. A query that names the field fails today
  with `unknown field`, since the column is hidden. **BREAKING** for one that reaches it only through a wildcard
  (`SELECT * … INTERSECT SELECT * …`, or `SELECT *` beside a table-named alias): that ran today over the visible
  columns, and is refused once a masked field joins the wildcard. The two queries are:
  - an `intersect` or `except` whose operand outputs the field, since that shape has no row to take a mark from;
  - a query that carries a lambda parameter or an output name (explicit, derived or generated) that equals a table it
    reads or begins with `<table>.`, since such a name could capture the table-qualified column names the mask is
    evaluated over. An output named after the field it projects bare, such as an enrichment field without `as`, is
    exempt; every reference to it inside the query block that defines it is rendered as its masked expression, not
    its name.
- A rows-only entry (one carrying `rows` on a role with no read path) now narrows the columns shown on the rows it
  adds: a column its `columns` list leaves out is absent there. Today that list is ignored, and the reading role's
  columns show on those rows. An entry whose `columns` is `null` behaves as today, while one with a list can hide more
  than before and can turn `columns_restricted` to `true`.
- A table that stores a column whose physical name is the table's own name or one of its enrichments' keeps hiding
  instead of masking, and a TTL over a masked column is refused `403 column_not_entitled`.
- CSV export and Flight SQL return a masked cell as an empty field or an Arrow null, with no flag column.
- A null-rejecting comparison on a masked field (`=`, `<>`, `<`, `>`, `<=`, `>=`, `in`, the string-match operators,
  `is not null`) is evaluated so it keeps the stored column's primary-key, partition and skip-index pruning; without
  that, a filter on a masked key column reads the whole table.
- Schema discovery marks a masked field `"masked": true`; the catalog read, `column_count`, revision snapshots,
  schema changes, catalog export and import list a masked column like a visible one; `permissions.columns_restricted`
  is `true` when a column is hidden **or** masked.
- Pipeline authoring and the runner's data-plane endpoints refuse a masked column as they refuse a hidden one
  (`403 column_not_entitled`), and withhold an `enrichment_behind` note's instants for a masked version column: those
  reads run without a row filter or a mask. Catalog export, which carries no values, exports a masked column.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `table-access-control`: "Column visibility by caller role" distinguishes visible, masked and hidden columns and
  defines a masked column's per-row value; "Hidden columns are absent from every read surface" scopes its
  no-marking rule to hidden columns; "Caller-effective privilege reporting" counts a masked column in
  `columns_restricted`; "Per-role row policies" and "Role-keyed access API" let a rows-only entry's `columns` narrow
  the rows it adds; the pipeline paths refuse a masked column as a hidden one.
- `structured-query`: the response envelope gains the `$masked` row member; a masked field reads as absent at every
  reference, with no trace of the stored value; the enrichment pushdown requirements keep masked fields and mask the
  pushed results outside, and partition transfer from a masked partition field takes rewritten conjuncts only; "SQL
  translation" and "Output column naming when `as` is omitted" carry the masked exceptions; aggregate, window, distinct, union and scalar-subquery cells over absent inputs are flagged; `intersect`
  and `except` over a masked output are refused; null-rejecting comparisons on a masked field keep pruning; `$masked`
  is a reserved output name.
- `query-schema-discovery`: a field carries a `masked` flag.
- `table-management`: the `permissions` description counts a masked column in `columns_restricted`, and a schema
  change refuses a TTL over a masked column (`403 column_not_entitled`).
- `sql-query-translation`: "Set operations combine query blocks" names the `intersect`/`except` refusal over a masked
  output as its one exception.
- `query-csv-export`: a masked cell is an empty field and the export carries no flag.
- `flight-sql-endpoint`: a masked cell is an Arrow null and the stream carries no flag.
- `rest-sql-endpoint`: the SQL response envelope carries `$masked` as the DSL's does (the `$masked` alias refusal and
  the set-operation rule are structured-query requirements every surface shares).

## Impact

- **Code**: the per-caller column decision (`SensitiveColumnAccess`) returns, per column, visible / masked with its
  mask condition / hidden; the query model (`CatalogQueryService.buildModel`) binds a masked field to its masked
  expression; the query builder adds the per-row flag columns, the filter rewrite and the aggregate and window flags;
  result materialization builds `$masked`, removes the flag columns and nulls masked composite and computed cells;
  `SensitiveProjectionGuard` refuses masked columns; `TableCallerPermissions` reports them.
- **APIs**: `/v1/queries/execute`, `/execute-sql`, `/execute-csv`, `/execute-sql-csv`, schema discovery, Flight SQL,
  `GET /v1/tables` and `GET /v1/tables/{name}` (`columns`, `column_count`, `permissions.columns_restricted`), catalog
  export and import, revision snapshots, `PATCH /v1/tables/{name}/schema` (TTL), and the new refusals on
  `validate-sql`, `translate`/`translate-sql` and saved-query and dashboard save validation. `docs/openapi.json` gains
  the discovery field's `masked` member.
- **Docs**: `docs/row-level-security.md` (column policies), `docs/sql-reference.md` (the `$masked` alias and the
  set-operation refusal), `docs/grafana-dashboards.md` and the panels under `grafana/dashboards/` (a stray `$masked`
  column), the upgrade notes of the release that ships it.
- **No new configuration, migration or credential.** A caller with a single governing role on a table, or none of
  whose governing roles carries a row policy there (and no rows-only entry with a `columns` list), reads the same rows,
  columns and values as today. Only the `$masked` output-name refusal (BREAKING above) and discovery's `masked: false`
  member reach it.
