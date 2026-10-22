## 1. Make the whole-day prefix date-bearing without a date directory

- [x] 1.1 In `service/BlobPrefixResolver.java`, extract a private `dateDirPrefix(day)` —
      `basePrefix + dateSegment(datePath, day) + "/"` normally, plain `basePrefix` when the date layout
      is blank — and re-express **both** `bandPrefix` and `checkBandShape`'s `expected` against it.
      They are written as `dayPrefix(day) + fileNameHead(day)` today, so this is **mandatory**, not
      optional: once `dayPrefix` absorbs the head it would otherwise be emitted twice. The rendered
      strings must not move in the normal mode.
- [x] 1.2 Make `dayPrefix(day)` return `dateDirPrefix(day) + fileNameHead(day)` when the date layout is
      blank — `logs/date=2025-10-22`, with **no trailing separator** — and today's
      `basePrefix + dateSegment + "/"` otherwise. Update its javadoc: the trailing-slash promise
      ("cannot match a sibling directory by name") now holds only in the normal mode, and the flat mode
      pays for it with the prefix-freedom check of task 2.3.
- [x] 1.3 Update the class javadoc: a key is `<path-prefix>/<date-path>/<file name>` **or**
      `<path-prefix>/<file-name head><timestamp>…` when no date directory is configured, and record
      why the blank mode absorbs the head rather than dropping a segment (design.md, D1).

## 2. Startup validation

- [x] 2.1 Turn `validatedDatePath` into an **instance** method and accept a blank or `null` pattern,
      returning `null` for it instead of throwing. Move the head and base-path resolution **ahead** of
      it in the constructor — the sweep now renders `dayPrefix`, which needs both — and comment that
      the ordering is load-bearing. `validatedFileNamePrefix` stays static.
- [x] 2.2 Sweep the rendered **whole-day prefix** rather than the date segment. This alone keeps
      `yyyy/MM` refused in the normal case and admits a blank layout exactly when the head pins the
      day; a blank or dateless head under a blank date layout collides with itself on day two and needs
      no rule of its own. Treat `null` and blank alike, as the head already does.
- [x] 2.3 Add the **prefix-freedom** refusal: sort the swept prefixes and refuse when an entry is a
      proper prefix of its successor, naming both days. Sorted order makes one linear pass complete
      (design.md, D3). Only reachable in blank-date mode.
- [x] 2.4 Keep both refusal messages naming the rendered **date segment** — or the rendered **head**,
      in blank mode — rather than the whole prefix, and blame the setting the operator must edit
      (`SYNC_FILE_NAME_PREFIX_PATTERN` in blank mode, not the date layout they left empty).
- [x] 2.5 Update the `validatedDatePath` javadoc: blank is now accepted and means "no date directory";
      say why the sweep runs over `dayPrefix`; and document the two distinct collapses (same prefix vs
      nested prefix) that motivate the two refusals.
- [x] 2.6 Update the `validatedFileNamePrefix` javadoc: its "deliberately no distinctness sweep and no
      blank refusal" note is now conditional — under a blank date layout the head is swept by
      `validatedDatePath`, which is where a blank head is refused.

## 3. The startup log and the property javadoc

- [x] 3.1 The startup template `"Blob discovery prefix template: {}<{}>/, …"` renders `logs/<>/` for a
      blank layout — an empty segment and a slash no key carries. Render the head-absorbed form
      instead, so the logged template and the logged example agree with a real key.
- [x] 3.2 `configuration/SyncProperties.java` — extend the `datePathPattern` javadoc: blank means "no
      date directory", in which case the whole-day prefix absorbs `fileNamePrefixPattern`, which must
      then pin the day; and extend `fileNamePrefixPattern` to say it is the only date-bearing text in
      that mode. Leave the `SyncProperties` compact constructor's trim behaviour alone — it already
      preserves a configured-but-blank value as blank, which is now meaningful for both patterns.
