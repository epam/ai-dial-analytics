package com.epam.aidial.analytics.blob;

import com.epam.aidial.analytics.blob.credential.AwsCredentialProvider;
import com.epam.aidial.analytics.blob.credential.AzureCredentialProvider;
import com.epam.aidial.analytics.blob.credential.CredentialProvider;
import com.epam.aidial.analytics.configuration.BlobProperties;
import com.epam.aidial.analytics.exception.BlobAccessException;
import com.google.common.collect.ImmutableSet;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.domain.StorageType;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.jclouds.domain.Credentials;
import org.jclouds.io.Payload;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * The only component that knows about jclouds / the blob backend. Builds a {@link BlobStoreContext}
 * from configuration (provider {@code azureblob} or {@code aws-s3} for real storage, {@code filesystem} in
 * tests) and exposes prefix listing and per-blob streaming. The provider abstraction keeps the rest of the
 * service free of any cloud-specific API.
 *
 * <p>The context is created lazily on first use so the service still boots when the blob store is
 * unreachable; the failure then surfaces per sync run.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BlobClient {

    /**
     * jclouds requires a non-null identity/credential pair even for providers that ignore them (e.g.
     * {@code filesystem} in tests); these are the inert placeholders used when none are configured.
     */
    private static final String DEFAULT_IDENTITY = "identity";
    private static final String DEFAULT_CREDENTIAL = "credential";

    private final BlobProperties properties;
    private final BlobSpans blobSpans;
    private volatile BlobStoreContext context;
    /**
     * The credential provider, created once with the context and reused across retries. Memoized rather
     * than built per attempt because a workload-identity provider owns an SDK client: a failed
     * {@code buildView} would otherwise leave that client unreachable while {@link #context()} retries from
     * scratch, leaking one per failed sync cycle.
     */
    private volatile CredentialProvider credentials;

    /**
     * Lists the names of all blobs (not folder placeholders) under the given prefix in the configured
     * container, following pagination to completion.
     *
     * @param prefix key prefix to list under (e.g. {@code logs/2026/06/18/})
     * @return blob names found under the prefix (possibly empty)
     * @throws BlobAccessException if the listing cannot be completed
     */
    public List<String> list(String prefix) {
        return listWithMetadata(prefix).stream().map(BlobListing::name).toList();
    }

    /**
     * Lists all blobs (not folder placeholders) under the given prefix, returning each blob's name and
     * last-modified time as reported in the provider's list response (no per-blob metadata fetch). Used
     * by the near-real-time poller to select blobs newer than its watermark.
     *
     * @param prefix key prefix to list under (e.g. {@code logs/2026/06/18/})
     * @return blob listings found under the prefix (possibly empty)
     * @throws BlobAccessException if the listing cannot be completed
     */
    public List<BlobListing> listWithMetadata(String prefix) {
        return blobSpans.list(prefix, () -> {
            try {
                BlobStore blobStore = context().getBlobStore();
                List<BlobListing> listings = new ArrayList<>();
                String marker = null;
                do {
                    ListContainerOptions options = new ListContainerOptions().prefix(prefix).recursive();
                    if (marker != null) {
                        options.afterMarker(marker);
                    }
                    PageSet<? extends StorageMetadata> page = blobStore.list(properties.bucket(), options);
                    for (StorageMetadata metadata : page) {
                        if (metadata.getType() == StorageType.BLOB) {
                            listings.add(new BlobListing(metadata.getName(), toInstant(metadata.getLastModified())));
                        }
                    }
                    marker = page.getNextMarker();
                } while (marker != null);
                return listings;
            } catch (Exception ex) {
                throw new BlobAccessException("Failed to list blobs under prefix '" + prefix + "'", ex);
            }
        });
    }

    /** Epoch when the store reports no last-modified, so such a blob sorts before any real watermark. */
    private static Instant toInstant(Date date) {
        return date == null ? Instant.EPOCH : date.toInstant();
    }

    /**
     * Opens a stream to the raw (still gzip-compressed) bytes of a blob.
     *
     * @param key full blob key within the configured container
     * @return an input stream the caller must close; closing it releases the underlying jclouds payload
     * @throws IOException if the blob is missing or cannot be opened
     */
    public InputStream openStream(String key) throws IOException {
        Blob blob = blobSpans.get(key, () -> {
            try {
                return context().getBlobStore().getBlob(properties.bucket(), key);
            } catch (Exception ex) {
                throw new BlobAccessException("Failed to fetch blob '" + key + "'", ex);
            }
        });
        if (blob == null || blob.getPayload() == null) {
            throw new IOException("Blob not found: " + key);
        }
        Payload payload = blob.getPayload();
        try {
            return payload.openStream();
        } catch (IOException | RuntimeException ex) {
            // The payload was already fetched; if opening its stream fails, release it here so the open
            // network connection is not leaked (the caller only closes the stream on the success path).
            payload.release();
            throw ex;
        }
    }

    private BlobStoreContext context() {
        BlobStoreContext local = context;
        if (local == null) {
            synchronized (this) {
                local = context;
                if (local == null) {
                    local = buildContext();
                    context = local;
                }
            }
        }
        return local;
    }

    private BlobStoreContext buildContext() {
        ContextBuilder builder = ContextBuilder.newBuilder(properties.provider());
        if (isPresent(properties.endpoint())) {
            builder.endpoint(properties.endpoint());
        }
        // Normalize the configured credential pair and derive the shared-key-vs-managed-identity decision
        // once here, then pass it to both the credential provider and the override wiring.
        String identity = blankToNull(properties.identity());
        String credential = blankToNull(properties.credential());
        boolean managedIdentity = identity == null || credential == null;

        Properties overrides = effectiveOverrides(managedIdentity);
        if (!overrides.isEmpty()) {
            builder.overrides(overrides);
        }
        if (credentials == null) {
            credentials = credentialProvider(identity, credential);
        }
        builder.credentialsSupplier(credentials::getCredentials);
        builder.modules(ImmutableSet.of(new SLF4JLoggingModule()));
        return builder.buildView(BlobStoreContext.class);
    }

    /**
     * Selects credentials by provider from the already-normalized {@code identity}/{@code credential}
     * pair ({@code null} when absent). For {@code azureblob}, {@link AzureCredentialProvider} mirrors the
     * same shared-key-vs-Azure-AD decision based on whether both are present; for {@code aws-s3},
     * {@link AwsCredentialProvider} makes the same decision between a static access-key pair and AWS
     * workload identity (EKS IRSA). Both branches sit ahead of the fallback so the inert placeholders stay
     * confined to providers that ignore credentials entirely ({@code filesystem} in tests).
     *
     * <p>Package-private rather than {@code private} so a test can substitute a fixed credential.
     */
    CredentialProvider credentialProvider(String identity, String credential) {
        if (isAzureBlob()) {
            return new AzureCredentialProvider(identity, credential);
        }
        if (isAwsS3()) {
            return new AwsCredentialProvider(identity, credential, properties.endpoint());
        }
        Credentials fallback = new Credentials(
                identity == null ? DEFAULT_IDENTITY : identity,
                credential == null ? DEFAULT_CREDENTIAL : credential);
        return () -> fallback;
    }

    /**
     * Builds the jclouds context overrides. Operator-supplied {@code blob.overrides.*} take precedence;
     * when {@code azureblob} runs in managed-identity mode (no account name/key), the Azure AD bearer-token
     * overrides are auto-applied so the EPAM jclouds fork uses the token from {@link AzureCredentialProvider}.
     * Mirrors ai-dial-core's documented Azure AD setup.
     *
     * @param managedIdentity whether no shared-key pair is configured (decided once in {@link #buildContext})
     */
    private Properties effectiveOverrides(boolean managedIdentity) {
        Properties overrides = new Properties();
        if (properties.overrides() != null) {
            overrides.putAll(properties.overrides());
        }
        if (isAzureBlob() && managedIdentity) {
            overrides.putIfAbsent("jclouds.azureblob.auth", "azureAd");
            overrides.putIfAbsent("jclouds.oauth.credential-type", "bearerTokenCredentials");
            if (!isPresent(properties.endpoint())) {
                log.warn("azureblob managed-identity mode without blob.endpoint set; Azure AD auth "
                        + "requires endpoint https://<account>.blob.core.windows.net");
            }
        }
        if (isAwsS3() && !isPresent(properties.endpoint())) {
            // No override to apply — aws-s3 needs none — but a blank endpoint is the one way this provider
            // fails without saying so: the default is the global endpoint, so both SigV4 and STS sign for
            // us-east-1 and a bucket anywhere else rejects every request as a signature mismatch.
            log.warn("aws-s3 without blob.endpoint set; requests will be signed for us-east-1. Set "
                    + "blob.endpoint (BLOB_ENDPOINT) to the bucket's regional endpoint, "
                    + "https://s3.<region>.amazonaws.com");
        }
        return overrides;
    }

    private boolean isAzureBlob() {
        return "azureblob".equalsIgnoreCase(properties.provider());
    }

    private boolean isAwsS3() {
        return "aws-s3".equalsIgnoreCase(properties.provider());
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return isPresent(value) ? value : null;
    }

    @PreDestroy
    void shutdown() {
        BlobStoreContext local = context;
        if (local != null) {
            try {
                local.close();
            } catch (Exception ex) {
                log.debug("Error closing blob store context", ex);
            }
        }
        // The provider outlives a failed context build, so it is closed here regardless of whether one
        // was ever produced — an AWS workload-identity provider holds an STS client nothing else releases.
        if (credentials instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ex) {
                log.debug("Error closing blob credential provider", ex);
            }
        }
    }
}
