package com.epam.aidial.analytics.web.storage;

import com.epam.aidial.analytics.clickhouse.replication.StorageReadyGate;
import com.epam.aidial.analytics.web.handler.ErrorView;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Answers {@code 503 clickhouse_unavailable} on {@code /v1/**} until the ClickHouse storage phase has opened the
 * {@link StorageReadyGate}. Readiness already keeps a Service's traffic away meanwhile; this covers traffic that
 * bypasses it, such as a port-forward. The operational endpoints under {@code /actuator} are not filtered, so
 * liveness keeps answering through a long conversion.
 */
public class StorageReadyFilter extends OncePerRequestFilter {

    /** The functional surface the filter covers. */
    public static final String URL_PATTERN = "/v1/*";

    private final StorageReadyGate gate;
    private final JsonMapper jsonMapper;

    public StorageReadyFilter(StorageReadyGate gate, JsonMapper jsonMapper) {
        this.gate = gate;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (gate.isOpen()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(jsonMapper.writeValueAsString(new ErrorView(request, HttpStatus.SERVICE_UNAVAILABLE,
                "clickhouse_unavailable", "ClickHouse storage is being verified at startup; retry shortly")));
    }
}
