## ADDED Requirements

### Requirement: A lookup on an aligned enrichment pre-selects the source's partitions

A statement whose `WHERE` carries a qualifying conjunct `C` over an aligned enrichment `E` SHALL gain
`partitionExpr(source.p) IN PS` in its own `WHERE`, alongside `C`, where
`PS = (SELECT DISTINCT partitionExpr(p) FROM E WHERE C [AND B] SETTINGS final = 0)`, `p` is the alignment column and
`B` is the statement's source-only bounds on the source's partition column, rendered on `E.p` instead. A bound is a
top-level source-only comparison conjunct that reads the source's partition column and nothing else and holds no
subquery — `request_time >= now() - INTERVAL 1 DAY`, say. `B` exists so that a statement which already bounds its time
does not pay a read of `C`'s column over all of `E`'s history to name the days it named itself; without a bound `PS`
reads `C`'s column across every partition of `E`.

`PS` SHALL NOT be repeated inside `E`'s derived table. ClickHouse evaluates an uncorrelated subquery once per scope, so
a copy there is a second read of `C`'s column, and it prunes nothing more: the analyzer carries the source's
restriction into `E`'s derived table, and into every other aligned join, through the tuple join's `source.p = E.p`
(measured on ClickHouse 26.3 and 26.8 under `do_not_merge_across_partitions_select_final = 1`, which every aligned read
carries). A join of the same statement without an aligned partition pair has no such equality; it SHALL be keyed on
`partitionExpr(source.p) IN PS` as one of its source-only key predicates, so that it reads the grain keys of the
selected partitions rather than hashing its whole table. When the statement is late-materialized, the restriction
SHALL appear in the page block only: the outer statement is confined to the page's keys, which ClickHouse carries into
every aligned join as well. A count for `include_total` SHALL take the same restriction as the page.

On the read path a top-level `AND` conjunct SHALL qualify only when all of these hold: it references one
column of one aligned enrichment alone; that column is not heavy; it has the form `col = <literal>` or
`col IN (<literals>)`; no literal is the column type's default value (`''` for text, `0` for a number); and it is
false on an unmatched row (the null-rejecting notion of "Null-rejecting enrichment-only conjuncts narrow the
enrichment's derived table"). Any other conjunct — a range, a substring match, a function call over the column,
`NOT IN`, `!=`, a comparison with the default value, a subquery, a lookup on a heavy column — SHALL NOT qualify, and
the statement SHALL be emitted exactly as before. A heavy column is left out because the pre-selection reads the
lookup column across every partition, which for a body is the read the shaping exists to avoid; a subquery because its
values may include the default an unmatched row reads. The pipeline paths (enrichment-runtime, aggregate-pipeline)
apply the same rewrite to the lookups they build themselves, an `IN (<subquery>)` among them. `C` SHALL be rendered
over the bare column in the pre-selection, never through `toString` or another wrapper.

The rewrite SHALL be exact: the result of a rewritten statement SHALL equal the result of the unrewritten
one. An admitted row's matched enrichment row satisfies `C` and shares the row's partition value, so it satisfies `B`
on `E.p` too and the row's partition is in `PS`; reading `PS` with `final = 0` only adds stale versions and can only
widen it; keying a join on a conjunct of the statement's own `WHERE` never changes which rows it returns; and an
unmatched row fails `C` and was never admitted.

When several conjuncts qualify, each SHALL contribute its own restriction. A statement with no qualifying
conjunct SHALL be unchanged.

#### Scenario: A session lookup prunes both tables to the session's days

- **GIVEN** an aligned `usage_client_identity` of `dial_usage_log`
- **WHEN** a query filters `"usage_client_identity.client_session_id" = 's1'`
- **THEN** the statement restricts `dial_usage_log` to the partitions holding an enrichment row of `s1`, its plan
  reads the enrichment's derived table in those partitions only, `PS` occurs once per scope that needs it, and the
  statement returns the same rows as without the restriction

#### Scenario: A time-bounded lookup bounds the pre-selection's own read

- **WHEN** a query filters `request_time >= <t>` and `"usage_client_identity.client_session_id" = 's1'`
- **THEN** `PS` reads `usage_client_identity` only in the partitions at or after `<t>`, and the statement returns the
  same rows as without the restriction

#### Scenario: A join without a partition pair is keyed on the pre-selection

- **GIVEN** a query that also reads an enrichment of `dial_usage_log` keyed on `chat_id`
- **WHEN** it filters `"usage_client_identity.client_session_id" = 's1'`
- **THEN** that enrichment's derived table is keyed on the `chat_id`s of the selected partitions' source rows, and the
  statement returns the same rows as without the restriction

#### Scenario: A late-materialized page and its count are pre-selected

- **WHEN** a lookup query projects a heavy payload column and is read late-materialized with `include_total`
- **THEN** the page block and the count each carry the restriction, the outer statement does not, and the rows and
  the total equal the unrewritten statement's

#### Scenario: A default-valued lookup does not qualify

- **WHEN** a query filters `"usage_client_identity.client_session_id" = ''`, or a numeric enrichment column `= 0`
- **THEN** no partition pre-selection is emitted and the statement returns the unmatched source rows as
  before

#### Scenario: A negated or ranged conjunct does not qualify

- **WHEN** a query filters an aligned enrichment column with `NOT IN`, `!=` or a range
- **THEN** no partition pre-selection is emitted

#### Scenario: An IN list lookup qualifies

- **WHEN** a query filters an aligned enrichment column with `IN ('s1', 's2')`
- **THEN** the pre-selection is emitted, and the result equals the unrewritten statement's

#### Scenario: A lookup on a heavy column does not qualify

- **WHEN** a query filters `"dial_usage_log_payload.response_body" = 'x'`
- **THEN** no partition pre-selection is emitted

#### Scenario: The lookup is rendered on the bare column

- **WHEN** a pre-selection is emitted for a lookup on a `string` column
- **THEN** `PS` does not wrap the column in `toString`
