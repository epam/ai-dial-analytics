package com.epam.aidial.analytics.service.table.grant;

import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicyCodec;
import com.epam.aidial.analytics.web.security.Privilege;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WildcardGranteeReportTest {

    private final WildcardGranteeReport report = new WildcardGranteeReport(
            mock(TableDefinitionRepository.class), new RowPolicyCodec(JsonMapper.builder().build()));

    @Test
    void namesEachTableAndWhereItNamesTheWildcard() {
        final TableDefinitionEntity open = table("open");
        open.getAccess().setRolesFor(Privilege.TABLE_READ, List.of("team-x", "*"));
        open.getAccess().setRolesFor(Privilege.TABLE_WRITE, List.of("*"));
        final TableDefinitionEntity confined = table("confined");
        confined.setRowPolicies("{\"*\":{\"filter\":{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"a\"},"
                + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"x\"}]}}}");
        final TableDefinitionEntity plain = table("plain");
        plain.getAccess().setRolesFor(Privilege.TABLE_READ, List.of("team-x"));

        assertThat(report.describe(List.of(open, confined, plain)))
                .containsExactly("open [read, write]", "confined [row policy]");
    }

    private static TableDefinitionEntity table(String name) {
        final TableDefinitionEntity table = new TableDefinitionEntity();
        table.setName(name);
        return table;
    }
}