- [x] 3.3 `application.yml` default stays `yyyy/MM/dd` and `'date='yyyy-MM-dd`. No property is added or
      renamed, so **`docs/configuration.md` gains no row** — only the `SYNC_DATE_PATH_PATTERN`
      description line changes, since blank is now meaningful rather than refused (owned by the docs
      agent).

## 4. Tests

- [x] 4.1 Re-point `blankLayoutIsRefused` and `nullLayoutIsRefused` in
      `service/BlobPrefixResolverTest`: both pass `DEFAULT_HEAD` (`'date='yyyy-MM-dd`) through the
      two-arg overload today, and that head now pins the day, so a blank date layout there is
      **accepted**. Pass a blank head and assert the combined-distinctness message.
- [x] 4.2 Confirm `refusalNamesTheCollidingDates` still passes **unchanged** — it asserts
      `renders '01/01' for both …`, which the message must keep doing.
- [x] 4.3 Add flat-layout coverage: blank date layout + `'date='yyyy-MM-dd` head gives
      `dayPrefix` = `logs/date=2026-06-24` with no trailing separator; band prefixes carry the head
      **once**; `checkBandShape` accepts a real flat key `logs/date=2026-06-24<digits>-<uuid>.log.gz`
      and rejects one that is not; a blank date layout + blank head is refused.
- [x] 4.4 Add prefix-freedom coverage: a blank date layout with `'d='yyyy-M-d` is refused naming two
      nesting days, while `'date='yyyy-MM-dd` is accepted.
- [x] 4.5 Confirm the remaining ~40 tests stand untouched — every one configures a non-blank date
      layout, where `dateDirPrefix == dayPrefix`, so band math and `checkBandShape` must be byte-identical.
- [x] 4.6 Add an assertion that the two modes produce the *same* band prefix construction, i.e. that
      the band-shape `expected` and the band prefix share a head-once composition in both modes. This
      is the doubled-head regression (design.md, D2), which no existing test can catch.

## 5. Verification

- [x] 5.1 `./gradlew clean build` — green.
- [x] 5.2 Offline check of a flat-layout configuration: start with `SYNC_PATH_PREFIX=logs`,
      `SYNC_DATE_PATH_PATTERN=` and `SYNC_FILE_NAME_PREFIX_PATTERN='data='yyyy-MM-dd`, and confirm the
      startup line prints `logs/data=<today>` and a band example `logs/data=<today><band digits>`,
      matching the live key shape.
      *Verified: `Blob discovery prefix template: logs/<'data='yyyy-MM-dd>, e.g. logs/data=2026-09-17;
      band prefix example: logs/data=2026-09-171789642` — head-absorbed, no empty segment, no stray slash.*
- [x] 5.3 Confirm no caller changed: `DataSyncService`, `DataBackfillService` and `BlobPollingService`
      pass `dayPrefix` to `blobClient.list`, which lists by prefix with `recursive()` and no delimiter,
      so the absent trailing separator is load-bearing nowhere. *Verified: `DataSyncService:142` and
      `BlobPollingService:235,239,242` are the only call sites, and `DataBackfillService` goes through
      `DataSyncService`.*
- [ ] 5.4 On a flat-layout deployment after deploy, watch `sync.blobs.band.shape.mismatch` — a doubled head or an over-wide
      whole-day prefix both surface there rather than as missing rows.

## 6. The band-shape check's weaker guarantee in the flat mode

- [x] 6.1 `checkBandShape`'s `expected` equals the whole-day prefix once the head is absorbed, so a key
      the listing returned can only fail the trailing-digit test. Say so in the javadoc rather than
      leaving the old "the whole-day paths hold the keys already" claim to read as unchanged.
- [x] 6.2 Make the warning's remedy conditional: "or set `POLL_LISTING_MODE=day` — whole-day ingestion
      is unaffected" is **false** with no date directory, where every discovery path lists under the
      same head. Point at the startup line instead, which renders the prefix an operator can hold
      against a real key.
- [x] 6.3 MODIFY *A file name bounded listing cannot match is reported* in the delta spec, restating
      its four scenarios and adding the mode-dependent remedy.
