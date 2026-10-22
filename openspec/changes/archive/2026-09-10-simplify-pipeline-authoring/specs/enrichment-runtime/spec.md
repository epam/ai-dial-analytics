## MODIFIED Requirements

### Requirement: An ENRICH pipeline's read window is clamped to its referenced enrichments' positions

Every pipeline-scoped read that advances a cursor SHALL bound its window to the position through which each
enrichment its statements join has been materialized, taking the **lowest** such position when several
enrichments are joined. This applies to the incremental scan; the Class-1 materialize window carries the
same clamp (see [class1-enrichment](../class1-enrichment/spec.md)).

The clamp exists because an enrichment row lands **after** the source row it derives from, while a
pipeline's window advances on the source's cursor. Without it a row would be read while its enrichment cell
is still absent, and because the source row does not move again, the consequence is permanent: a group
pipeline would attribute the row to the wrong group or to none and never revisit it, a `trigger.ready_when.signal`
computed inside the scan query would return a verdict derived from an absent value, and a
bound `vars` value read at assembly would be missing from the prompt. Clamping the window removes
every one of those at once, because a row that does not enter the window contributes to none of them.

Clamping the scan window SHALL be sufficient for a group pipeline's members: the assembly endpoint selects
the **whole** group by key rather than only newly seen members, so a group that is marked is assembled
from every member it has. The unrecoverable case is a source row that is never marked at all, which is
what the clamp prevents.

The clamp's set and the **join** set are therefore not the same set, and each SHALL be derived for what it
governs. A statement SHALL join only the enrichments its own clauses name, so nothing pays a full hash
read of a table it never selects from. The clamp SHALL additionally cover the columns a group pipeline's `vars`
bind, which the scan page does not project but the assembly reads for the very rows that page marks: a
bound value read while its enrichment cell is absent is a wrong prompt value, so the enrichment behind it
holds the scan window back even though no clause of the scan names it.

An enrichment that contributes no bound — one no pipeline targets — SHALL NOT hold the window back. A
`trigger.ready_when.idle` window SHALL be measured after the clamp, so a group's idle timer starts from the
activity the scan actually reported; the clamp therefore delays labelling by at most the lag of the
enrichments a pipeline reads.

A pipeline joining no enrichment SHALL be unclamped and its window unchanged.

#### Scenario: A window is held back to an enrichment's materialized position

- **WHEN** a pipeline referencing an enrichment column is scanned while source rows exist above that
  enrichment's recorded materialize position
- **THEN** those rows are excluded from the page, the returned cursor does not advance past that
  position, and a later scan returns them once the enrichment has reached them

#### Scenario: The lowest position bounds a multi-enrichment read

- **WHEN** a pipeline references columns of two enrichments materialized to different positions
- **THEN** the window is bounded by the lower of the two

#### Scenario: An enrichment with no pipeline imposes no bound

- **WHEN** a pipeline references a column of an `ACTIVE` enrichment that no pipeline targets
- **THEN** the window is not held back, because that enrichment will never gain another row

#### Scenario: A pipeline joining no enrichment is unclamped

- **WHEN** a pipeline referencing only its read source's own columns is scanned
- **THEN** the window is bounded only by the cursor, the safety-lag, and any requested bounds, exactly
  as before

#### Scenario: A clamped scan reports its lag rather than failing

- **WHEN** a pipeline's referenced enrichment has not been materialized at all, so no source row is inside
  the window
- **THEN** the scan returns an empty page and echoes the input cursor, rather than failing
