package com.epam.aidial.analytics.service.enrichment.model;

import com.epam.aidial.analytics.service.query.model.SortDir;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One ordering key of a {@link MemberSelect}: a declared column of the pipeline's read source and a direction.
 *
 * <p>Deliberately a column and a direction rather than an expression. Choosing among a group's members
 * needs a ranking, not a computation, and accepting an expression here would widen the validated-SQL
 * surface for nothing — {@code prefer_sql} already covers the cases that need a predicate.
 *
 * <p>Unknown properties are tolerated for the same reason {@link ReadyWhen} tolerates them.
 */
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemberOrder {

    /** A column declared on the pipeline's read source. Validated to resolve there at registration. */
    private String column;

    /** Ascending or descending; wire values {@code asc}/{@code desc}. Defaults to descending when absent. */
    private SortDir direction;

    /**
     * The declared direction, or {@link SortDir#DESC} when none was given. Descending is the useful
     * default here: every ordering this exists to express — the most messages, the largest body, the most
     * recent activity — ranks the interesting member highest.
     */
    public SortDir directionOrDefault() {
        return direction == null ? SortDir.DESC : direction;
    }
}
