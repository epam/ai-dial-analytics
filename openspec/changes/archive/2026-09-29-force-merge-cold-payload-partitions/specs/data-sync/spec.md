## ADDED Requirements

### Requirement: Settled ingest day partitions are force-merged so their duplicates collapse

`dial_usage_log_payload` and `dial_usage_log` SHALL each carry `min_age_to_force_merge_seconds = 172800`
(2 days) together with `min_age_to_force_merge_on_partition_only = 1`. Once every active part of a day
partition is older than that interval, ClickHouse SHALL merge the partition into a single part, and because
both tables are `ReplacingMergeTree` tables that merge SHALL leave exactly one row per key — for
`dial_usage_log_payload` the row for each `event_id` with the greatest `_updated_at`, and for `dial_usage_log`
the row for each full sort key with the greatest `_ingested_at`. A partition with any part younger than the
interval SHALL NOT be force-merged, and the settings SHALL NOT alter which merges the automatic selector
already performs on it.

The settings exist because the platform writes each day's rows twice — the poller into the current day, the
daily reconciliation run into the previous day — and a settled day is otherwise never merged into one part:
the automatic selector stops picking a partition once it is down to a few similar-sized parts, so the
rewritten rows stay as stale versions until the partition expires. `min_age_to_force_merge_on_partition_only`
is what makes the result one part: without it a partition holding a newer part could be merged piecemeal and
still be left with several.

`dial_usage_log_payload` SHALL additionally carry `vertical_merge_algorithm_min_columns_to_activate = 1` and
`vertical_merge_algorithm_min_rows_to_activate = 1`, so every merge of it that writes a wide part, forced or
chosen by the automatic selector, runs with the vertical algorithm. ClickHouse merges vertically only into a
wide part (one above `min_bytes_for_wide_part`, 10 MiB by default), which on these rows is any merge of more
than a few dozen of them; a merge of tiny parts into a compact part stays horizontal and is cheap either
way. Its rows are heavy: on the order of 100 KiB of bodies per row,
with single bodies of several MiB, plus two JSON documents with dozens of paths each.
ClickHouse picks the algorithm by column count, not width, and with 8 columns outside the sort key and version
against the default threshold of 11 every merge would otherwise be horizontal, reading every column of every
source part at once. The vertical algorithm merges the key columns first and then streams the other columns one
at a time, so a merge's memory follows its widest column. Measured on 26.3 against a synthetic partition of
realistic shape (8 source parts, realistic row widths), the whole-partition merge peaked at roughly a third of
the horizontal algorithm's memory, at both sizes tried, and ran faster either
way; the extra pass reads only the key columns, a UUID and a timestamp. `dial_usage_log` SHALL NOT carry
them: its rows are light and it already reaches the default column threshold.

The interval SHALL exceed the gaps between writes that the routine writers produce into one partition — the
poller during the day, the reconciliation run early on the day after, and a rare straggler a day later — so a
partition is not collapsed and then reopened by them. Each write restarts the partition's age. A write into a
collapsed partition, for example an operator-triggered backfill of an older day or a reconciliation run
delayed by more than the interval, is not an error; it restarts the interval, and the partition is merged
again once it has settled.

The settings SHALL be provisioned by a ClickHouse migration, in the tables' own settings so that Flyway and an
operator applying the migrations out of band provision them identically; the migration has no dependency on
the build, so an out-of-band operator MAY apply it before or after. They SHALL NOT be configurable through the
service: neither an environment property nor a `/v1/tables` field sets them, the catalog does not mirror
them, and an operator changes or removes them with `ALTER TABLE … MODIFY SETTING …` or `RESET SETTING …`.
Applying them SHALL be metadata-only — no mutation is scheduled and no part is rewritten by the migration
itself — and re-applying the migration SHALL succeed and change nothing.

Applying the settings requires the `ALTER SETTINGS` privilege on each table. `dial_usage_log` already needed
it for its retention setting; `dial_usage_log_payload` needs it newly, so a writer credential granted `ALTER`
sub-privileges individually fails startup at this migration until it is granted — by design, a migration
failure aborts startup rather than serving against a half-provisioned table. A wholesale `ALTER` grant is
unaffected.

