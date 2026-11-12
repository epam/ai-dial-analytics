package com.epam.aidial.analytics.web.storage;

import com.epam.aidial.analytics.clickhouse.replication.StorageReadyGate;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.json.JsonMapper;

/** Registers {@link StorageReadyFilter} ahead of every other filter, the security chain included. */
@Configuration(proxyBeanMethods = false)
public class StorageReadyWebConfiguration {

    @Bean
    public FilterRegistrationBean<StorageReadyFilter> storageReadyFilter(StorageReadyGate gate, JsonMapper jsonMapper) {
        final FilterRegistrationBean<StorageReadyFilter> registration =
                new FilterRegistrationBean<>(new StorageReadyFilter(gate, jsonMapper));
        registration.addUrlPatterns(StorageReadyFilter.URL_PATTERN);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
