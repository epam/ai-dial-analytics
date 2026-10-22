package com.epam.aidial.analytics.configuration;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.BackslashEscaping;
import org.jooq.conf.RenderQuotedNames;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * jOOQ is used in <strong>render-only</strong> mode: this {@link DSLContext} has no
 * {@link javax.sql.DataSource} and never executes SQL. The structured-query translators build a
 * jOOQ query model with it, then the SQL string is rendered ({@code getSQL(ParamType.INLINED)}) and
 * handed to the ClickHouse HTTP client for execution. The dialect is
 * {@link SQLDialect#CLICKHOUSE CLICKHOUSE} (experimental in jOOQ) so the rendered SQL targets
 * ClickHouse syntax.
 */
@Configuration
public class QueryDslConfiguration {

    @Bean
    public DSLContext clickhouseRenderContext() {
        Settings settings = new Settings()
                .withRenderFormatted(false)
                // Quote identifiers explicitly so reserved words / mixed-case names render safely.
                .withRenderQuotedNames(RenderQuotedNames.EXPLICIT_DEFAULT_QUOTED)
                // ClickHouse treats '\' as an escape inside string literals, but jOOQ's CLICKHOUSE dialect
                // only doubles quotes; without this a value ending in \' closes its literal (SQL injection).
                .withBackslashEscaping(BackslashEscaping.ON);
        return DSL.using(SQLDialect.CLICKHOUSE, settings);
    }
}
