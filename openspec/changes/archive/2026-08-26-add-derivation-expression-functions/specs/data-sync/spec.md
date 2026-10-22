## MODIFIED Requirements

### Requirement: Closed named transform registry

The service SHALL support a fixed, named set of transforms referenced by columns in the mapping, and
SHALL NOT support arbitrary user-supplied expressions or code. The registry SHALL include at least
`iso-to-ch-datetime` (convert an ISO-like timestamp to a ClickHouse `DateTime64` literal),
`string-array` (coerce a JSON array to a list of strings), and `lowercase-json-keys` (lower-case the
keys of a JSON object). A column referencing a transform name that
is not in the registry SHALL cause a fatal configuration error at load time.

`lowercase-json-keys` SHALL apply to a column of `object` kind and SHALL lower-case the object's
**top-level member names** only, leaving every value byte-for-byte unchanged and leaving nested
objects untouched. Where two source members differ only in key case, which one survives SHALL be
unspecified — the mapping is for header maps, where a producer sends one spelling per request. The
`dial_usage_log` mapping SHALL apply it to `request_tags`, so that header keys are stored in a single
case going forward.

This transform applies at ingest and therefore to newly ingested rows only. Rows stored before it is
deployed keep their original key case, so `request_tags` carries mixed key case across the cutover.
Readers that must span both sides SHALL use the case-insensitive extraction function rather than
assume a single spelling (see [structured-query](../structured-query/spec.md),
`json_extract_string_ci`). Reconciling the stored history is explicitly out of scope: no re-ingest and
no in-place rewrite is implied by this transform.

#### Scenario: Known transform is applied

- **WHEN** a column declares the `iso-to-ch-datetime` transform and its source value is an ISO-like
  timestamp
- **THEN** the inserted value is the timestamp rendered as a ClickHouse `DateTime64` literal

#### Scenario: Unknown transform is rejected

- **WHEN** a column references a transform name that is not in the registry
- **THEN** the service fails to load the mapping with a configuration error


#### Scenario: Header keys are lower-cased at ingest

- **WHEN** a record's headers object carries `X-Claude-Code-Session-Id` and the `request_tags` column
  declares the `lowercase-json-keys` transform
- **THEN** the stored document carries the member under `x-claude-code-session-id`, and its value is
  unchanged

#### Scenario: Only keys are lower-cased, never values

- **WHEN** the same transform is applied to a document whose values carry upper-case characters — a
  mixed-case user agent, an e-mail address
- **THEN** every value is stored byte-for-byte as received

#### Scenario: History is unaffected

- **WHEN** the transform is deployed
- **THEN** rows ingested before the deploy keep their original key case, and no re-ingest or in-place
  rewrite is triggered
