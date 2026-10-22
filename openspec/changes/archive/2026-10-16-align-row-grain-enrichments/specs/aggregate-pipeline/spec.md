## ADDED Requirements

### Requirement: A refresh restricted through an aligned enrichment pre-selects partitions

A refresh SHALL pre-select partitions when it restricts its recompute to dirty group keys through a column of
an aligned enrichment: the dirty-key `IN` restriction SHALL be treated as a qualifying lookup conjunct and SHALL gain the
partition pre-selection of the structured-query capability ("A lookup on an aligned enrichment pre-selects
the source's partitions"), in both the form declaration (`AggregateCompiler`) and the SQL declaration
(`AggregateSqlCompiler`). The recompute's input SHALL take the restriction in its `WHERE`; the restricted enrichment's
derived table SHALL keep the dirty-key restriction alone, the pre-selection reaching it — and every other aligned join —
through the tuple join; and every join without an aligned partition pair SHALL be keyed on it. In the SQL declaration
the restriction SHALL yield wherever the dirty-key restriction does — when the absence image of the keys is itself
dirty — so that the recompute still reads every partition exactly when unmatched rows belong to a dirty key. The
recomputed aggregates SHALL equal those of the unrestricted recompute for the same dirty keys.

#### Scenario: Recomputing dirty sessions reads only their active days

- **GIVEN** `sessions_rollup` grouping by `usage_client_identity.client_session_id`, aligned
- **WHEN** a refresh recomputes two dirty sessions
- **THEN** the recompute reads `dial_usage_log` and `usage_client_identity` only in the partitions holding
  those sessions' enrichment rows, and writes the same aggregates as before

#### Scenario: The SQL declaration is restricted the same way

- **GIVEN** an SQL-mode aggregate restricted through the same aligned column
- **WHEN** it is refreshed
- **THEN** its leaf restriction carries the same partition pre-selection, and the aggregates it writes equal the
  unrestricted recompute's
