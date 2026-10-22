## ADDED Requirements

### Requirement: Every pipeline response carries the runner contract version

Every response under `/v1/pipelines`, of any method and any status, SHALL carry the
`Adas-Contract-Version` header naming the contract this build serves. It SHALL be set unconditionally —
on a read, a write, a refusal and an error alike — and SHALL NOT depend on the caller having asked for it.

Because "absent" is the signal, the header SHALL be set at a point every response under the prefix passes
through: **before authentication and before handler resolution**, so that a `401` or a URL-level `403`
written by the security filter chain, and a `404` for a path under the prefix that resolves to no handler,
carry it as well as a response a controller produced. Setting it only where a handler runs would make the
service's most ordinary failure — a wrong or expired API key — indistinguishable from a build that
predates the contract, which is the one distinction this header exists to make.

The header is what lets a client distinguish a current ADAS from one that predates the contract entirely:
an unknown request header is silently ignored by any Spring service, and an unknown *response* header is
the only signal a client can test for that an older build cannot forge. Its absence therefore means "this
ADAS is older than the contract", which is precisely the case a consumer must refuse rather than
interpret. Setting it on the control plane as well as the data plane matters because the feed is the first
call a runner makes after a deploy: it is where the skew can be detected before any work is scheduled,
scanned or paid for.

Requiring the header on a request is a separate matter, specified per endpoint: the control-plane reads
SHALL NOT require it, so an operator, the OpenAPI console and a management client keep reading the registry
unchanged.

#### Scenario: The contract version is present on an ordinary read

- **WHEN** `GET /v1/pipelines` or `GET /v1/pipelines/{name}` is called with no contract header
- **THEN** the response is served normally and carries the `Adas-Contract-Version` header

#### Scenario: The contract version is present on a refusal

- **WHEN** a request under `/v1/pipelines` is answered with an error, including a contract refusal
- **THEN** that response also carries the `Adas-Contract-Version` header, so a client can tell "refused by
  a current build" from "answered by a build that has no contract"

#### Scenario: An unauthenticated call still carries the contract version

- **WHEN** a request under `/v1/pipelines` is rejected by the security filter chain with `401` or a
  URL-level `403`, before any handler runs
- **THEN** that response carries the `Adas-Contract-Version` header, so a caller reads it as an
  authentication failure from a current build rather than as a version skew

### Requirement: A requested projection is served as requested or refused

A read that asks for the compiled projection SHALL either return a resolved response or be refused; it
SHALL NOT answer with the unresolved object under the compiled name. Because resolution is performed only
where the registry resolves — for the `enrich` kind — the two combinations that cannot be served SHALL be
refused:

- `GET /v1/pipelines?view=compiled` **without** `kind=enrich` SHALL be rejected with HTTP 400
  `bad_request`, naming the combination and that `kind=enrich` is required to compile a listing. Resolving
  a cross-kind listing would cost a per-pipeline lookup for entries that have nothing to resolve, which is
  why the filter is required rather than inferred.
- `GET /v1/pipelines/{name}?view=compiled` for a pipeline whose kind is not `enrich` SHALL be rejected with
  HTTP 422 `pipeline_validation_failed`, naming the pipeline's kind — the same shape as every other
  endpoint that serves one kind.

This extends the existing rule that an unrecognized `view` value is refused rather than defaulted, to a
*recognized* value that this read cannot honour. The reason is the same and the failure is worse: a
silently unresolved response is not an obviously wrong shape but a plausible one, carrying no inlined
evaluator, no `grain_key`, no `version_column` and no `outputs` — indistinguishable by a consumer from an
`enrich` pipeline that genuinely has none, and the precise shape that a consumer acting on it turns into a
silent data loss. A read path that quietly disagrees with the read path beside it is the defect this rule
exists to remove, not an optimization to preserve.

#### Scenario: A compiled listing without the enrich filter is refused

- **WHEN** `GET /v1/pipelines?view=compiled` is called without `kind=enrich`
- **THEN** the response is HTTP 400 `bad_request` naming the combination, rather than a 200 carrying
  unresolved entries

#### Scenario: A compiled read of a non-enrich pipeline is refused

- **WHEN** `GET /v1/pipelines/{name}?view=compiled` names an `aggregate` pipeline
- **THEN** the response is HTTP 422 `pipeline_validation_failed` naming the pipeline's kind

#### Scenario: The authored projection is unaffected

- **WHEN** the same reads are made with `view=source` or with no `view` parameter
- **THEN** they are served exactly as before, for every kind
