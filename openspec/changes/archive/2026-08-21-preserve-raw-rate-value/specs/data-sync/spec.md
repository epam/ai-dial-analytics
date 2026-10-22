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
(for `response_id`, `reasoning_tokens`, `mcp_method`, `mcp_tool_call_name`, `rate_analytics.rate_raw`);
a JSON array length (for `number_request_messages`); a byte length of a source string (for
`request_body_bytes`, `response_body_bytes`); an HTTP-success predicate over `response.status`
(for `success`); and a **rate normalizer** that reads a JSON `rate` value at `args.path` and
normalizes it to a signed integer (for `rate_analytics.rate`) — a numeric value passes through as its
integer value, boolean `true` maps to `1` and `false` maps to `0`, and an absent or blank value yields
null (subject to the column's nullability fallback). A column referencing a deriver name that is not
in the registry, or a derived column missing its `sources` list, SHALL cause a fatal configuration
error at load time.

The **string extractor** reads whatever scalar the path resolves to, not only a JSON string: a boolean
member SHALL yield the text `true` or `false`, a numeric member SHALL yield its literal text, and a
string member SHALL yield its unquoted content. A member that is an object or an array SHALL yield no
value, so the column takes its absent/blank fallback rather than a serialized document. This is a
statement of shipped behavior, made explicit because a column may depend on it to preserve the
submitted form of a polymorphic field (`rate_analytics.rate_raw`) rather than merely to read text.

A JSON-extraction deriver reads only sources that parse as a JSON document; a source that does not
(such as an SSE transcript) SHALL be skipped as if absent. A value the producer also reports outside
the response payload SHALL therefore be mapped from that report rather than derived from the payload,
so that its column does not depend on the response being a parseable document.

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

#### Scenario: Unparseable source is skipped

- **WHEN** every source of a JSON-extraction deriver holds text that is not a JSON document (such as an
  SSE event stream)
- **THEN** the deriver yields no value and the column takes its absent/blank fallback, rather than the
  ingest failing

#### Scenario: rate normalizer maps booleans and numbers

- **WHEN** the rate-normalizer deriver reads a `rate` value at its `args.path` that is `true`, `false`,
  or a number
- **THEN** it yields `1`, `0`, or the number's integer value respectively, and null when the value is
  absent or blank

#### Scenario: String extractor preserves a non-string scalar's submitted form

- **WHEN** the string-extractor deriver reads a value at its `args.path` that is `true`, `false`, or a
  number
- **THEN** it yields the text `true`, `false`, or the number's literal text respectively, so that a
  boolean submission stays distinguishable from the numeric value the rate normalizer maps it to

#### Scenario: String extractor yields no value for a non-scalar

- **WHEN** the string-extractor deriver reads a value at its `args.path` that is an object or an array
- **THEN** it yields no value and the column takes its absent/blank fallback, rather than serializing
  the document or failing the ingest

#### Scenario: Unknown or malformed deriver entry is rejected

- **WHEN** a column references a deriver name that is not in the registry, or declares `derive` without
  a non-empty `sources` list, or declares both `source` and `derive`
- **THEN** the service fails to load the mapping with a configuration error
