# Design

## D1. What the row cap is actually for

Row-limit governance exists to prevent a client **silently receiving a truncated answer**. That is the
whole guarantee: never hand back the first N rows of a larger result as though they were the result.

A `LIMIT` far above the cap carries no expectation that the cap protects. It is how BI tools express
"stop me before I hurt myself" — Power BI writes `1000001`, one million plus one, precisely so it can
tell overflow from a complete answer. Reading that as "the client wants a million rows, refuse it"
protects nothing: the result it guards is 78 rows.

So the cap is restated as a **ceiling on rows returned**, not a constraint on the literal a client may
write. Under that reading the two cases collapse into one: a statement whose stated bound ADAS cannot
honour is in the same position as a statement that stated no bound at all — ADAS must impose its own
ceiling and prove the result fits under it. Both therefore get the same treatment, and the truncation
probe that already existed is what makes it safe.

The predicate driving the behaviour changes from *"did the client state a limit?"* to *"did the client
state a limit ADAS can honour?"*. Everything downstream is unchanged.

## D2. Why not simply raise `flight.max-rows`

Rejected. Setting the cap above `1000001` disables it in practice — at that point no realistic result
trips the truncation check, so ADAS is silently back to "stream whatever ClickHouse returns" with the
memory and latency profile that implies, and the guarantee in D1 is gone rather than preserved.

It also does not generalise. `1000001` is *Power BI's* guard; Tableau and Excel use their own values.
Chasing them with configuration means re-tuning the cap per client and getting it wrong for the next
one. Clamping works for any guard, whatever number the client picked, which is the point.

## D3. Failure moves from getFlightInfo to doGet, and that is not new

Rejecting an over-cap `LIMIT` was cheap because it needed only the statement — it could fail before
ClickHouse was contacted. Clamping cannot: whether the result actually exceeds the ceiling is a fact
about the data, discoverable only after execution.

This is not a new property of the endpoint. The unbounded path already works this way, and the spec
already records the consequence — a query's success depends on the data as well as the statement, and
a result that fits today may exceed it tomorrow. An over-cap `LIMIT` simply joins that set. What must
not change, and does not, is that a failure at `doGet` streams nothing at all rather than a partial
page.

The `doGet` re-check matters for a second reason that survives this change: the ticket carries
client-supplied SQL, so the clamp must be applied on the ticket path too, not only where the schema was
advertised. Since both paths now run the same predicate and neither rejects, a hand-forged ticket
cannot reach execution with an unbounded request.

## D4. Scope held to the Flight transport

REST is untouched. `POST /v1/queries/execute-sql` and `POST /v1/queries/execute` keep the shared
default of 100 and maximum of 1000, and keep rejecting `LIMIT 50000` with
`page limit 50000 exceeds the maximum of 1000 rows`.

The asymmetry is deliberate and already established: the shared governance bounds a materialized JSON
document, while Flight streams Arrow batches under its own per-request memory limit and concurrency
gate, which is why it has a separate — and higher — ceiling in the first place. The argument in D1 is
about a *streaming* transport whose client writes a guard rail; it does not transfer to a REST caller
that hand-writes a page limit, where an explicit oversized `limit` is far more likely to be a mistake
worth naming than a self-protection idiom.
