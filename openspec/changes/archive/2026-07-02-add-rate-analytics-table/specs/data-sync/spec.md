## ADDED Requirements

### Requirement: Multiple ingest targets

The service SHALL support a configurable list of ingest **targets**, where each target pairs a
ClickHouse table name with its own column mapping (see "Configurable column mapping") and an optional
match predicate (see "Conditional row emission via a match predicate"). During a run, each parsed
record SHALL be offered to every configured target; a target that emits for the record (its predicate
matches, or it declares none) produces one row from that target's mapping, so a single record MAY
produce rows in more than one target table. Each target SHALL batch and insert into its own table via
the dedicated writer credential, and the fail-fast startup schema validation SHALL be applied to each
target independently. The default configuration SHALL include the `dial_usage_log` target with no
match predicate, preserving existing behavior; the configuration SHALL be environment-driven and
remain backward-compatible for a single default target.

The deduplication-key drop rule (see "Records without a deduplication key are dropped") SHALL apply
per target: a record lacking the key columns a target relies on is dropped for that target and counted,
independently of other targets.

#### Scenario: A record fans out to multiple targets

- **WHEN** a run has two configured targets and a parsed record satisfies both targets' emission rules
- **THEN** the service writes one row into each target's table from that target's mapping

#### Scenario: Default single target preserves existing behavior

- **WHEN** only the default `dial_usage_log` target is configured
- **THEN** every parsed record is mapped and written to `dial_usage_log` exactly as before

#### Scenario: Per-target startup validation

- **WHEN** the service starts with multiple targets configured
- **THEN** it validates each target's mapping against its own table schema and fails startup if any
  target's mapping diverges from its table

### Requirement: Conditional row emission via a match predicate

The service SHALL emit a target's row conditionally based on an optional `match` block that the
target's mapping MAY declare: an ordered list of conditions, each naming a dotted source path within
the record, a comparison operator (at least `ends-with`, `equals`, and `contains`), and a value. The
service SHALL emit a row for that target only when ALL declared conditions hold (logical AND) for the
record. A mapping that declares no `match` block SHALL emit a row for every record. Unknown operators
SHALL be rejected at load time (fail-fast), consistent with the closed transform and deriver
registries.

#### Scenario: Record matches the predicate

- **WHEN** a target mapping declares `match` requiring `request.uri` ends-with `/rate` and
  `request.method` equals `POST`, and a record satisfies both
- **THEN** the target emits a row for that record

#### Scenario: Record fails the predicate

- **WHEN** a record fails any one of a target's `match` conditions
- **THEN** the target emits no row for that record

#### Scenario: No predicate emits for all records

- **WHEN** a target mapping declares no `match` block
- **THEN** the target emits a row for every parsed record

#### Scenario: Unknown operator fails startup

- **WHEN** a mapping's `match` block uses an operator that is not registered
- **THEN** the service fails fast at startup rather than loading the mapping

## MODIFIED Requirements

### Requirement: Closed named deriver registry

The service SHALL support a fixed, named set of **derivers** — derivation functions that compute a
column value from one or more source nodes of the parsed record — referenced by columns in the
mapping, and SHALL NOT support arbitrary user-supplied expressions or code. A derived column entry
SHALL declare a `derive` name, an ordered non-empty `sources` list of dotted JSON paths, and MAY
declare an `args` map of static parameters; it MUST NOT also declare a `source`. The registry SHALL
include derivers sufficient to populate the calculated `events` columns: a deterministic UUID hash
over its concatenated sources (for `event_id`); a request-URI classifier (for `event_kind`); JSON
sub-document field extractors that re-parse a source value that is itself a JSON string (or read it
directly when already an object) and read a dotted `args.path` as a string or as an unsigned integer
(for `response_id`, `cache_creation_tokens`, `reasoning_tokens`, `mcp_method`, `mcp_tool_call_name`);
a JSON array length (for `number_request_messages`); a byte length of a source string (for
`request_body_bytes`, `response_body_bytes`); an HTTP-success predicate over `response.status`
(for `success`); and a **rate normalizer** that reads a JSON `rate` value at `args.path` and
normalizes it to a signed integer (for `rate_analytics.rate`) — a numeric value passes through as its
integer value, boolean `true` maps to `1` and `false` maps to `0`, and an absent or blank value yields
null (subject to the column's nullability fallback). A column referencing a deriver name that is not
in the registry, or a derived column missing its `sources` list, SHALL cause a fatal configuration
error at load time.

#### Scenario: Derived column is computed by its named deriver

- **WHEN** a column declares a `derive` name with a `sources` list (and optional `args`)
- **THEN** the inserted value is computed by the named deriver from the resolved source nodes, then
  subjected to the same absent/blank fallback (default, else null if nullable, else the kind's zero
  value) as a mapped column

#### Scenario: event_id is deterministic across re-ingests

- **WHEN** two records share the same `trace_id` and `core_span_id`
- **THEN** the configured `event_id` deriver produces the identical UUID for both, so it is a stable
  proxy for the natural `(trace_id, core_span_id)` deduplication key

#### Scenario: Embedded-JSON extraction reads a nested field

- **WHEN** a JSON-extraction deriver is configured with ordered `sources` and an `args.path`, and the
  first source whose value is present parses as a JSON document
- **THEN** the deriver returns that document's value at `args.path`, falling through to the next source
  when an earlier source is absent or lacks the path

#### Scenario: rate normalizer maps booleans and numbers

- **WHEN** the rate-normalizer deriver reads a `rate` value at its `args.path` that is `true`, `false`,
  or a number
- **THEN** it yields `1`, `0`, or the number's integer value respectively, and null when the value is
  absent or blank

#### Scenario: Unknown or malformed deriver entry is rejected

- **WHEN** a column references a deriver name that is not in the registry, or declares `derive` without
  a non-empty `sources` list, or declares both `source` and `derive`
- **THEN** the service fails to load the mapping with a configuration error
