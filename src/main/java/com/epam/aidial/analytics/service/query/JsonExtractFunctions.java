package com.epam.aidial.analytics.service.query;

import java.util.Set;

/**
 * The {@code json_extract_*} family, and the one distinction within it that costs real money: which
 * members can be served from a native JSON <b>subcolumn</b> and which need the whole serialized document.
 *
 * <p>Shared rather than restated because three places now depend on the same membership and for
 * different reasons — the renderer decides how to render an {@code object} argument, the pruning
 * collector decides whether a keyed join's projection may be pruned to the addressed paths, and pipeline
 * registration decides whether to tell the author they chose the expensive form. Separate copies of the
 * list are how a function added to the whole-document set later stops being reported by one of them while
 * still being rendered expensively by another: silent, and only visible as an outage.
 *
 * <p>The cost this distinction stands for is not proportional to the data. ClickHouse stores a
 * {@code JSON} column as one stream per path per part, so a whole-document read opens paths x parts read
 * buffers whatever the values weigh — hundreds of paths across hundreds of parts, on a column holding only
 * a few hundred MiB in total, is enough to exhaust a 2 GiB ceiling on buffers alone. Under the
 * {@code FINAL} every Class-1 materialize write carries, a whole-column read can cost an order of magnitude
 * more memory and time than the pruned subcolumns, so pruning is not a
 * nice-to-have on that path but the difference between a write that fits the ceiling and one that cannot.
 *
 * <p><b>{@code json_extract_array} is path-addressable, and was not always.</b> It was classified with
 * {@link #STRING_CI} on the belief that an array member "comes back typed {@code Dynamic}" and so has no
 * subcolumn to address. The member is indeed {@code Dynamic}, but that is not the same as unaddressable:
 * serializing <em>that subcolumn</em> back to JSON and extracting from it reads one stream instead of one
 * per path, and returns the same array. See {@code ExprTranslator.subcolumnExtract} for the rendering and
 * the one spelling of it that is silently wrong. Only {@link #STRING_CI} genuinely has no statically known
 * subcolumn — not knowing the key's spelling is the entire point of it — so it alone stays behind.
 */
public final class JsonExtractFunctions {

    /** Case-insensitive single-key extraction: no statically known key, so no addressable subcolumn. */
    public static final String STRING_CI = "json_extract_string_ci";

    /** Array extraction: path-addressable, through the addressed subcolumn's own re-serialization. */
    public static final String ARRAY = ArrayValuedFunctions.JSON_EXTRACT_ARRAY;

    /** The members that read an {@code object} column through native subcolumn access at a literal path. */
    public static final Set<String> PATH_ADDRESSABLE = Set.of(
            "json_extract_string", "json_extract_long", "json_extract_double", "json_extract_bool", ARRAY);

    /** The members that can only read an {@code object} column as {@code toJSONString(<column>)}. */
    public static final Set<String> SERIALIZED_TEXT = Set.of(STRING_CI);

    private JsonExtractFunctions() {
    }

    /** Every member of the family, however it reads an {@code object} column. */
    public static boolean isJsonExtract(String fn) {
        return PATH_ADDRESSABLE.contains(fn) || SERIALIZED_TEXT.contains(fn);
    }

    /**
     * Whether {@code fn} reads an {@code object} column through its <b>serialized text</b> rather than
     * through subcolumn access — true for {@link #STRING_CI} alone, the one member whose shape has no
     * statically known subcolumn to address.
     */
    public static boolean readsSerializedText(String fn) {
        return SERIALIZED_TEXT.contains(fn);
    }

    /**
     * The single-path function that answers the same question as {@code fn} at a known key, or {@code null}
     * when the shape has no cheaper equivalent. Only {@link #STRING_CI} has one, and only because knowing
     * the key's case is the whole of what it gives up.
     */
    public static String singlePathAlternative(String fn) {
        return STRING_CI.equals(fn) ? "json_extract_string" : null;
    }
}
