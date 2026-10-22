## ADDED Requirements

### Requirement: Reported cached prompt tokens are persisted

The service SHALL persist a producer-reported cached prompt token count (nested under `token_usage`
as the provider's prompt-tokens-details `cached_tokens`) to the `cached_prompt_tokens` column, and
SHALL NOT store NULL for a record that reports a non-null count. When the producer does not report the
count, the column SHALL be NULL per the nullable-column absent/blank fallback.

This is a behavioral guarantee about the ingested value; the specific JSON source path remains an
implementation detail of the (overridable) default column mapping and is not pinned here.

#### Scenario: Reported cached prompt token count is stored

- **WHEN** a record's `token_usage` reports a cached prompt token count of N (including 0)
- **THEN** the ingested row's `cached_prompt_tokens` equals N

#### Scenario: Absent cached prompt token count is null

- **WHEN** a record's `token_usage` does not report a cached prompt token count
- **THEN** the ingested row's `cached_prompt_tokens` is NULL
