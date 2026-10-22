## ADDED Requirements

### Requirement: Materialize requires the same runner contract version as the rest of the data plane

`POST /v1/pipelines/{name}/materialize` SHALL carry the `Adas-Contract-Version` header and SHALL be
refused on a missing or mismatched value exactly as the scan, group-assembly and write-back endpoints are
(HTTP 409 `runner_contract_required` / `runner_contract_mismatch`, decided before any pipeline lookup,
authorization or query — see the `enrichment-runtime` capability for the normative rule).

The Class-1 lane SHALL NOT be exempted on the grounds that it carries no row payload. It is the lane whose
refusal is *most* consequential to the rest of the service, because the materialized position it advances
is what every dependent rollup's freshness clamp reads: a skewed caller that reached this endpoint would
either advance that position over a window it did not derive, or — being refused for the wrong reason,
later — leave the clamp frozen without anything naming the cause. A refusal at admission makes the
position simply stop moving, which is recoverable within one cadence of the matching runner landing, and
pairs with the runner-contact metric that names the pipeline holding the clamp.

#### Scenario: A materialize without the contract header advances no position

- **WHEN** `POST /v1/pipelines/{name}/materialize` is called with no `Adas-Contract-Version` header
- **THEN** the response is HTTP 409 `runner_contract_required`, no window is probed or written, and the
  pipeline's materialized position is unchanged

#### Scenario: A mismatched caller cannot advance the materialized position

- **WHEN** the endpoint is called with a contract version that differs from the version served
- **THEN** the response is HTTP 409 `runner_contract_mismatch` and the materialized position is unchanged,
  so a dependent rollup's clamp holds at the last position actually derived
