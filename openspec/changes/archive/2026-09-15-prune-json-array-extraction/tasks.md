## 1. The rendering

- [x] 1.1 Verify against a real ClickHouse server, before writing any code, that re-serializing the addressed
      subcolumn returns what the whole-document form returns — across an array of strings, of numbers, of
      mixed types, of objects and of nulls, a non-array member, a nested document, an explicit null, an
      absent path, a multi-segment path, and an unmatched `LEFT JOIN` under `join_use_nulls=0` — and that
      the `toString` spelling does not.
- [x] 1.2 Add `ExprTranslator.subcolumnArray`, rendering
      `JSONExtract(assumeNotNull(ifNull(toJSONString(<sub>), '')), 'Array(String)')`, and branch to it in
      `subcolumnExtract` **above** the shared `toString` field, with a comment recording that routing it
      through that field is the silently-wrong rendering.
- [x] 1.3 Keep both null wrappers and record why each is there: `ifNull` supplies the value for an absent
      path; `assumeNotNull` pins the result type rather than relying on `Nullable` inference. The
      un-wrapped form fails on both 26.3.21 and 26.3.24; no version difference was observed between them.

## 2. The classification

- [x] 2.1 Move `json_extract_array` from `JsonExtractFunctions.SERIALIZED_TEXT` to `PATH_ADDRESSABLE`, so
      the renderer's fallback predicate, the pruning collector and the registration advisory all move
      together rather than one at a time.
- [x] 2.2 Drop it from `PipelineJsonReadNotes` (it is no longer collected, so the no-alternative branch of
      the note goes with it) and from the `notes` schema description on `PipelineDto`.
- [x] 2.3 Update `ObjectPathUsage`'s statement of which references force the whole column.
- [x] 2.4 Leave `json_extract_string_ci` exactly as it is, on both surfaces.

## 3. Tests

- [x] 3.1 Render tests: the object form emits the re-serialized subcolumn and **not**
      `JSONExtract(toString(`, a multi-segment path addresses a deeper subcolumn, and the text-column
      rendering is unchanged.
- [x] 3.2 `EnrichmentJoinsPruningTest`: a `json_extract_array` over an `object` column prunes to its
      aliased subcolumn and no longer costs a sibling single-path read its pruning — the shape that
      blocked `usage-client-identity` — plus the variadic case.
- [x] 3.3 Integration: values pinned against a real ClickHouse, with an array of strings beside the
      all-integer array so an integer-only fixture cannot pass in place of the real assertion.
- [x] 3.4 `PipelineJsonReadNotesTest`: the array read is no longer reported; `json_extract_string_ci`
      still is.

## 4. Specs

- [x] 4.1 `structured-query`: the serialized-text requirement now names `json_extract_string_ci` alone;
      the keyed-join fallback list drops `json_extract_array`; the paths x parts cost requirement narrows
      what registration reports.
- [x] 4.2 `./gradlew clean build` green.
