## MODIFIED Requirements

### Requirement: Security mode selection

The service SHALL support a configurable security mode via the `security.mode` property with two
values: `oidc` and `none`. The default SHALL be `none`. When the mode is `none`, all endpoints are
accessible without authentication and method-level authorization is not enforced. When the mode is
`oidc`, the service SHALL enforce Bearer-JWT authentication and privilege-based authorization as
specified below.

The mode SHALL be **resolved once**, from a single component, and every authorization decision —
the HTTP filter chain, method-level checks, the Flight authenticator, API-key authentication, the
per-table grant evaluator, the sensitive-column filter, the row-policy filter, saved-query ownership
and rule authorization — SHALL consult that one resolution. No component SHALL re-derive the mode from
the raw property.

A `security.mode` value that is neither `oidc` nor `none` SHALL **fail startup** with an error
naming the offending value. Matching SHALL be case-insensitive, so `OIDC` selects `oidc` rather
than leaving a partially-secured service in which one surface enforces authentication and another
does not.

#### Scenario: Default mode is none
- **WHEN** the service starts with no `security.mode` configured
- **THEN** the mode is `none` and all `/v1/**` endpoints respond without requiring a token

#### Scenario: OIDC mode requires configuration
- **WHEN** the service starts with `security.mode=oidc` and no valid identity provider configured
- **THEN** startup fails fast with an error indicating no identity providers are configured

#### Scenario: An unknown mode fails startup
- **WHEN** the service starts with `security.mode=disabled`
- **THEN** startup fails with an error naming the value, and the service does not begin serving

#### Scenario: Mode matching is case-insensitive
- **WHEN** the service starts with `security.mode=OIDC`
- **THEN** the service enforces `oidc` on every surface, including the Flight port, API-key
  authentication and row-policy enforcement
