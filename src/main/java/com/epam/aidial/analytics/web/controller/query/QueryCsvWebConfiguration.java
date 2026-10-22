package com.epam.aidial.analytics.web.controller.query;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.ContentNegotiationStrategy;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.UrlPathHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Keeps the CSV endpoints' failures in the {@code ErrorView} contract when the client asks only for CSV.
 *
 * <p>A client of {@link QueryCsvController} naturally sends {@code Accept: text/csv}. The success path
 * ignores that header (it sets its content type explicitly), but a failure is an {@code ErrorView}
 * returned from {@code DefaultExceptionHandler} and content-negotiated like any body: against
 * {@code text/csv} no converter can write it, the handler's result is discarded and the exception
 * escapes as a bodiless 500. So for exactly the two CSV paths the requested media types are the
 * {@code Accept} header's, with {@code application/json} appended — at the lowest precedence, after
 * whatever the client listed — when the header does not already admit it. Every other path negotiates
 * exactly as before: the header strategy alone, which is also what Spring Boot configures by default.
 * {@code strategies(...)} replaces the configured list, so enabling parameter-based negotiation
 * ({@code spring.mvc.contentnegotiation.favor-parameter}) would mean adding its strategy here too.
 */
@Configuration
public class QueryCsvWebConfiguration implements WebMvcConfigurer {

    static final Set<String> CSV_PATHS = Set.of(QueryCsvController.EXECUTE_CSV_PATH, QueryCsvController.EXECUTE_SQL_CSV_PATH);

    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer.strategies(List.of(new CsvEndpointNegotiation()));
    }

    static final class CsvEndpointNegotiation implements ContentNegotiationStrategy {

        private final ContentNegotiationStrategy header = new HeaderContentNegotiationStrategy();

        @Override
        public List<MediaType> resolveMediaTypes(NativeWebRequest request) throws HttpMediaTypeNotAcceptableException {
            final List<MediaType> requested = header.resolveMediaTypes(request);
            if (!isCsvEndpoint(request) || requested.stream().anyMatch(t -> t.includes(MediaType.APPLICATION_JSON))) {
                return requested;
            }
            final List<MediaType> withJson = new ArrayList<>(requested);
            withJson.add(MediaType.APPLICATION_JSON);
            return withJson;
        }

        private static boolean isCsvEndpoint(NativeWebRequest request) {
            final HttpServletRequest http = request.getNativeRequest(HttpServletRequest.class);
            return http != null && CSV_PATHS.contains(UrlPathHelper.defaultInstance.getPathWithinApplication(http));
        }
    }
}
