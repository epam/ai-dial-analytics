package com.epam.aidial.analytics.blob.credential;

import com.google.auth.ServiceAccountSigner;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.ComputeEngineCredentials;
import com.google.auth.oauth2.ExternalAccountCredentials;
import com.google.auth.oauth2.GoogleAuthUtils;
import com.google.auth.oauth2.GoogleCredentials;
import lombok.extern.slf4j.Slf4j;
import org.jclouds.domain.Credentials;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * configuration), then gcloud's application-default credentials file when present (local development),
 * otherwise the environment's metadata server — on GKE with Workload Identity, the pod's bound service
 * account. The lookup is done here rather than through {@link GoogleCredentials#getApplicationDefault()},
 * whose process-wide provider caches the first credential it loads and probes for a metadata server only
 * once, so neither a replaced credentials file nor a metadata server that was unreachable at first use would
 * ever be retried. The credential is always asked for the Cloud Storage read-only scope (types that
 * cannot take a scope, such as a gcloud user credential, ignore it) and its access token reaches
 * jclouds as the credential half, which the {@code bearerTokenCredentials} OAuth type sends as
 * {@code Authorization: Bearer}. jclouds consults the supplier per request; refresh happens in
 * {@link GoogleCredentials#refreshIfExpired()} on the calling thread, ahead of expiry and serialized inside
 * the library, so concurrent callers share one token request and no background thread is started.
 *
 * <p>Resolution is <em>lazy</em>: nothing is looked up until the first {@link #getCredentials()}, so a
 * missing credential fails that sync cycle rather than construction, and a failed first resolution is
 * retried from scratch by the next cycle — a credentials file that was fixed in the meantime is re-read, and
 * the metadata server is asked again.
 *
 * <p>The provider owns no client, pool or thread, so it has nothing to close.
 *
 * <p>The first successful resolution logs the principal the token was issued for. On GCP the metadata server
 * answers whether or not Workload Identity is in effect, so a pod scheduled where it is not gets the
 * <em>node's</em> service account; naming the principal is what makes that substitution visible.
 *
 * <p>Never log an access token, a private key or a credential object: {@code GoogleCredentials#toString()}
 * includes the current token state.
 */
@Slf4j
public class GcsCredentialProvider implements CredentialProvider {

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
            this.source = () -> loadApplicationDefault(System.getenv(CREDENTIALS_FILE_ENV),
                    Path.of(GoogleAuthUtils.getWellKnownCredentialsPath()));
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
                throw refreshFailure(credentials, ex);
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
     * Application Default Credentials, looked up afresh on every call: the file {@code envFile} names when set,
     * else the gcloud application-default credentials file when it exists, else the metadata server. Creating
     * the metadata-server credential makes no request, so an absent or unreachable metadata server surfaces
     * at the first token refresh, and is asked again the next time.
     */
    static GoogleCredentials loadApplicationDefault(String envFile, Path wellKnownFile) throws IOException {
        if (envFile != null && !envFile.isBlank()) {
            return fromFile(Path.of(envFile), CREDENTIALS_FILE_ENV + " file " + envFile);
        }
        if (Files.isRegularFile(wellKnownFile)) {
            return fromFile(wellKnownFile, "gcloud credentials file " + wellKnownFile);
        }
        return ComputeEngineCredentials.create();
    }

    private static GoogleCredentials fromFile(Path file, String description) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return GoogleCredentials.fromStream(in);
        } catch (IOException ex) {
            throw new IOException("Cannot read the " + description + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Loads, scopes and first-refreshes the keyless credential once. Double-checked so concurrent first use
     * makes one lookup and one token request; the field is only published after a successful refresh, so a
     * failure leaves the next cycle to start over. A failed lookup and a refused token request are reported
     * apart, because they have different fixes.
     */
    private GoogleCredentials resolve() {
        GoogleCredentials current;
        synchronized (this) {
            current = resolved;
            if (current == null) {
                try {
                    // Scoped unconditionally, not behind createScopedRequired(): the metadata-server and
                    // external-account types report false there yet do honour a requested scope, and the base
                    // implementation returns the credential unchanged for types that cannot take one.
                    current = source.load().createScoped(List.of(READ_ONLY_SCOPE));
                } catch (IOException ex) {
                    throw new UncheckedIOException("Cannot resolve Google Application Default Credentials for the "
                            + "google-cloud-storage blob source; bind the pod's service account through GKE Workload "
                            + "Identity, or point " + CREDENTIALS_FILE_ENV + " at a credentials file", ex);
                }
                try {
                    current.refreshIfExpired();
                } catch (IOException ex) {
                    throw refreshFailure(current, ex);
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

    /** A credential was found but its token request failed: refused, unbound, or unreachable. */
    private static UncheckedIOException refreshFailure(GoogleCredentials credentials, IOException cause) {
        String hint = credentials instanceof ComputeEngineCredentials
                ? "; check the Workload Identity binding (roles/iam.workloadIdentityUser) of the pod's service account, "
                        + "or outside GCP point " + CREDENTIALS_FILE_ENV + " at a credentials file"
                : "";
        return new UncheckedIOException("Failed to obtain a Google access token from " + sourceOf(credentials)
                + " for the google-cloud-storage blob source" + hint, cause);
    }
}
