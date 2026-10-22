package com.epam.aidial.analytics.service.table.rowpolicy;

import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FilterNodeDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * One role's row policy on one table: a {@code filter} in the structured query's filter grammar, which the
 * role's reads of the table are narrowed to.
 *
 * <p>A policy narrows a read and never grants one: whether the role may read the table at all is still
 * decided by its privileges and the table's grant lists. There is no "unrestricted" policy — a role that may
 * read the table and has no policy on it already sees every row, and lifts the confinement of the caller's
 * other roles (see {@code RowPolicyFilter}).
 */
public record RowPolicy(@JsonDeserialize(using = FilterNodeDeserializer.class) FilterNode filter) {}
