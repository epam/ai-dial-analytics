package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.jooq.Context;
import org.jooq.Field;
import org.jooq.impl.CustomField;
import org.jooq.impl.DSL;
import org.jooq.impl.QOM;
import org.jooq.impl.SQLDataType;

import java.util.List;

/**
 * One service-defined <b>computed field</b> of an enrichment: a field the query model exposes and discovery lists, but
 * which no catalog column backs and no statement computes. Its value is completed by ADAS after the statement that
 * reads it has run (see {@code openspec/specs/data-sync/spec.md}, "A rebuilt request body is readable beside the
 * stored one", and {@code ComputedFieldCompleter}). The closed set lives in {@link ComputedFields}.
 *
 * <p>In a statement the field is a {@link StandIn}: a {@code tuple(...)} over the physical columns the completion
 * needs, projected under the field's output name like any other binding. One cell per field and no helper columns, so
 * every projection site that already renders {@code binding.field()} carries what the completer needs without knowing
 * the field is special, and the output names, order and count stay those the caller asked for.
 *
 * @param name        the bare name, published qualified by its enrichment ({@code "<enrichment>.<name>"})
 * @param kind        which value the completer writes
 * @param type        the type discovery reports
 * @param enumValues  the declared domain of an {@code enum} field, else {@code null}
 * @param displayName the fixed display name
 * @param description the fixed description
 */
public record ComputedField(String name, Kind kind, QueryFieldType type, List<String> enumValues, String displayName,
        String description) {

    /** The two values a request-body rebuild completes. */
    public enum Kind {
        /** The rebuilt request body. */
        BODY,
        /** What the rebuilt body holds: {@code stored}, {@code rebuilt} or {@code partial}. */
        STATUS
    }

    /** The physical columns the body stand-in reads. */
    static final List<String> BODY_READS = List.of(
            "request_body", "request_parent_hash", "request_ancestor_hashes", "request_cache_control", "request_time");

    /**
     * The physical columns the status stand-in reads: the two text columns only through their null maps, and the grain
     * key, which tells an unmatched join row (whose null-map subcolumns read their type default, {@code 0}) apart.
     */
    static final List<String> STATUS_READS = List.of(
            "event_id", "request_body.null", "request_parent_hash", "request_ancestor_hashes", "request_cache_control.null",
            "request_time");

    /** The stand-in for this field over {@code table}'s columns. */
    public Field<Object> standIn(String table) {
        return new StandIn(this, table);
    }

    /** The physical columns of {@code table} the stand-in references, which a derived table must project for it. */
    public List<Field<?>> reads(String table) {
        return (kind == Kind.STATUS ? STATUS_READS : BODY_READS).stream().<Field<?>>map(column -> DSL.field(DSL.name(table, column))).toList();
    }

    /**
     * The stand-in projected at {@code projected}, alias unwrapped, or {@code null} when it projects anything else. The
     * one recognizer the executor and the composite guard use, so a stand-in is found however it was aliased.
     */
    public static StandIn standInOf(Field<?> projected) {
        final Field<?> field = projected instanceof QOM.FieldAlias<?> alias ? alias.$field() : projected;
        return field instanceof StandIn standIn ? standIn : null;
    }

    /**
     * The statement-side form of a computed field. For {@link Kind#BODY}:
     * <pre>tuple(request_body, toString(request_parent_hash), arrayMap(x -&gt; toString(x), request_ancestor_hashes),
     *       request_cache_control, toYYYYMMDD(request_time))</pre>
     * and for {@link Kind#STATUS} the same with two flags in place of the two text columns — whether there is no body
     * (an unmatched join row, or a {@code NULL} body) and whether the row carries markers — read from the columns' null
     * maps, so a status-only read names no body. The {@code .null} subcolumn rather than {@code isNull(...)}: measured on
     * the supported server, {@code isNull} over a column read {@code FINAL} reads the whole column, the subcolumn only its
     * null map. The paths travel as text and the day as a number, so the cell's Java shape does not depend on how the
     * client maps a {@code UUID}.
     */
    public static final class StandIn extends CustomField<Object> {

        private final ComputedField computed;
        private final String table;

        private StandIn(ComputedField computed, String table) {
            super(DSL.name(computed.name()), SQLDataType.OTHER);
            this.computed = computed;
            this.table = table;
        }

        /** The computed field this stands in for. */
        public ComputedField computed() {
            return computed;
        }

        @Override
        public void accept(Context<?> ctx) {
            final boolean status = computed.kind() == Kind.STATUS;
            final Field<?> body = status
                    ? DSL.field("toUInt8(({0} = toUUID('00000000-0000-0000-0000-000000000000')) OR ({1} = 1))", Integer.class,
                            column("event_id"), column("request_body.null"))
                    : column("request_body");
            final Field<?> markers = status
                    ? DSL.field("toUInt8({0} = 0)", Integer.class, column("request_cache_control.null"))
                    : column("request_cache_control");
            ctx.visit(DSL.field("tuple({0}, toString({1}), arrayMap(x -> toString(x), {2}), {3}, toYYYYMMDD({4}))", Object.class,
                    body, column("request_parent_hash"), column("request_ancestor_hashes"), markers, column("request_time")));
        }

        private Field<?> column(String name) {
            return DSL.field(DSL.name(table, name));
        }
    }
}
