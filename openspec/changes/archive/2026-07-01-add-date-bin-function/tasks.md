## 1. Translator

- [x] 1.1 In `service/query/translate/ExprTranslator.java`, add `case "date_bin" -> dateBin(name, args, bindings);` to the `toFunction` switch.
- [x] 1.2 Add a private unit allowlist (`Map<String,String>` `DATE_BIN_UNITS`) mapping {`second`,`minute`,`hour`,`day`,`week`} → ClickHouse `INTERVAL` keyword (`SECOND`/`MINUTE`/`HOUR`/`DAY`/`WEEK`), case-insensitive.
- [x] 1.3 Add a private `dateBin(fn, args, bindings)` method (mirroring `widthBucket`): require exactly 3 args; parse `amount` via `valueExprToObjectMapper` and require a positive integer; read the `unit` string literal and validate against the allowlist; resolve the timestamp operand via `toField(args.get(2), bindings)`; render `DSL.field("toStartOfInterval({0}, INTERVAL " + amount + " " + keyword + ")", Object.class, operand)`. Reject invalid input with `QueryValidationException`.

## 2. Spec

- [x] 2.1 Authored the `structured-query` delta spec (change `specs/structured-query/spec.md`): `date_bin` catalog row, prose paragraph, and the three new scenarios (buckets a timestamp; invalid unit rejected; wrong arity / non-positive amount rejected). Merges into `openspec/specs/structured-query/spec.md` at archive.

## 3. Tests

- [x] 3.1 `QueryTranslatorValidationTest`: cases mirroring `widthBucketRequiresFourArguments` — `date_bin` requires 3 args; invalid `unit` rejected; non-integer and non-positive `amount` rejected.
- [x] 3.2 `DialUsageLogQueryRenderTest`: assert the rendered SQL contains `toStartOfInterval(... INTERVAL 5 MINUTE)` for a `date_bin(5,'minute',request_time)` select (deterministic native render).
- [x] 3.3 `StructuredQueryProvisionalIntegrationTest`: live-ClickHouse case — insert rows at 12:01/12:03/12:08, aggregate `count()` grouped by `date_bin(5,'minute',request_time) AS bucket`, assert 12:01+12:03 collapse into one bucket (n=2) and 12:08 into another (n=1), proving :00 alignment.

## 4. Verify

- [x] 4.1 `./gradlew clean build` passes (compile + checkstyle + tests).
