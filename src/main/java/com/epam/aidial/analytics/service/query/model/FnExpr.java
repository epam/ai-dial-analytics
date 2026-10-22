package com.epam.aidial.analytics.service.query.model;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import java.util.List;

/**
 * A function call: arbitrary arity and nesting; {@code args} are themselves expressions.
 * Aggregates are just functions ({@code sum}, {@code count}, …); {@code count} with no args is
 * {@code COUNT(*)}. {@code distinct} applies argument-level deduplication (e.g. {@code
 * COUNT(DISTINCT x)}). The function set is an allowlist enforced by the validation layer (out of
 * scope) — an unregistered name is rejected before any SQL is built.
 */
public record FnExpr(
        String name,

        // Optional flag: absent/null defaults to false rather than failing deserialization.
        @JsonSetter(nulls = Nulls.AS_EMPTY) boolean distinct,

        List<Expr> args) implements Expr {}
