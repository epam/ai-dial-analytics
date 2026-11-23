package com.epam.aidial.analytics.service.audit;

import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableView;

/**
 * A table as it stood at a revision, with the state of the table's life containing that revision: the live row while
 * that life runs, otherwise the row recorded at the deletion that ended it. For an enrichment it also carries the
 * source state that judges it: the source just before the enrichment's deletion once its life has ended, otherwise the
 * source's own life containing the revision. A revision's column visibility is judged by those states, never by a
 * lookup of the name.
 *
 * @param view       the table at the revision; its columns are {@code null}, not tracked, for a system table
 * @param life       the table's life state, or {@code null} when none is recorded
 * @param sourceLife an enrichment's source state; {@code null} for any other table, or when none is recorded
 */
public record TableSnapshot(TableView view, TableDefinitionEntity life, TableDefinitionEntity sourceLife) {}
