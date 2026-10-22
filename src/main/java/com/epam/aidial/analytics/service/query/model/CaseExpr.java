package com.epam.aidial.analytics.service.query.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A conditional: {@code { "type": "case", "when": [ { "when": <filter node>, "then": <expr> }, … ],
 * "else": <expr> }}. Branches are evaluated in order and the first whose condition holds supplies the
 * value; {@code otherwise} (wire key {@code else}) supplies it when none does and MAY be omitted, in which
 * case an unmatched row yields null.
 *
 * <p>This is the one expression position that admits the <em>filter grammar</em> as a condition: SQL
 * {@code CASE WHEN a > b} has no boolean-function spelling, so unlike {@code if} — whose condition must
 * be a boolean-valued expression — a branch condition here is a {@link FilterNode}. Branch typing
 * follows {@code if}'s rules (numeric widening, otherwise same type), enforced during translation.
 */
public record CaseExpr(
        List<CaseBranch> when,
        @JsonProperty("else") Expr otherwise) implements Expr {}
