package com.epam.aidial.analytics.service.query.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A window function call: {@code { "type": "window", "fn": <fn expr>, "partition_by": [<expr>, …],
 * "order_by": [<window order>, …], "frame": <window frame> }}. {@code fn} is either a zero-argument
 * call to a window-only ranking function ({@code row_number}, {@code rank}, {@code dense_rank}) or a call
 * to one of the aggregates {@code count}, {@code sum}, {@code avg}, {@code min}, {@code max}.
 * {@code partitionBy}, {@code orderBy} and {@code frame} MAY each be absent; an absent frame leaves the
 * engine's standard default frame in force. Where a window expression may appear, and what its operands
 * may reference in aggregate mode, is enforced during translation.
 */
public record WindowExpr(
        FnExpr fn,
        @JsonProperty("partition_by") List<Expr> partitionBy,
        @JsonProperty("order_by") List<WindowOrder> orderBy,
        WindowFrame frame) implements Expr {}
