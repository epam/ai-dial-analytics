## 1. Strict readers

- [x] 1.1 Add the package-private `sync/mapping/Coercion` with `toLong`, `toDecimal`, `toBool` and
  `toStringArray`. Each returns the value, `null` for blank text, or the singleton `Coercion.FAILED`, per the
  rules in `design.md` decision 2.
- [x] 1.2 Unit tests for every row of the spec's kind table and the probe cases:
  - `long`: `123`, `"123"`, `" 42 "`, `1e3`, `"1e3"`, `12.7`, `"12.7"`, `"abc"`, `true`, an object, an
    overflowing number, `""`;
  - `bool`: `true`, `"TRUE"`, `0`, `1`, `2`, `"yes"`;
  - `double`: `"0.000123"` kept exactly, `true`, an object;
  - `array<string>`: an object element read as its JSON text, a scalar input.

## 2. Mapping engine

- [x] 2.1 `MappingEngine.coerce` delegates to `Coercion`. `resolve` treats `Coercion.FAILED` from coercion or
  from a deriver as a failure: count it, log it, and pass `null` to the transform and `applyFallback`.
- [x] 2.2 Give every `CompiledColumn` its `sync.values.coercion.failures{table,column,kind}` counter and a
  `CoercionFailures`. The limiter admits one WARN per minute and carries the suppressed count; the line has
  the table, column, kind, JSON node type and blob key, and never the value. The three-argument constructor
  uses a private `SimpleMeterRegistry`.
- [x] 2.3 Unit tests:
  - a nullable `long` column with `"abc"` stores `null` and counts one;
  - a non-nullable `long` with `12.7` stores `0` and counts;
  - blank text stores the fallback and counts nothing;
  - a default wins over a failure;
  - the limiter admits one line per window and reports the suppressed count, using an injectable nano clock.

## 3. Derivers

- [x] 3.1 `json-extract-uint` and the `response.status` read of `success-from-status` use `Coercion.toLong`
  and return `Coercion.FAILED` for a present, unreadable value. `success-from-status` still returns `false`
  for an absent status. The rate normalizer's text branch returns `Coercion.FAILED` for unparseable text, and
  for a fractional number.
- [x] 3.2 Tests:
  - `reasoning_tokens` with `"n/a"` falls back and is counted under `reasoning_tokens`;
  - an absent `response.status` gives `success=false` and counts nothing;
  - the rate normalizer counts `"maybe"`;
  - existing `DeriverRegistry` and `MappingEngine` tests still pass, or are updated where they pinned the
    lenient result.

## 4. Documentation and specs

- [x] 4.1 `docs/column-mapping.md`, "Extraction kinds": say what each kind accepts, that blank text is absent,
  and that an unreadable value falls back and is counted on `sync.values.coercion.failures`. Keep it to one
  line per kind.
- [x] 4.2 Confirm the delta spec matches the shipped behavior and run `openspec validate observable-value-coercion --strict`.
- [x] 4.3 Run the public-repo hygiene sweep from `CLAUDE.md` over the changed files.

## 5. Verification

- [x] 5.1 `./gradlew clean build` passes.
