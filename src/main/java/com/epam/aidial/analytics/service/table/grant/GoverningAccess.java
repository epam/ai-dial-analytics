package com.epam.aidial.analytics.service.table.grant;

import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import com.epam.aidial.analytics.service.table.TableAccess;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.web.security.Privilege;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The grant lists that decide a caller's privileges on a table, shared by {@link TableAccessEvaluator} and
 * {@link TableCallerPermissions} so enforcement and reporting read the same lists.
 *
 * <p>For any table but an enrichment that is its own {@code access}. An enrichment is never queried without its
 * source as {@code FROM}, so its read side is its source's: the source's {@code read} and {@code sensitive}, and a
 * {@code get} that also carries the source's {@code modify}, so a caller that sees the source through
 * {@code modify} sees its enrichments too. A {@code *} in that {@code modify} confers nothing on the source and is
 * dropped, since {@code get} would honour it. {@code write} and {@code modify} stay the enrichment's own, and a
 * missing source leaves the read side empty, failing closed. The enrichment's own read-side lists are never read:
 * one left by an earlier build confers nothing.
 */
@Component
@RequiredArgsConstructor
public class GoverningAccess {

    private final TableDefinitionRepository definitions;

    /** The source of an enrichment; null for any other table, and for an enrichment whose source row is missing. */
    public TableDefinitionEntity sourceOf(TableDefinitionEntity definition) {
        if (definition.getType() != TableType.ENRICHMENT || definition.getSourceTable() == null) {
            return null;
        }
        return definitions.findById(definition.getSourceTable()).orElse(null);
    }

    /** The grant lists that decide privileges on {@code definition}. */
    public TableAccess of(TableDefinitionEntity definition) {
        return compose(definition, sourceOf(definition));
    }

    /** {@link #of} with the source already in hand ({@code null} when missing or not an enrichment). */
    static TableAccess compose(TableDefinitionEntity definition, TableDefinitionEntity source) {
        if (definition.getType() != TableType.ENRICHMENT) {
            return definition.getAccess();
        }
        final TableAccess governing = new TableAccess();
        if (source != null && source.getAccess() != null) {
            final TableAccess from = source.getAccess();
            final List<String> get = new ArrayList<>(from.rolesFor(Privilege.TABLE_GET));
            from.rolesFor(Privilege.TABLE_MODIFY).stream()
                    .filter(role -> !SecurityClaimsExtractor.ANYONE.equals(role))
                    .forEach(get::add);
            governing.setGet(get);
            governing.setRead(new ArrayList<>(from.rolesFor(Privilege.TABLE_READ)));
            governing.setSensitive(new ArrayList<>(from.rolesFor(Privilege.TABLE_SENSITIVE)));
        }
        if (definition.getAccess() != null) {
            governing.setWrite(new ArrayList<>(definition.getAccess().rolesFor(Privilege.TABLE_WRITE)));
            governing.setModify(new ArrayList<>(definition.getAccess().rolesFor(Privilege.TABLE_MODIFY)));
        }
        return governing;
    }
}
