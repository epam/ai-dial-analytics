## ADDED Requirements

### Requirement: The published contract states a filter's default and offers no unimplemented value first

The published OpenAPI document SHALL state every optional enum query parameter's default
machine-readably, and SHALL order each parameter's accepted values so that no unimplemented value
precedes an implemented one. A default stated only in prose or only in code SHALL NOT be treated as
published.

This exists because a spec-driven consumer never reads the prose. A generated client, an
OpenAPI-rendered filter form, and a `<select>` built from an enum with no empty option all fill an
optional parameter from its **first** accepted value or from its `example`, so the published ordering and
example are the effective default for every such caller. `view` is the case that proved it: `source` is
the default, is documented as the default in the parameter's own description, and is what an absent `view`
resolves to — but the document advertised `{"enum": ["compiled", "source"], "example": "compiled"}`, so
callers that never mentioned `view` sent `view=compiled`. Those callers were answered 200 with authored
declarations rendered under the compiled name for two days, then refused outright once that mis-answer
became a refusal. Neither the callers nor the endpoint were wrong; the published contract was.

The obligation is therefore on the **document**, not on the handler: the in-code default, the accepted
value set and the refusal codes are all already correct and SHALL NOT change. What changes is that a
consumer can derive the default without reading prose written for a human.

A parameter that genuinely has **no** default SHALL NOT publish one. An absent `kind` selects every kind
rather than a particular one, so publishing a `kind` default would narrow a listing that was asked to be
unnarrowed — a worse failure than the one this requirement removes, because it silently drops rows instead
of refusing. Such a parameter SHALL still order its values so an unimplemented kind is not offered ahead
of implemented ones.

#### Scenario: The view parameter publishes its default

- **WHEN** the OpenAPI document is read for `GET /v1/pipelines` or `GET /v1/pipelines/{name}`
- **THEN** each `view` parameter carries a schema default of `source`, and its example is `source`
- **AND** the accepted values are offered with `source` first

#### Scenario: A client that sends no view still gets the authored projection

- **WHEN** either endpoint is called with no `view` parameter
- **THEN** the response is the authored projection, unchanged by this requirement

#### Scenario: The kind filter publishes no default

- **WHEN** the OpenAPI document is read for `GET /v1/pipelines`
- **THEN** the `kind` parameter carries no default, because an absent `kind` returns every kind
- **AND** no unimplemented kind is offered ahead of an implemented one

#### Scenario: The accepted value set is unchanged

- **WHEN** a caller sends `view=compiled` explicitly
- **THEN** it is honoured or refused exactly as before, and an unrecognized `view` is still rejected with
  HTTP 400 `bad_request` naming the accepted values
