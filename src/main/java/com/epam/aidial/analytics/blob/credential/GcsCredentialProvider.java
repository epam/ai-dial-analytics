package com.epam.aidial.analytics.blob.credential;

import com.google.auth.ServiceAccountSigner;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.ComputeEngineCredentials;
import com.google.auth.oauth2.ExternalAccountCredentials;
import com.google.auth.oauth2.GoogleCredentials;
import lombok.extern.slf4j.Slf4j;
import org.jclouds.domain.Credentials;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Resolves Google Cloud Storage credentials, shaped like {@link AzureCredentialProvider} and
 * {@link AwsCredentialProvider}: the configured pair when both halves are present, and otherwise a
 * <em>keyless</em> access token.
 *
 * <p><strong>Key-pair mode</strong> hands the pair to jclouds unchanged — identity is the service account's
 * {@code client_email}, credential its PEM private key — and jclouds' own JWT-bearer flow signs, exchanges
 * and caches the token. Nothing in this class runs per request in that mode.
 *
 * <p><strong>Keyless mode</strong> resolves Google Application Default Credentials: the file named by
 * {@code GOOGLE_APPLICATION_CREDENTIALS} when set (a service-account key or a workload-identity-federation
 * configuration), otherwise the environment's metadata server — on GKE with Workload Identity, the pod's
 * bound service account. The credential is scoped to Cloud Storage read-only and its access token reaches
 * jclouds as the credential half, which the {@code bearerTokenCredentials} OAuth type sends as
 * {@code Authorization: Bearer}. jclouds consults the supplier per request; refresh happens in
 * {@link GoogleCredentials#refreshIfExpired()} on the calling thread, ahead of expiry and serialized inside
 * the library, so concurrent callers share one token request and no background thread is started.
 *
 * <p>Resolution is <em>lazy</em>: nothing is looked up until the first {@link #getCredentials()}, so a
 * missing credential fails that sync cycle rather than construction, and a failed first resolution is
 * retried from scratch by the next cycle — a credentials file that was fixed in the meantime is re-read.
 *
 * <p>The first successful resolution logs the principal the token was issued for. On GCP the metadata server
 * answers whether or not Workload Identity is in effect, so a pod scheduled where it is not gets the
 * <em>node's</em> service account; naming the principal is what makes that substitution visible.
 *
 * <p>Never log an access token, a private key or a credential object: {@code GoogleCredentials#toString()}
 * includes the current token state.
 */
@Slf4j
public class GcsCredentialProvider implements CredentialProvider, AutoCloseable {

    /** The only scope requested: {@code data-sync} lists and reads, nothing else. */
    static final String READ_ONLY_SCOPE = "https://www.googleapis.com/auth/devstorage.read_only";

    private static final String CREDENTIALS_FILE_ENV = "GOOGLE_APPLICATION_CREDENTIALS";

    /** Source of keyless credentials; a seam so tests need neither an ADC environment nor a network. */
    @FunctionalInterface
    interface CredentialsSource {
        GoogleCredentials load() throws IOException;
    }

    private final Credentials keyPairCredentials;
    private final CredentialsSource source;
    private volatile GoogleCredentials resolved;

    /**
     * @param identity service-account client email, or {@code null} to select keyless mode
     * @param secret   the account's PEM private key, or {@code null} to select keyless mode
     */
    public GcsCredentialProvider(String identity, String secret) {
        if (identity != null && secret != null) {
            this.keyPairCredentials = new Credentials(identity, secret);
            this.source = null;
        } else {
            this.keyPairCredentials = null;
            this.source = GoogleCredentials::getApplicationDefault;
        }
    }

    /** Test seam: keyless mode over a supplied credential source. */
    GcsCredentialProvider(CredentialsSource source) {
        this.keyPairCredentials = null;
        this.source = source;
    }

    @Override
    public Credentials getCredentials() {
        if (keyPairCredentials != null) {
            return keyPairCredentials;
        }
        GoogleCredentials credentials = resolved;
        if (credentials == null) {
            // Refreshed as part of resolution, so first use pays exactly one token request.
            credentials = resolve();
        } else {
            try {
                credentials.refreshIfExpired();
            } catch (IOException ex) {
                throw new UncheckedIOException("Failed to refresh the Google access token for the google-cloud-storage blob source", ex);
            }
        }
        AccessToken token = credentials.getAccessToken();
        if (token == null || token.getTokenValue() == null) {
            throw new IllegalStateException("Google credentials returned no access token for the google-cloud-storage blob source");
        }
        // Blank identity + token credential: the bearerTokenCredentials OAuth type sends only the credential.
        return new Credentials("", token.getTokenValue());
    }

    /**
     * Loads, scopes and first-refreshes the keyless credential once. Double-checked so concurrent first use
     * makes one lookup and one token request; the field is only published after a successful refresh, so a
     * failure leaves the next cycle to start over.
     */
    private GoogleCredentials resolve() {
        GoogleCredentials current;
        synchronized (this) {
            current = resolved;
            if (current == null) {
                try {
                    GoogleCredentials loaded = source.load();
                    current = loaded.createScopedRequired() ? loaded.createScoped(List.of(READ_ONLY_SCOPE)) : loaded;
                    current.refreshIfExpired();
                } catch (IOException ex) {
                    throw new UncheckedIOException("Cannot resolve Google Application Default Credentials for the "
                            + "google-cloud-storage blob source; bind the pod's service account through GKE Workload "
                            + "Identity, or point " + CREDENTIALS_FILE_ENV + " at a credentials file", ex);
                }
                log.info("google-cloud-storage blob access is keyless; principal {} from {}",
                        principalOf(current), sourceOf(current));
                resolved = current;
            }
        }
        return current;
    }

    /**
     * The account the token is issued for, where the credential type exposes one. The metadata server's
     * account is one extra call, made once; any failure there downgrades to "unknown" rather than failing
     * a cycle that already holds a valid token.
     */
    static String principalOf(GoogleCredentials credentials) {
        try {
            String account = null;
            if (credentials instanceof ServiceAccountSigner signer) {
                account = signer.getAccount();
            } else if (credentials instanceof ExternalAccountCredentials external) {
                account = external.getServiceAccountEmail();
            }
            return account == null || account.isBlank() ? "unknown" : account;
        } catch (RuntimeException ex) {
            log.debug("Could not determine the Google principal", ex);
            return "unknown";
        }
    }

    private static String sourceOf(GoogleCredentials credentials) {
        if (credentials instanceof ComputeEngineCredentials) {
            return "the metadata server";
        }
        String file = System.getenv(CREDENTIALS_FILE_ENV);
        return file != null && !file.isBlank() ? "the credentials file named by " + CREDENTIALS_FILE_ENV : "Application Default Credentials";
    }

    /**
     * Drops the cached credential. Nothing here owns a client or a thread — the library's default transport
     * holds no pool and refresh runs on the caller — so this exists so {@code BlobClient.shutdown()} releases
     * the provider uniformly with the AWS one.
     */
    @Override
    public void close() {
        resolved = null;
    }
}
