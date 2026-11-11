package com.epam.aidial.analytics.service.table.grant;

import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicyCodec;
import com.epam.aidial.analytics.web.security.Privilege;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Logs, once at startup, every table that names {@code *} in a grant list or as a row-policy role. {@code *}
 * stands for every authenticated caller; a deployment that stored it earlier, as an ordinary role name, can
 * see here which tables that now opens.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WildcardGranteeReport implements ApplicationRunner {

    private final TableDefinitionRepository definitions;
    private final RowPolicyCodec codec;

    @Override
    public void run(ApplicationArguments args) {
        final List<String> tables = describe(definitions.findAll());
        if (!tables.isEmpty()) {
            log.info("Tables naming '{}', which stands for every authenticated caller: {}",
                    SecurityClaimsExtractor.ANYONE, tables);
        }
    }

    /** One entry per table naming {@code *}: its name and where it names it. */
    List<String> describe(List<TableDefinitionEntity> tables) {
        final List<String> found = new ArrayList<>();
        for (final TableDefinitionEntity table : tables) {
            final List<String> places = new ArrayList<>();
            for (final Privilege privilege : PrivilegeScope.GRANTABLE) {
                if (table.getAccess() != null && table.getAccess().rolesFor(privilege).contains(SecurityClaimsExtractor.ANYONE)) {
                    places.add(PrivilegeScope.grantKey(privilege));
                }
            }
            try {
                if (codec.read(table.getRowPolicies()).containsKey(SecurityClaimsExtractor.ANYONE)) {
                    places.add("row policy");
                }
            } catch (IllegalStateException unreadable) {
                // A report must not stop the service from starting; reads of that table say what is wrong.
                log.warn("Row policies of table '{}' are not readable", table.getName());
            }
            if (!places.isEmpty()) {
                found.add(table.getName() + " " + places);
            }
        }
        return found;
    }
}
