package com.epam.aidial.analytics.web.security;

import com.epam.aidial.analytics.web.handler.ErrorView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Renders authentication failures (missing, malformed, or invalid token) as the standard
 * {@link ErrorView} JSON with a {@code 401} status and the {@code unauthorized} code. Runs inside the
 * security filter chain, so it — not {@code DefaultExceptionHandler} — is what produces the body.
 */
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ErrorViewWriter errorViewWriter;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        ErrorView body = new ErrorView(request, HttpStatus.UNAUTHORIZED, "unauthorized",
                "Authentication is required to access this resource");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        errorViewWriter.write(response, body);
    }
}
