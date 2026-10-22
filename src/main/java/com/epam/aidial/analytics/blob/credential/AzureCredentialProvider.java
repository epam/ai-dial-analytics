package com.epam.aidial.analytics.blob.credential;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.core.http.HttpClient;
import com.azure.core.util.HttpClientOptions;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import lombok.extern.slf4j.Slf4j;
import org.jclouds.domain.Credentials;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Resolves Azure Blob credentials, mirroring ai-dial-core's {@code AzureCredentialProvider}.
 *
 * <p>If both an account name ({@code identity}) and account key ({@code secret}) are supplied, plain
 * shared-key credentials are used. Otherwise, it falls back to {@link DefaultAzureCredential} (the
 * Azure AD chain: managed identity, workload identity, environment, Azure CLI, ...) and exchanges it
 * for a short-lived bearer token scoped to {@code https://storage.azure.com/.default}. The token is
 * cached and refreshed shortly before expiry; jclouds receives it as the credential on each call.
 *
 * <p>This token-as-credential path requires EPAM's jclouds fork, whose azureblob provider accepts an
 * AAD token; upstream Apache jclouds is shared-key only.
 */
@Slf4j
public class AzureCredentialProvider implements CredentialProvider {

    private static final long EXPIRATION_WINDOW_IN_SEC = 10;
    private static final String STORAGE_SCOPE = "https://storage.azure.com/.default";

    private final Credentials sharedKeyCredentials;
    private final DefaultAzureCredential defaultCredential;
    private final TokenRequestContext tokenRequestContext;

    private volatile AccessToken accessToken;

    public AzureCredentialProvider(String identity, String secret) {
        if (identity != null && secret != null) {
            this.sharedKeyCredentials = new Credentials(identity, secret);
            this.defaultCredential = null;
            this.tokenRequestContext = null;
        } else {
            this.sharedKeyCredentials = null;
            HttpClientOptions clientOptions = new HttpClientOptions()
                    .setResponseTimeout(Duration.ofSeconds(30))
                    .setConnectTimeout(Duration.ofSeconds(10))
                    .setReadTimeout(Duration.ofSeconds(30));
            HttpClient httpClient = HttpClient.createDefault(clientOptions);
            this.defaultCredential = new DefaultAzureCredentialBuilder().httpClient(httpClient).build();
            this.tokenRequestContext = new TokenRequestContext().addScopes(STORAGE_SCOPE);
        }
    }

    @Override
    public Credentials getCredentials() {
        return Objects.requireNonNullElseGet(sharedKeyCredentials, this::getTemporaryCredentials);
    }

    private Credentials getTemporaryCredentials() {
        AccessToken current = accessToken;
        if (isExpired(current)) {
            // Double-checked locking so concurrent first-use makes a single token request, not one per thread.
            synchronized (this) {
                current = accessToken;
                if (isExpired(current)) {
                    log.debug("Requesting temporary Azure Storage token via DefaultAzureCredential");
                    current = defaultCredential.getTokenSync(tokenRequestContext);
                    accessToken = current;
                }
            }
        }
        // jclouds (EPAM fork) treats a blank identity + token credential as bearer-token auth.
        return new Credentials("", current.getToken());
    }

    private static boolean isExpired(AccessToken token) {
        return token == null
                || OffsetDateTime.now().plusSeconds(EXPIRATION_WINDOW_IN_SEC).isAfter(token.getExpiresAt());
    }
}
