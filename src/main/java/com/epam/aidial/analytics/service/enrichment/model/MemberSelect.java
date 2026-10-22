package com.epam.aidial.analytics.service.enrichment.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * A group pipeline's member-selection policy, stored as the {@code member_select} JSONB: which of a group's
 * members bear on its label. Null means the assembly's default policy — the newest members by the source's
 * total order, bounded only by the configured caps.
 *
 * <p>It exists because "the newest N" is the wrong policy for the data group enrichment was built for, and
 * because applying a policy at all is what makes the read affordable. A DIAL conversation's newest hop is
 * frequently a two-message internal sub-agent call, while the body carrying the accumulated message history
 * is the chain entry or else the hop with the most request messages; and selecting members from cheap
 * columns first is what lets the assembly read bodies for two members instead of two hundred.
 *
 * <p>The three fields compose as one policy, evaluated after the membership filter and the safety lag:
 * {@link #preferSql} names the candidates, {@link #orderBy} ranks them, {@link #limit} takes the top of
 * that ranking. The fallback in {@code preferSql} is the load-bearing part — a preference no member
 * satisfies must not turn into an empty group, because a group with no members is indistinguishable from
 * one that has aged out of the source.
 *
 * <p>Unknown properties are tolerated for the same reason {@link ReadyWhen} tolerates them.
 */
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemberSelect {

    /**
     * Boolean predicate over the read source's declared columns marking the members that should be
     * preferred. Validated under the same bounded grammar as {@code filter_sql}, and its referenced columns
     * run the same sensitive-column entitlement check.
     *
     * <p>When <b>no</b> member of a group satisfies it, every member becomes a candidate instead. It is a
     * preference, not a second filter: {@code filter_sql} already decides membership, and a pipeline that
     * prefers a chain-entry hop still has to label a conversation that has none.
     */
    @JsonProperty("prefer_sql")
    private String preferSql;

    /**
     * How to rank the candidates, most-interesting first. Applied in order, and always followed by the
     * source's total order descending as a deterministic tiebreak, so re-assembling an unchanged group
     * selects the same members.
     */
    @JsonProperty("order_by")
    private List<MemberOrder> orderBy = new ArrayList<>();

    /**
     * How many candidates to retain. Lowers the assembly's effective row cap rather than sitting beside it,
     * so a caller asking for more members than the pipeline allows cannot defeat the policy — which is what
     * lets this ship without a change to the caller.
     */
    private Integer limit;
}
