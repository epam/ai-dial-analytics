package com.epam.aidial.analytics.service.catalog.transfer;

import com.epam.aidial.analytics.exception.CatalogBundleMalformedException;
import com.epam.aidial.analytics.exception.CatalogBundleVersionUnsupportedException;
import com.epam.aidial.analytics.web.dto.catalog.BundleTable;
import com.epam.aidial.analytics.web.dto.catalog.CatalogBundle;
import com.epam.aidial.analytics.web.dto.table.CreateTableRequest.ColumnRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads an uploaded bundle.
 *
 * <p>An uploaded file part is not bound by the HTTP message converter, so neither the strict request mapper nor
 * {@code @Valid} reaches it; this applies both by hand. (The {@code options} part is JSON, and Spring binds it
 * through the strict converter like any request body.) The bundle is read in two stages so that a bundle from
 * a newer ADAS gets a readable answer:
 *
 * <ol>
 *   <li>the envelope alone ({@code format}, {@code format_version}), leniently except that a field repeated
 *       within one object is a 400 rather than last-wins (for bundle parsing only). A wrong {@code format} is a
 *       400; a {@code format_version} above {@link CatalogBundle#FORMAT_VERSION} is a 422 naming it, whatever
 *       unknown fields the newer shape also carries;</li>
 *   <li>the whole bundle, strictly: unknown fields at any depth and unknown enum values are a 400, and so is a
 *       bean-validation violation, as on every request body, and a null list element anywhere in the bundle,
 *       which the shared request records do not constrain themselves.</li>
 * </ol>
 */
@Component
public class CatalogBundleParser {

    /** How Jackson's duplicate detection words its refusal; what follows is the repeated field's name. */
    private static final String DUPLICATE_PREFIX = "Duplicate Object property";

    private final JsonMapper lenient;
    private final JsonMapper strict;
    private final Validator validator;

    public CatalogBundleParser(JsonMapper jsonMapper, Validator validator) {
        // The tree is what the strict stage binds, so a repeated field must be refused while it is built.
        this.lenient = jsonMapper.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        // The same strict copy StrictRequestJsonConfiguration hands the web converter.
        this.strict = jsonMapper.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        this.validator = validator;
    }

    /** The bundle in {@code content}, or a 400/422 saying why it cannot be read. */
    public CatalogBundle parseBundle(byte[] content) {
        final JsonNode envelope;
        try {
            envelope = lenient.readTree(content);
        } catch (JacksonException ex) {
            final String message = ex.getOriginalMessage();
            if (message != null && message.startsWith(DUPLICATE_PREFIX)) {
                throw new CatalogBundleMalformedException("The bundle repeats a field within one object: "
                        + message.substring(DUPLICATE_PREFIX.length()).trim());
            }
            throw new CatalogBundleMalformedException("The bundle is not readable JSON");
        }
        if (envelope == null || !envelope.isObject()) {
            throw new CatalogBundleMalformedException("The bundle must be a JSON object");
        }
        final JsonNode format = envelope.get("format");
        if (format == null || !format.isString() || !CatalogBundle.FORMAT.equals(format.asString())) {
            throw new CatalogBundleMalformedException("The file is not a catalog bundle: its format must be '"
                    + CatalogBundle.FORMAT + "'");
        }
        final JsonNode version = envelope.get("format_version");
        if (version == null || !version.isIntegralNumber() || version.asLong() < 1) {
            throw new CatalogBundleMalformedException("The bundle's format_version must be a positive integer");
        }
        if (version.asLong() > CatalogBundle.FORMAT_VERSION) {
            throw new CatalogBundleVersionUnsupportedException("The bundle has format_version " + version.asLong()
                    + ", newer than the highest this ADAS reads (" + CatalogBundle.FORMAT_VERSION + "); import it into an "
                    + "ADAS at least as new as the one that exported it");
        }
        return requireNoNullElements(requireValid(bind(envelope, CatalogBundle.class)));
    }

    private <T> T bind(JsonNode node, Class<T> type) {
        try {
            return strict.treeToValue(node, type);
        } catch (UnrecognizedPropertyException ex) {
            throw new CatalogBundleMalformedException("The bundle carries an unrecognized field '" + path(ex) + "'");
        } catch (JacksonException ex) {
            final String at = path(ex);
            throw new CatalogBundleMalformedException("The bundle could not be read"
                    + (at.isEmpty() ? "" : " at '" + at + "'") + "; check the value's type and spelling");
        }
    }

    private <T> T requireValid(T value) {
        final Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            throw new CatalogBundleMalformedException("The bundle is invalid: " + violations.stream()
                    .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .collect(Collectors.joining("; ")));
        }
        return value;
    }

    /**
     * Refuses a null element in the lists of the records the bundle shares with the request API, whose own
     * declarations leave them unconstrained; the bundle's own lists are constrained by {@code @NotNull}.
     */
    private static CatalogBundle requireNoNullElements(CatalogBundle bundle) {
        final List<String> problems = new ArrayList<>();
        final List<BundleTable> tables = nullSafe(bundle.tables());
        for (int i = 0; i < tables.size(); i++) {
            final String at = "tables[" + i + "]";
            final BundleTable table = tables.get(i);
            requireNoNull(table.table().columns(), at + ".table.columns", problems);
            requireNoNull(table.table().orderingKey(), at + ".table.ordering_key", problems);
            requireNoNull(table.schema().columns(), at + ".schema.columns", problems);
            requireNoNull(table.schema().orderingKey(), at + ".schema.ordering_key", problems);
            for (final ColumnRequest column : nullSafe(table.schema().columns())) {
                if (column != null && column.enumValues() != null && column.enumValues().contains(null)) {
                    problems.add(at + ".schema.columns[].enum_values must not contain null");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new CatalogBundleMalformedException("The bundle is invalid: " + String.join("; ", problems));
        }
        return bundle;
    }

    private static void requireNoNull(List<?> list, String at, List<String> problems) {
        if (list != null && list.stream().anyMatch(Objects::isNull)) {
            problems.add(at + " must not contain null");
        }
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** The JSON path a binding failure is at, in field names and indexes only, never the input. */
    private static String path(JacksonException ex) {
        final StringBuilder path = new StringBuilder();
        for (final JacksonException.Reference reference : ex.getPath()) {
            if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }
}
