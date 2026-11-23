## MODIFIED Requirements

### Requirement: Uniform caller identity across authentication entry points

Every authentication entry point SHALL construct the caller's authorities identically (Bearer JWT,
opaque-token introspection, DIAL API key over HTTP, and DIAL API key over the Flight surface): the
expanded privilege set **and** the caller's raw provider role names under the reserved prefix.
An entry point SHALL NOT omit the raw provider roles, because per-table grants match on them.

Every entry point SHALL likewise carry the caller values (see "Caller values derived from the
credential"), because a row policy compares rows with them.

Where two entry points share an introspection cache entry for the same credential, the cached
identity SHALL be independent of which surface populated it: the same API key SHALL yield the same
privileges, the same provider roles and the same caller values whether it is first presented over HTTP
or over Flight.

#### Scenario: An API key yields the same identity on both surfaces
- **WHEN** the same DIAL API key is presented first over the Flight surface and then over HTTP
- **THEN** both requests resolve the same privileges and the same raw provider role names, and a
  per-table grant naming one of those roles authorizes both

#### Scenario: Flight carries provider roles
- **WHEN** a caller authenticates to the Flight surface with an API key whose Core role is listed in
  a table's `read` grant list, and holds no global read privilege
- **THEN** the caller can read that table over Flight

#### Scenario: An API key yields the same caller values on both surfaces
- **WHEN** the same project API key is presented first over the Flight surface and then over HTTP, and
  its role carries the row policy `project_id = caller.project`
- **THEN** both reads return the rows of that key's project

## ADDED Requirements

### Requirement: Caller values derived from the credential

The system SHALL derive two caller values from an authenticated credential, for use in row policies
(see [table-access-control](../table-access-control/spec.md), "Caller values in row policies"). Either
MAY be absent; an absent value is unknown to a policy.

**`caller.project`** SHALL be the project name of a project API key, and absent for every other
credential.

**`caller.user_hash`** SHALL equal the value DIAL Core writes as `user.id` in its usage log for the same
user, so that it can be compared with a `user_hash` column. It SHALL be derived from three settings:

- `logging-key`: the name of the claim identifying the user;
- `logging-salt`: the salt, a credential;
- `obfuscate-user-email`: default `true`.

With obfuscation on, the value SHALL be the lowercase hexadecimal SHA-256 digest of the UTF-8 bytes of the
salt followed by the claim value. With obfuscation off, it SHALL be the claim value itself. `logging-key`
SHALL name a top-level claim, not a dotted path. For a Bearer JWT and an introspected opaque token the
claim SHALL be a string; for a per-user API key, whose claims are read as lists of strings (a lone string
counting as a list of one, non-string items ignored), it SHALL have exactly one string value. The value
SHALL be absent when `logging-key` is not set, when the claim is missing, when it has any other shape, or
when it is blank.

The settings SHALL be read per identity provider (`providers.<name>.*`) for a Bearer JWT and for an
introspected opaque token, using the provider that authenticated the credential, and from
`security.api-key.*` for a per-user API key, whose claims arrive without naming a provider. A deployment
whose providers use different salts SHALL leave the API-key settings unset, so that per-user API keys have
no user hash rather than a wrong one. A project API key SHALL have no user hash.

`logging-key` set without `logging-salt` while obfuscation is on SHALL fail startup. The salt SHALL NOT be
logged or returned by any endpoint.

#### Scenario: A JWT caller's hash matches the stored column

- **WHEN** a provider is configured with `logging-key` `email` and `logging-salt` `s1`, and a JWT of that
  provider carries `email` `user1@example.com`
- **THEN** the caller's user hash is the lowercase hex SHA-256 of `s1user1@example.com`

#### Scenario: Obfuscation off yields the raw claim

- **WHEN** the same provider sets `obfuscate-user-email` to `false`
- **THEN** the caller's user hash is `user1@example.com`

#### Scenario: A missing or non-string claim yields no hash

- **WHEN** the configured claim is absent from the token, is an array of several values, or is blank
- **THEN** the caller has no user hash

#### Scenario: A provider without the settings yields no hash

- **WHEN** a caller authenticates through a provider that sets no `logging-key`
- **THEN** the caller has no user hash, and authentication succeeds as before

#### Scenario: A per-user API key uses the API-key settings

- **WHEN** `security.api-key.logging-key` and `security.api-key.logging-salt` are set, and Core's
  introspection of a per-user key returns that claim with one string value
- **THEN** the caller's user hash is derived from that value with the API-key salt

#### Scenario: A project API key has a project and no hash

- **WHEN** a project API key of project `P1` authenticates
- **THEN** the caller's project is `P1` and it has no user hash

#### Scenario: A key without a salt fails startup

- **WHEN** a provider sets `logging-key`, leaves `logging-salt` unset and does not turn obfuscation off
- **THEN** startup fails naming the provider and the missing setting
