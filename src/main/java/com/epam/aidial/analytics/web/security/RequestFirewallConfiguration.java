package com.epam.aidial.analytics.web.security;

import com.epam.aidial.analytics.web.handler.ErrorView;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders a refusal by Spring Security's default request firewall as {@code ErrorView} {@code 400 bad_request}, in both
 * security modes, instead of a bare {@code 400} (see {@code openspec/specs/authentication/spec.md}, "Encoded path
 * separators are refused"). The firewall itself keeps every default rule.
 */
@Configuration(proxyBeanMethods = false)
public class RequestFirewallConfiguration {

    @Bean
    public RequestRejectedHandler requestRejectedHandler(JsonMapper jsonMapper) {
        return (request, response, rejected) -> {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            // Not the firewall's own message: for a rejected header it echoes the header's value.
            response.getWriter().write(jsonMapper.writeValueAsString(new ErrorView(request, HttpStatus.BAD_REQUEST,
                    "bad_request", "The request was rejected: its path or headers carry a character or sequence the "
                    + "service refuses (such as ';', '\\', '//', an encoded '/', '%' or '.', or a control character)")));
        };
    }
}
