package com.epam.aidial.analytics.web.security;

import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.text.ParseException;
import java.util.Map;

/**
 * A {@link JwtDecoder} that supports several identity providers at once: it reads the {@code iss}
 * claim from the (still unverified) token and dispatches to the issuer-specific decoder, which then
 * verifies the signature and claims. Tokens whose issuer is not configured are rejected.
 */
@Slf4j
public class MultiIssuerJwtDecoder implements JwtDecoder {

    private final Map<String, JwtDecoder> issuerToDecoderMap;

    public MultiIssuerJwtDecoder(Map<String, JwtDecoder> issuerToDecoderMap) {
        this.issuerToDecoderMap = Map.copyOf(issuerToDecoderMap);
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        String issuer = getIssuer(token);
        JwtDecoder decoder = issuerToDecoderMap.get(issuer);
        if (decoder == null) {
            log.warn("Rejecting token from unrecognized issuer: {}", issuer);
            throw new InvalidBearerTokenException("Unrecognized issuer: " + issuer);
        }
        return decoder.decode(token);
    }

    private static String getIssuer(String token) {
        try {
            return SignedJWT.parse(token).getJWTClaimsSet().getIssuer();
        } catch (ParseException e) {
            throw new InvalidBearerTokenException("Malformed JWT token", e);
        }
    }
}
