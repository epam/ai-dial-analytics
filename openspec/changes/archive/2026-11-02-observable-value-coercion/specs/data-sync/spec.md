## ADDED Requirements

### Requirement: A value that fails to coerce to its column's kind is counted and falls back like an absent one

The service SHALL read a present source value as its column's extraction kind strictly. A value the kind cannot
represent SHALL NOT be replaced by an invented value. A present value means one that is neither missing nor
JSON `null`.

| Kind            | Accepts                                                                                                                         |
|-----------------|---------------------------------------------------------------------------------------------------------------------------------|
| `long`          | An integral number that fits a signed 64-bit integer, or text whose trimmed form parses as one.                                 |
| `double`        | A number, or text whose trimmed form parses as a decimal; the producer's decimal value is preserved exactly.                    |
| `bool`          | `true` or `false`; the number `0` or `1`; the text `true`, `false`, `0` or `1` in any case, trimmed.                             |
| `array<string>` | An array. A scalar element is read as its text; an object or array element is read as its JSON text, as the `string` kind does. |
| `string`        | Any value. A scalar is read as its text; an object or array is read as its JSON text.                                           |
| `object`        | Any value, carried as it is.                                                                                                    |

Blank text, meaning text that is empty after trimming, SHALL be **absent** for every kind other than `string`
and `object`. It takes the column's absent/blank fallback and is not a coercion failure.

Any other present value is a **coercion failure**. Examples:
- a fractional number, a boolean, an object or an out-of-range number for `long`;
- unparseable text for `long` or `double`;
- any other number or text for `bool`;
- a scalar or an object for `array<string>`.

A coercion failure SHALL take the column's absent/blank fallback: the configured default; otherwise `null` if
the column is nullable; otherwise the kind's zero value. A nullable column therefore records the value as
missing rather than as a false zero. A non-nullable column still inserts safely.

The derivers that read a number or a boolean from a present value SHALL apply the same rule and SHALL report a
failure to the mapping rather than their own fallback:
- `json-extract-uint`;
- the HTTP-success predicate, for its `response.status` read;
- the rate normalizer, for a value that is neither a boolean, an integer, text that parses as one, nor blank
  text.

A failure SHALL then be counted and fall back against the derived column, as for a mapped column. A deriver's
documented result for an **absent** value is unchanged. A failure is only a present value that cannot be read.
For example, the HTTP-success predicate still yields `false` for an absent status, and the length and
byte-count derivers still yield `0`.

Every coercion failure SHALL increment the counter `sync.values.coercion.failures`, tagged by `table`,
`column` and `kind`. It is attributed to the target table and column rather than to the ingest path, like the
inline-file counters, because every path writes through the same mapping.

The service SHALL also log a coercion failure at WARN, naming the table, the column, the kind, the JSON type
of the producer value and the blob. It SHALL write at most one such line per table and column per minute. The
first line after a quiet period SHALL carry the number of failures not logged since the previous line. The log
SHALL NOT include the value itself, since a field mapped as a number can carry arbitrary producer text.

Values are read this way for rows ingested from when the service runs it. Rows already stored keep the values
the earlier lenient reading gave them, and an operator backfill that includes already-ingested blobs applies
the strict reading to a range.

#### Scenario: Unreadable text in a nullable long column is stored as null

- **WHEN** a record's `token_usage.prompt_tokens` is the text `"abc"` and `prompt_tokens` is a nullable `long`
  column with no default
- **THEN** the row stores `null` for `prompt_tokens`, and `sync.values.coercion.failures{table=dial_usage_log,
  column=prompt_tokens, kind=long}` is incremented by one

#### Scenario: A fractional number is not truncated into a long column

- **WHEN** a record's `operation_duration_ms` is `12.7` and the column is a non-nullable `long` with no
  default
- **THEN** the row stores `0`, the kind's zero, rather than `12`, and the coercion failure is counted

#### Scenario: Numeric text is read as a number

- **WHEN** a record's `token_usage.total_tokens` is the text `" 42 "`
- **THEN** the row stores `42` and nothing is counted

#### Scenario: Blank text is absent, not a failure

- **WHEN** a record's `token_usage.total_tokens` is the text `""` and the column is nullable
- **THEN** the row stores `null` and the coercion-failure counter is not incremented

#### Scenario: A decimal price given as text keeps its precision

- **WHEN** a record's `token_usage.price` is the text `"0.000123"`
- **THEN** the row stores exactly `0.000123`, and nothing is counted

#### Scenario: An unreadable boolean is counted

- **WHEN** a `bool` column's source value is the text `"yes"`
- **THEN** the column takes its fallback and the coercion failure is counted, rather than the value being
  stored as `false` silently

#### Scenario: Structured array elements keep their content

- **WHEN** an `array<string>` column's source is `["a", {"k": 1}]`
- **THEN** the row stores the list `a`, `{"k":1}`, rather than `a` and an empty string

#### Scenario: A scalar for an array column is counted

- **WHEN** an `array<string>` column's source value is the text `"a"`
- **THEN** the column takes its fallback and the coercion failure is counted

#### Scenario: An unreadable number in a deriver is counted against the derived column

- **WHEN** the value at `usage.completion_tokens_details.reasoning_tokens` that `json-extract-uint` reads for
  `reasoning_tokens` is the text `"n/a"`
- **THEN** `reasoning_tokens` takes its fallback rather than `0` from the deriver, and
  `sync.values.coercion.failures{column=reasoning_tokens, kind=long}` is incremented

#### Scenario: An absent deriver input is not a failure

- **WHEN** a record has no `response.status`
- **THEN** `success` is `false` as before, and nothing is counted

#### Scenario: Repeated failures are logged once per minute per column

- **WHEN** one thousand records in a minute carry unreadable text in `prompt_tokens`
- **THEN** one WARN line names the table, column, kind, JSON type and blob without the value, the counter
  increases by one thousand, and the next line for that column after the minute reports how many were not
  logged
