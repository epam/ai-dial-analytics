package com.epam.aidial.analytics.service.query;

import org.jooq.Context;
import org.jooq.Field;
import org.jooq.impl.CustomField;
import org.jooq.impl.DSL;
import org.jooq.impl.QOM;
import org.jooq.impl.SQLDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An internal column of a statement over masked fields: for one mask condition in one context, whether an output cell
 * read an absent input — {@code NOT mask} for the row, {@code max(NOT mask)} for a group, the same over a window for a
 * window output. It is projected beside the outputs under a name chosen apart from theirs, and carries the outputs it
 * marks, so the executor builds each row's {@code $masked} member from it and drops it before anything else sees the
 * row ({@link Marks}).
 */
public final class MaskFlag extends CustomField<Integer> {

    /** The reserved row member listing a row's absent or partial output cells. */
    public static final String MEMBER = "$masked";

    /** The prefix of a flag column's name; the suffix is advanced past every output name of the statement. */
    public static final String NAME_PREFIX = "__adas_mask_";

    private final Field<?> expression;
    private final Set<String> marks;
    private final Set<String> nulls;

    /**
     * @param expression what the column computes, {@code 0} where no input it watches was absent
     * @param marks      the output names whose cell it marks
     * @param nulls      the output names whose cell is nulled where it is set: a bare masked composite field, whose absent
     *     value inside expressions is its empty value
     */
    public MaskFlag(Field<?> expression, Collection<String> marks, Collection<String> nulls) {
        super(DSL.name("mask_flag"), SQLDataType.INTEGER);
        this.expression = expression;
        this.marks = Set.copyOf(marks);
        this.nulls = Set.copyOf(nulls);
    }

    /** The flag projected at {@code projected}, alias unwrapped, or {@code null} when it projects anything else. */
    public static MaskFlag of(Field<?> projected) {
        final Field<?> field = projected instanceof QOM.FieldAlias<?> alias ? alias.$field() : projected;
        return field instanceof MaskFlag flag ? flag : null;
    }

    @Override
    public void accept(Context<?> ctx) {
        ctx.visit(expression);
    }

    /** Whether a flag cell says an input was absent: anything but an explicit {@code 0}, {@code NULL} included. */
    public static boolean set(Object value) {
        if (value instanceof Number number) {
            return number.longValue() != 0;
        }
        return !Boolean.FALSE.equals(value);
    }

    /**
     * The flag columns of one statement, by name, and its outputs in projection order. Applied to the materialized rows
     * after computed fields are completed: each row gains {@code $masked} listing its marked outputs (omitted when none),
     * a bare masked composite cell that is absent becomes {@code null}, and the flag columns leave the row.
     */
    public record Marks(List<String> outputs, Map<String, MaskFlag> flags) {

        /** The marks of a statement projecting {@code select}. */
        public static Marks of(List<? extends Field<?>> select) {
            final List<String> outputs = new ArrayList<>(select.size());
            final Map<String, MaskFlag> flags = new LinkedHashMap<>();
            for (final Field<?> field : select) {
                final MaskFlag flag = of(field);
                if (flag == null) {
                    outputs.add(field.getName());
                } else {
                    flags.put(field.getName(), flag);
                }
            }
            return new Marks(List.copyOf(outputs), flags);
        }

        /** Whether the statement projects no flag. */
        public boolean isEmpty() {
            return flags.isEmpty();
        }

        /** Whether {@code output}'s cell on {@code row} read an absent input. */
        public boolean marked(Map<String, Object> row, String output) {
            for (final Map.Entry<String, MaskFlag> flag : flags.entrySet()) {
                if (flag.getValue().marks.contains(output) && set(row.get(flag.getKey()))) {
                    return true;
                }
            }
            return false;
        }

        /** Builds each row's {@code $masked}, nulls its absent bare composite cells and drops the flag columns. */
        public void apply(List<Map<String, Object>> rows) {
            if (flags.isEmpty()) {
                return;
            }
            for (final Map<String, Object> row : rows) {
                final List<String> masked = new ArrayList<>();
                for (final String output : outputs) {
                    if (marked(row, output)) {
                        masked.add(output);
                    }
                }
                for (final Map.Entry<String, MaskFlag> flag : flags.entrySet()) {
                    if (set(row.get(flag.getKey()))) {
                        flag.getValue().nulls.forEach(output -> row.put(output, null));
                    }
                }
                flags.keySet().forEach(row::remove);
                if (!masked.isEmpty()) {
                    row.put(MEMBER, List.copyOf(masked));
                }
            }
        }
    }
}
