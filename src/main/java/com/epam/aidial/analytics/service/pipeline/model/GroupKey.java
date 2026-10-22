package com.epam.aidial.analytics.service.pipeline.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;

/**
 * One part of an aggregate pipeline's group-by tuple, stored inside the pipeline's {@code spec} JSONB.
 * A part is <b>either</b> a plain input {@code column} <b>or</b> a {@code trunc} time bucket over a
 * date/timestamp column — exactly one of the two, checked at registration.
 *
 * <p>{@link #as} is the <em>target</em> column this part writes, and the ordered list of {@code as}
 * names is validated to equal the target's {@code ordering_key}. For a plain column it defaults to the
 * column name; for a bucket it is required, since {@code trunc(request_time, day)} has no natural name.
 *
 * <p>Both forms are objects rather than the design note's bare-string shorthand for a plain column,
 * following the project's API convention that entry objects stay objects so metadata can be added later
 * without breaking clients. Unknown properties are tolerated.
 */
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GroupKey {

    /** A declared column of the input, grouped by as-is; null when this part is a {@link #trunc}. */
    private String column;

    /** A time bucket over a declared date/timestamp column; null when this part is a plain column. */
    private Trunc trunc;

    /** The target column this key writes. Optional for a plain column (defaults to it), required for a bucket. */
    private String as;

    /** Whether this part is a time bucket. */
    public boolean isBucket() {
        return trunc != null;
    }

    /** The target column this key writes: {@link #as} when set, else the plain column name. */
    public String outputName() {
        return StringUtils.isNotBlank(as) ? as : column;
    }

    /** The input column this part reads, whichever form it takes. */
    public String inputColumn() {
        return trunc == null ? column : trunc.getColumn();
    }

    /** A fixed-width time bucket: truncate {@code column} down to the start of its {@code unit}. */
    @Getter
    @Setter
    @EqualsAndHashCode
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Trunc {
        /** The declared date/timestamp column to bucket. */
        private String column;
        /** The bucket width; see {@link TruncUnit} for why the set is bounded. */
        private TruncUnit unit;
    }
}
