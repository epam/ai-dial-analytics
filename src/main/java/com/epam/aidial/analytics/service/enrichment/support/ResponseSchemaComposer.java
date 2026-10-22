package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.enrichment.model.TransformOutput;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composes an {@code llm} transform's JSON response schema from the <b>pipeline's</b> target enrichment
 * and the transform's {@code outputs}. One fact, one authority: a field's name, JSON type, enum domain
 * and enum order come from the target column wherever it can carry them, and the prose the model is told
 * comes from the transform. Nothing is declared twice, so an enum order that drifts from the column's, a
 * numeric type spelled several ways and a domain the schema forgot become unrepresentable rather than
 * merely unlikely.
 *
 * <p><b>Why the pipeline.</b> Composition needs both halves — the transform's outputs and the target's
 * columns — and the pipeline is the object that carries the first and names the second. The table stays
 * ignorant of transforms.
 *
 * <h2>Four of a property's facts come from its column</h2>
 *
 * <p>Its {@code type}, its {@code enum} domain, that domain's <b>order</b>, and — since an output's
 * {@code prose} became optional — its {@code description}. A declared prose still wins; the column's
 * description fills in when there is none, and where both are blank the property composes no
 * {@code description} at all, which is valid and is what a folded legacy declaration already produces.
 * The default is applied <b>here only</b>: it is never stored and never echoed onto the authored surface,
 * because a derived value echoed there is sent back by the next read-modify-write patch, becomes authored,
 * and silently stops tracking the column. One consequence this owns rather than absorbs: a
 * {@code description}-touching metadata patch on an {@code llm} target now bumps the pipeline's
 * {@code generation}, because the column description is a live input to the composed prompt.
 *
 * <p><b>Order is the whole point, so it is carried structurally.</b> The wire map is parsed into a
 * {@link LinkedHashMap}, the {@code outputs} column stores an ordered array, and this class emits
 * {@link LinkedHashMap}s — so the author's declared order survives to the provider. It is deliberately
 * <em>not</em> the target's column order, which is tag-group-then-alphabetical and would make generation
 * order a function of column tags. Under {@code response_format: {type: json_schema, strict: true}}
 * property order is generation order, so an order silently destroyed by storage lets a field (say a
 * {@code risk_level}) be generated ahead of the fields its own prose instructs it to derive itself from.
 *
 * <p><b>What composing the domain from the column buys, beyond fixing the order drift.</b> A ClickHouse
 * enum column refuses an out-of-domain value at insert. Taking the domain <em>from</em> that column makes
 * "the model returned a value the column cannot store" unrepresentable — a stronger guarantee than
 * matching a sort order, and the one that would have turned a membership drift into a failed write rather
 * than a silent one. The accepted cost is that enum <em>order</em> in the prompt is now whoever chose the
 * column's dashboard sort: a {@code data_exposure} column may be ordered
 * {@code none, internal_confidential, personal_data, credentials, unclear} while its own prose ranks
 * severity {@code credentials > personal_data > internal_confidential}. Membership carries the
 * correctness; order carries none. An order override on the transform would rebuild precisely the
 * two-copies-of-one-domain drift this removes, so there is not one.
 */
@Component
public class ResponseSchemaComposer {

    private static final String TYPE = "type";
    private static final String ENUM = "enum";
    private static final String DESCRIPTION = "description";
    private static final String OBJECT = "object";
    private static final String STRING = "string";
    private static final String INTEGER = "integer";
    private static final String NUMBER = "number";
    private static final String BOOLEAN = "boolean";
    private static final String ARRAY = "array";

