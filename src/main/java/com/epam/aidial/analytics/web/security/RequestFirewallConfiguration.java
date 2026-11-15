package com.epam.aidial.analytics.web.security;

import com.epam.aidial.analytics.web.handler.ErrorView;
import org.apache.tomcat.util.buf.EncodedSolidusHandling;
import org.springframework.boot.tomcat.ConfigurableTomcatWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lets a percent-encoded {@code /} ({@code %2F}) and {@code %} ({@code %25}) travel inside one path segment, in both
 * security modes, so a role such as {@code /analytics/viewers} is addressable as {@code .../access/%2Fanalytics%2Fviewers}
 * (see {@code openspec/specs/authentication/spec.md}, "Encoded path separators and firewall refusals").
 *
 * <p>Three layers take part. Tomcat passes {@code %2F} through undecoded, so no layer treats it as a separator while
 * matching a request to a handler or an authentication rule; Spring Security's firewall accepts {@code %2F} and
 * {@code %25} and keeps every other rule ({@code ;}, {@code \}, {@code //}, an encoded {@code .}, NUL, CR, LF, U+2028, U+2029,
 * {@code .}/{@code ..} segments); and Spring MVC decodes a matched path variable once. A firewall refusal is rendered as
 * {@code ErrorView} {@code 400 bad_request}.
 */
@Configuration(proxyBeanMethods = false)
public class RequestFirewallConfiguration {

    @Bean
    public HttpFirewall httpFirewall() {
        final StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowUrlEncodedSlash(true);
        firewall.setAllowUrlEncodedPercent(true);
        return firewall;
    }

    @Bean
    public RequestRejectedHandler requestRejectedHandler(JsonMapper jsonMapper) {
        return (request, response, rejected) -> {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            // Not the firewall's own message: for a rejected header it echoes the header's value.
            response.getWriter().write(jsonMapper.writeValueAsString(new ErrorView(request, HttpStatus.BAD_REQUEST,
                    "bad_request", "The request was rejected: its path or headers carry a character or sequence the "
                    + "service refuses (such as ';', '\\', '//', an encoded '.', or a control character)")));
        };
    }

    @Bean
    public WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory> encodedSolidusPassthrough() {
        return factory -> factory.addConnectorCustomizers(
                connector -> connector.setEncodedSolidusHandling(EncodedSolidusHandling.PASS_THROUGH.getValue()));
    }
}
