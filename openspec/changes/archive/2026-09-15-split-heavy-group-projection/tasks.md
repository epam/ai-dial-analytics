# Tasks

## 1. Split the projection phase by heavy column

- [x] 1.1 Add `projectionBatches` to `GroupFetchService`: one batch per heavy bound column, with every
      inexpensive bound column folded into the first heavy batch, and a single batch when the declaration
      binds no heavy column. Heaviness is the catalog flag off the model's bindings; an absent binding is
      treated as heavy (it cannot occur — `fetch` enforces entitlement over the whole declaration first —
      and one statement too many is the safe way to be wrong).
- [x] 1.2 Split `projectMembers` into the orchestrator and `readMembers`, one statement per batch, and
      merge the slices under the member lookup key. Tally the reads that matched each member rather than
      testing for a value: a heavy column is legitimately NULL.
- [x] 1.3 Name the projected columns in the budget refusal (`label`), so a refusal identifies which bound
      column does not fit.

## 2. Tests

- [x] 2.1 `GroupFetchServiceTest`: two heavy columns produce one projection statement each, neither
      naming the other's column, with the inexpensive column riding along; one heavy column still produces
      one projection. Verified to fail when the batching is collapsed back to a single read.
- [x] 2.2 `GroupEnrichmentIntegrationTest`: a member carrying two heavy payload columns comes back with
      both values merged, and a member whose second heavy column is NULL is kept rather than dropped.

## 3. Spec

- [x] 3.1 Add the projection-split requirement to `enrichment-runtime`.
