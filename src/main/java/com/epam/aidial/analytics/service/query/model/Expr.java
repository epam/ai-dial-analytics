package com.epam.aidial.analytics.service.query.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * The uniform expression grammar, used wherever a value can appear ({@code select} entries,
 * function arguments, filter/{@code having} operands, aggregate arguments). Discriminated by
 * {@code type}: nine wire kinds ({@code field}, {@code value}, {@code param}, {@code fn}, {@code array},
 * {@code case}, {@code window}, {@code lambda}, {@code lambda_param}) plus one internal kind,
 * {@link ScalarSubqueryExpr}, that the SQL front end constructs for a composite statement and that has no
 * wire spelling.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = FieldExpr.class, name = "field"),
    @JsonSubTypes.Type(value = ValueExpr.class, name = "value"),
    @JsonSubTypes.Type(value = ParamExpr.class, name = "param"),
    @JsonSubTypes.Type(value = FnExpr.class, name = "fn"),
    @JsonSubTypes.Type(value = ArrayExpr.class, name = "array"),
    @JsonSubTypes.Type(value = CaseExpr.class, name = "case"),
    @JsonSubTypes.Type(value = WindowExpr.class, name = "window"),
    @JsonSubTypes.Type(value = LambdaExpr.class, name = "lambda"),
    @JsonSubTypes.Type(value = LambdaParamExpr.class, name = "lambda_param")
})
public sealed interface Expr permits FieldExpr, ValueExpr, ParamExpr, FnExpr, ArrayExpr, CaseExpr, WindowExpr,
        LambdaExpr, LambdaParamExpr, ScalarSubqueryExpr {}