The settings SHALL take effect on parts that already exist. On the deployment that first applies the
migration, every existing partition whose parts are all older than the interval becomes a merge candidate at
once. Such merges run on the shared background pool, need free disk of about twice the partition's size while
they write the new part, and are bounded by the server's merge memory share; a merge that exceeds the memory
share fails and is retried. `dial_usage_log`'s partitions are small, so the cost is concentrated in
`dial_usage_log_payload`, whose partitions reach tens of GiB. A deployment short of disk or memory MAY stage
the rollout by applying a longer interval by hand, with migrations disabled, and lowering it step by step to
the shipped value.

Deduplicating on disk SHALL NOT change any read result. Every read continues to deduplicate at query time
with `final = 1`, so a query returns one row per key whether or not the partition has been merged. Row expiry
is unaffected: each table's `TTL` and `ttl_only_drop_parts = 1` are unchanged, and a force-merged partition is
dropped as a whole exactly as before.

#### Scenario: A settled multi-part payload partition collapses to one part

- **WHEN** a day partition of `dial_usage_log_payload` holds several parts containing several versions of the
  same `event_id`, and every part is older than 2 days
- **THEN** the partition is merged into one part holding one row per `event_id`, the row with the greatest
  `_updated_at`

#### Scenario: A settled multi-part usage-log partition collapses to one part

- **WHEN** a day partition of `dial_usage_log` holds several parts containing several versions of the same
  sort key, and every part is older than 2 days
- **THEN** the partition is merged into one part holding one row per sort key, the row with the greatest
  `_ingested_at`

#### Scenario: A partition with a recent part is left to the automatic selector

- **WHEN** a day partition holds parts older than 2 days and one part younger than 2 days
- **THEN** the force-merge does not select the partition, and the partition keeps its parts until either the
  automatic selector merges them or the youngest part reaches 2 days

#### Scenario: A late write into a collapsed partition restarts the interval

- **WHEN** a backfill inserts rows into a day partition that was already collapsed to one part
- **THEN** the insert succeeds, the partition now holds the old part and the new one, and it is force-merged
  again only after both are older than 2 days

#### Scenario: The migration provisions the settings on both tables without rewriting data

- **WHEN** the migration is applied to `dial_usage_log_payload` and `dial_usage_log` holding data
- **THEN** each table's declaration lists `min_age_to_force_merge_seconds = 172800` and
  `min_age_to_force_merge_on_partition_only = 1` among its settings, `dial_usage_log_payload`'s also lists
  `vertical_merge_algorithm_min_columns_to_activate = 1` and `vertical_merge_algorithm_min_rows_to_activate = 1`
  while `dial_usage_log`'s lists neither, no part is rewritten by the statements themselves, and each table's
  sort key, partitioning, `TTL` and `ttl_only_drop_parts = 1` are unchanged

#### Scenario: A payload merge runs with the vertical algorithm

- **WHEN** a day partition of `dial_usage_log_payload` is merged into a wide part, by the force merge or the
  automatic selector
- **THEN** `system.part_log` records the merge with `merge_algorithm = 'Vertical'`

#### Scenario: Re-applying the migration is harmless

- **WHEN** the migration statements are run again against tables that already carry the settings
- **THEN** they succeed and the settings are unchanged

#### Scenario: Removing the settings restores the previous behaviour

- **WHEN** an operator runs `ALTER TABLE <table> RESET SETTING min_age_to_force_merge_seconds,
  min_age_to_force_merge_on_partition_only` on `dial_usage_log_payload` or on `dial_usage_log`
- **THEN** the statement succeeds, that table's declaration no longer lists either setting, and its merges
  return to the automatic selector alone

#### Scenario: A read is identical before and after the collapse

- **WHEN** the same query naming a column of either table is run before and after a partition is force-merged
- **THEN** both return one row per key with the same values

#### Scenario: A writer without the settings grant fails startup at the migration

- **WHEN** migrations are enabled and the writer credential lacks the privilege to alter the settings of one of
  the two tables
- **THEN** startup fails at this migration rather than serving against a table that lacks the settings
