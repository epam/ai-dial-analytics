package com.epam.aidial.analytics.service.security;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.grant.ColumnPolicyCodec;
import com.epam.aidial.analytics.service.table.grant.GoverningAccess;
import com.epam.aidial.analytics.service.table.grant.TableAccessEvaluator;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicyCodec;
import com.epam.aidial.analytics.web.security.ProviderRolePrivileges;
import com.epam.aidial.analytics.web.security.SecurityModeResolver;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.mock;

/** Builds a {@link SensitiveColumnAccess} for tests that exercise the clearance alone: its caller carries no provider role, so no column policy narrows it. */
public final class SensitiveColumnAccessFixture {

    private SensitiveColumnAccessFixture() {
    }

    public static SensitiveColumnAccess withoutColumnPolicies(TableAccessEvaluator evaluator, SecurityModeResolver mode) {
        final JsonMapper jsonMapper = new JsonMapperConfiguration().jsonMapper();
        return new SensitiveColumnAccess(evaluator, mode, mock(TableDefinitionRepository.class), mock(GoverningAccess.class),
                mock(SecurityClaimsExtractor.class), mock(ProviderRolePrivileges.class), new RowPolicyCodec(jsonMapper),
                new ColumnPolicyCodec(jsonMapper), mock(ColumnMappingRepository.class));
    }
}