    /**
     * The composed schema for one pipeline's (target, transform) pair, driven by the {@code outputs}
     * <b>keys</b> with each key's structure looked up on the target column of the same name.
     *
     * <p>Each key is resolved to its column by {@link TargetOutputColumns}, the same rule the
     * registration gate applies when it derives the output mapping — the two must not be able to
     * disagree about which column an output names.
     *
     * @param outputs      the transform's declared outputs, in declaration order
     * @param targetColumns the target enrichment's columns
     * @param targetName    the target's name, for the failure messages
     * @throws PipelineValidationException if an output resolves to no column, resolves only to a
     *     {@code source_name}, declares {@code values} against a column that already declares
     *     {@code enum_values}, or resolves to a column type with no strict-mode representation
     */
    public Map<String, Object> compose(
            List<TransformOutput> outputs, List<ColumnMappingEntity> targetColumns, String targetName) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>(outputs.size());
        for (TransformOutput output : outputs) {
            ColumnMappingEntity column =
                    TargetOutputColumns.requireExposed(output.getName(), targetColumns, targetName);
            properties.put(output.getName(), property(output, column, targetName));
            required.add(output.getName());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(TYPE, OBJECT);
        // `required` is not a choice and cannot become one: strict mode requires every property to be
        // required, so there is no optional-output shape to express. Column nullability would have been the
        // wrong source anyway — an enrichment column is nullable because a row may not be labelled yet,
        // while a schema field is required because the model must answer. A field that may legitimately be
        // absent is spelled as a sentinel value plus a jsonata mapping it to NULL.
        schema.put("required", required);
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * One property: its type and domain from the column, and its {@code description} from the output's
     * declared prose, <b>else the column's own {@code description}</b>, else omitted.
     *
     * <p>That fallback is the fourth fact to come from the column, joining the type, the {@code enum}
     * domain and the enum order. The description was the one an author had to copy into the declaration by
     * hand, and two copies of one fact is exactly the drift this class was written to remove.
     *
     * <p><b>Applied here and nowhere else.</b> The default is never written into the stored {@code spec},
     * never served on {@code view=source}, and never served inside the authored {@code transform} block on
     * {@code view=compiled} — only into the composed schema. Materializing it would re-create the
     * drift-capable duplicate on purpose and destroy an operator's ability to tell an authored prose from a
     * defaulted one; echoing it onto the authored surface would be worse still, because the next
     * read-modify-write {@code PATCH} would send it back, it would become authored, and it would silently
     * stop tracking the column.
     *
     * <p>Both blank composes <b>no</b> {@code description}, which is valid JSON Schema and is exactly what
     * a folded legacy declaration already produces. It is reported as a note by
     * {@code POST /v1/pipelines/&#123;name&#125;/preview} rather than refused: a description-less field is
     * degraded, not broken.
     *
     * <p>{@code sql} outputs never reach here — a {@code sql} transform composes no schema at all, because
     * it asks no model anything, and its string is an expression rather than prose.
     */
    private static Map<String, Object> property(
            TransformOutput output, ColumnMappingEntity column, String targetName) {
        Map<String, Object> property = typeOf(output, column, targetName);
        final String description = StringUtils.isNotBlank(output.getProse())
                ? output.getProse()
                : column.getDescription();
        if (StringUtils.isNotBlank(description)) {
            property.put(DESCRIPTION, description);
        }
        return property;
    }

    /**
     * The type mapping, exhaustive by intent: a column type with no strict-mode representation is refused
     * naming the column and its type rather than composed into something the provider would reject.
     *
     * <p>{@code timestamp} carries <b>no {@code format}</b>: strict mode rejects the keyword, so a
     * timestamp property is a plain string and the value is parsed on the write side, as it already is.
     */
    private static Map<String, Object> typeOf(
            TransformOutput output, ColumnMappingEntity column, String targetName) {
        Map<String, Object> property = new LinkedHashMap<>();
        QueryFieldType type = column.getType();
        switch (type) {
            case STRING, UUID -> property.put(TYPE, STRING);
            case ENUM -> {
                property.put(TYPE, STRING);
                property.put(ENUM, List.copyOf(column.getEnumValues()));
            }
            case INTEGER, LONG -> property.put(TYPE, INTEGER);
            case DECIMAL -> property.put(TYPE, NUMBER);
            case BOOLEAN -> property.put(TYPE, BOOLEAN);
            case DATE, TIMESTAMP -> property.put(TYPE, STRING);
            case ARRAY -> {
                property.put(TYPE, ARRAY);
                property.put("items", items(column, targetName));
            }
            default -> throw new PipelineValidationException("transform output '" + output.getName()
                    + "' resolves to column '" + column.getName() + "' on '" + targetName + "', whose type "
                    + type.code() + " has no JSON Schema representation under strict mode; a model cannot be"
                    + " asked to produce it");
        }
        applyDeclaredDomain(output, column, property, targetName);
        return property;
    }

    private static Map<String, Object> items(ColumnMappingEntity column, String targetName) {
        QueryFieldType element = column.getElementType();
        if (element == null) {
            throw new PipelineValidationException("column '" + column.getName() + "' on '" + targetName
                    + "' is an array declaring no element_type, so its items cannot be typed");
        }
        Map<String, Object> items = new LinkedHashMap<>();
        switch (element) {
            case STRING, UUID, DATE, TIMESTAMP -> items.put(TYPE, STRING);
            case INTEGER, LONG -> items.put(TYPE, INTEGER);
            case DECIMAL -> items.put(TYPE, NUMBER);
            case BOOLEAN -> items.put(TYPE, BOOLEAN);
            default -> throw new PipelineValidationException("column '" + column.getName() + "' on '"
                    + targetName + "' is an array of " + element.code()
                    + ", which has no JSON Schema representation under strict mode");
        }
        return items;
    }

    /**
     * An output's own {@code values}, for a field whose closed domain the target column cannot carry.
     *
     * <p><b>Why "one authority per field" and not "always the column".</b> {@code enum_values} is
     * immutable once the column exists — a {@code PATCH} carrying it is a 422, and widening means drop and
     * re-add. A label whose value set is expected to change is therefore best declared as a plain
     * {@code string} column, yet its values are still a closed domain the model must be held to. A column
     * that cannot express a domain cannot be its authority. So declaring {@code values} on a column that
     * already has {@code enum_values} is refused naming both: still exactly one authority per field, chosen by
     * which of the two can hold it.
     *
     * <p>{@code values} buys <b>schema</b> enforcement only. A {@code string} column accepts anything, so
     * the domain is held by {@code strict: true} at the model boundary and by nothing at insert — the
     * "an out-of-domain value becomes unrepresentable" guarantee belongs to {@code enum_values} columns
     * alone, and the refusal below is worded so as not to imply otherwise.
     */
    private static void applyDeclaredDomain(
            TransformOutput output, ColumnMappingEntity column, Map<String, Object> property, String targetName) {
        if (output.getValues() == null || output.getValues().isEmpty()) {
            return;
        }
        if (column.getEnumValues() != null && !column.getEnumValues().isEmpty()) {
            throw new PipelineValidationException("transform output '" + output.getName() + "' declares values"
                    + " while column '" + column.getName() + "' on '" + targetName + "' already declares"
                    + " enum_values; a field has one authority for its domain, and the column's is the"
                    + " stronger one (ClickHouse refuses an out-of-domain value at insert) — drop the"
                    + " output's values");
        }
        // Against the COLUMN's type, not the composed property's: a date or timestamp column also composes
        // to a JSON string, and a declared value set is not a domain for either of those.
        if (column.getType() != QueryFieldType.STRING) {
            throw new PipelineValidationException("transform output '" + output.getName() + "' declares values"
                    + " against column '" + column.getName() + "' on '" + targetName + "' of type "
                    + column.getType().code() + "; a declared domain is only meaningful for a string column");
        }
        property.put(ENUM, List.copyOf(output.getValues()));
    }
}
