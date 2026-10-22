package com.epam.aidial.analytics.service.table.grant;

import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.web.security.Privilege;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-table authorization: whether the caller holds a table-scoped {@link Privilege} on a named table,
 * either <b>globally</b> or by a grant in that table's access lists. Invoked by
 * {@code PrivilegeAuthorizationManager} for an annotated endpoint, and programmatically where the table is
 * only known mid-request (the pipeline data plane, OTLP ingest, the query model).
 *
 * <p>There is <b>no bypass for any role name</b>. A caller that holds every privilege is authorized
 * because it holds the privilege, which is what removes the old {@code FULL_ADMIN} short-circuit: that
 * check made the whole object layer conditional on a name, and it is the reason a read grant underneath it
 * would have delivered a fraction of its value.
 *
 * <p>A table that does not exist is not authorized. Callers that must distinguish "absent" from
 * "unauthorized" look the definition up themselves; the non-disclosure rule means most must not.
 */
@Component("tableAccess")
@RequiredArgsConstructor
public class TableAccessEvaluator {

    private final TableDefinitionRepository definitions;
    private final SecurityClaimsExtractor claimsExtractor;

    /** Whether the caller holds {@code required} on {@code tableName}, globally or by grant. */
    @Transactional(readOnly = true)
    public boolean isAllowed(String tableName, Privilege required) {
        if (!PrivilegeScope.isTableScoped(required)) {
            throw new IllegalArgumentException(required + " is global-only and cannot be checked per table");
        }
        if (claimsExtractor.getPrivileges().contains(required)) {
            return true;
        }
        final TableDefinitionEntity definition = definitions.findById(tableName).orElse(null);
        if (definition == null) {
            return false;
        }
        return TableCallerPermissions.isGranted(
                definition.getAccess(), required, claimsExtractor.getProviderRoles());
    }

    /** Whether the caller may insert rows into {@code tableName}. */
    public boolean canWrite(String tableName) {
        return isAllowed(tableName, Privilege.TABLE_WRITE);
    }
}
