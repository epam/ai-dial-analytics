## Context

One service record models a chart, and saved queries, their revision snapshots and dashboard inline queries
all use it. A saved query's chart lives in a `jsonb` column (`saved_query.chart`, captured by its audit
table), which the entity holds as text and reads back through that record. A dashboard keeps it inside the
`widgets` `jsonb` document. Two mappers are involved:

- The strict request mapper reads request bodies and rejects unknown properties. That is why a `legend`
  sent today gets a `400 bad_request` naming the field.
- The primary mapper reads stored JSON and ignores unknown properties.

Both serialize the same way and omit null properties (`NON_NULL`).

## Goals / Non-Goals

**Goals:**

- One model change that reaches every surface carrying a chart, with no per-surface code.

**Non-Goals:**

- Validating `legend.position` against the four known values, or defaulting it. The client owns that
  mapping.
- Accepting unknown members inside `legend` (for example `"align"`). The service-wide strict-request rule
  rejects them with `400 bad_request`, as it rejects unknown chart members.
- Defining type coercion for scalar `visible` / `position` values beyond the spec's 400 cases.

## Decisions

**A typed `SavedQueryChartLegend` record, not a raw JSON pass-through.** The chart record gains a nullable
`legend` component of a new two-component record (`Boolean visible`, `String position`) in the same service
model package. A raw JSON node would also store "as sent", but it would accept any shape. It would also
bypass the strict-request rule for unknown members, leave the OpenAPI document without a schema for the
member, and break with the typed siblings (`type`, `x_field`, `y_field`). The record becomes a public
OpenAPI component, so it is named after its parent. A nested `Legend` could collide with another component.

**`position` is a `String`, not an enum.** The spec requires any string to round-trip unchanged, and the
client maps unknown values itself. An enum would reject or rewrite unknown values and couple server releases
to client vocabulary, the same reason `type` is a string. The four known values go in the record's Javadoc,
not in an `@Schema`. No class under `service/` imports swagger annotations, and the sibling chart schemas
carry no descriptions. An `allowableValues` would also publish an `enum` and tell generated clients the
value is checked.

**`visible` is a boxed `Boolean`.** Absent and `null` must stay distinguishable from `false`. The `NON_NULL`
mapper omits a null and keeps `false`, which a primitive could not express.

**No validator change.** The chart is not interpreted. The existing serialized-size cap counts the whole
chart, so `legend` is covered with no new code. Malformed shapes fail deserialization, and the existing
handler turns that into `400 bad_request`, the same path that rejects an object `y_field` today. The
malformed shapes are a non-object `legend`, an object or array member, and an unknown member. The apply
phase confirms each spec case with a test instead of assuming it.

## Risks / Trade-offs

- [A non-boolean `visible` such as `"true"` may be coerced by the mapper's default scalar coercion rather
  than rejected] → The spec leaves scalar coercion deliberately undefined. The request asks for none, and a
  client sends a JSON boolean.
- [A build without `legend` refuses a write carrying one with `400`] → The server ships before the client
  sends `legend`. A rollback while clients send it breaks chart saves until the client stops sending it.
  Reads keep working, because the stored member is ignored. The change needs no operator action, so it adds
  no upgrade-doc entry.

## Migration Plan

None. No Flyway migration, configuration key or credential. Stored charts without `legend` read back without
it.
