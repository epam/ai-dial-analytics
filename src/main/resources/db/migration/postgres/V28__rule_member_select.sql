-- A group rule's MEMBER SELECTION: which of a group's members bear on its label.
--
-- Until now `POST /v1/rules/{id}/group-rows` hardcoded one policy — the newest N members by the source's
-- total order — and applied every cap AFTER the read: the row cap as a LIMIT over a query that already
-- projected the rule's bound body columns, the byte cap in application code over rows already in hand.
-- The response was bounded; the read was not. Assembling one 300-hop conversation carrying 67 MB of
-- request/response bodies out of dial_usage_log exhausted an 8.10 GiB ClickHouse memory budget outright,
-- and no rule setting could avoid it (a filter_sql byte ceiling is a WHERE, evaluated after the bodies
-- are read; a smaller limit is applied after the sort).
--
-- member_select is {prefer_sql?, order_by?, limit} and does two things at once. It lets the assembly
-- select members from cheap columns first and read bodies only for the survivors, and it replaces the
-- "newest N" policy, which is wrong for the data this exists to serve: a DIAL conversation's newest hop
-- is frequently a two-message internal sub-agent call, while the body carrying the accumulated history is
-- the chain entry (parent_deployment IS NULL) or else the hop with the most request messages.
--
-- NULL means "the endpoint's default policy", which is exactly what every existing rule means, so this
-- migration deliberately does NOT backfill: an existing rule keeps today's semantics with no change in
-- behaviour, and only a rule that declares a limit gets a read proportional to it.
ALTER TABLE rule
    ADD COLUMN member_select JSONB;

-- Only a group rule has members to select among. The service layer rejects the same declaration with 422
-- rule_validation_failed and a message naming the trigger kind; this constraint is the database backstop,
-- one-directional because member_select is optional for a group rule while group_by is mandatory (hence
-- CHECK ... OR rather than the equality rule_group_by_iff_group uses).
ALTER TABLE rule
    ADD CONSTRAINT rule_member_select_only_group
        CHECK (member_select IS NULL OR trigger_kind = 'GROUP');
