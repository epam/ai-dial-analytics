package com.epam.aidial.analytics.blob.credential;

import lombok.extern.slf4j.Slf4j;
import org.jclouds.aws.domain.SessionCredentials;
import org.jclouds.domain.Credentials;
import org.jclouds.s3.filters.AwsHostNameUtils;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsWebIdentityTokenFileCredentialsProvider;
import software.amazon.awssdk.utils.SdkAutoCloseable;

import java.net.URI;
import java.util.Date;

/**
 * Resolves AWS S3 credentials, mirroring {@link AzureCredentialProvider}'s shape: a shared access-key
 * pair when both {@code identity} and {@code credential} are configured, and otherwise <em>workload
 * identity</em> — the EKS IRSA path, where the pod carries no secret at all.
 *
 * <p>Workload-identity mode resolves through
 * {@link StsWebIdentityTokenFileCredentialsProvider}, which exchanges the projected service-account
 * token named by {@code AWS_WEB_IDENTITY_TOKEN_FILE} for temporary credentials of the role named by
 * {@code AWS_ROLE_ARN} — the two variables the EKS pod-identity webhook injects from the service
 * account's {@code eks.amazonaws.com/role-arn} annotation. Both are read on <em>every</em> refresh, so a
 * re-projected token needs no restart, and the SDK renews well before expiry on the calling thread (no
 * background refresher, deliberately — see {@link #close()}).
 *
 * <p>Deliberately <strong>only</strong> web identity, not the SDK's default credential chain: that chain
 * falls through to the instance metadata service, so a pod that lost its annotation would silently read
 * the bucket with the EC2 <em>node</em> role instead of failing. For the same reason the STS client is
 * given {@link AnonymousCredentialsProvider} — {@code AssumeRoleWithWebIdentity} is an unauthenticated
 * operation, so the client needs no credentials and then cannot reach the metadata service for any
 * reason. It is also why this class owns the {@link StsClient}: the provider exposes no {@code create()},
 * and its superclass rejects a null client, so no path lets the SDK build one and pick its own region.
 *
 * <p>Temporary credentials reach jclouds as {@link SessionCredentials}, which the S3 SigV4 signer
 * recognises and turns into an {@code X-Amz-Security-Token} header; the plain {@link Credentials} a
 * shared key produces carries no such header. jclouds consults the supplier per request, so nothing is
 * cached here on top of the SDK's own caching.
 *
 * <p>Never log the resolved value: {@link SessionCredentials#toString()} prints the access key id and the
 * session token verbatim.
 */
@Slf4j
public class AwsCredentialProvider implements CredentialProvider, AutoCloseable {

    /**
     * The {@code aws-s3} provider's own default endpoint, used to derive the STS region when no endpoint
     * is configured. Keeping the fallback here — rather than letting the SDK's region chain decide —
     * is what guarantees the STS region is always the region the S3 signer signs for.
     */
    private static final String DEFAULT_S3_ENDPOINT = "https://s3.amazonaws.com";

    private final Credentials sharedKeyCredentials;
    private final AwsCredentialsProvider workloadIdentity;
    /** The STS client this provider owns and must close; {@code null} in shared-key and test modes. */
    private final SdkAutoCloseable ownedStsClient;

    /**
     * @param identity access key id, or {@code null} to select workload identity
     * @param secret   secret access key, or {@code null} to select workload identity
     * @param endpoint the configured S3 endpoint override; blank falls back to the provider default
     */
    public AwsCredentialProvider(String identity, String secret, String endpoint) {
        if (identity != null && secret != null) {
            this.sharedKeyCredentials = new Credentials(identity, secret);
            this.workloadIdentity = null;
            this.ownedStsClient = null;
        } else {
            this.sharedKeyCredentials = null;
            String region = stsRegion(endpoint);
            StsClient stsClient = StsClient.builder()
                    .region(Region.of(region))
                    .credentialsProvider(AnonymousCredentialsProvider.create())
                    .build();
            this.ownedStsClient = stsClient;
            // A missing AWS_ROLE_ARN / AWS_WEB_IDENTITY_TOKEN_FILE is captured here and rethrown from every
            // resolveCredentials(), so it surfaces as a failed sync cycle that the next one retries rather
            // than as a failed startup — the blob context is built lazily for exactly that reason.
            this.workloadIdentity = StsWebIdentityTokenFileCredentialsProvider.builder()
                    .stsClient(stsClient)
                    .build();
            log.info("aws-s3 blob access uses workload identity (AssumeRoleWithWebIdentity); STS region {}", region);
        }
    }

    /**
     * Test seam: takes an already-built credentials provider, so a test can exercise the mapping to
     * jclouds credentials without AWS, an STS round trip or any environment variable. The region is not a
     * parameter because it only ever reaches the {@link StsClient} this constructor does not build; the
     * derivation itself is tested through {@link #stsRegion(String)}.
     */
    AwsCredentialProvider(AwsCredentialsProvider workloadIdentity) {
        this.sharedKeyCredentials = null;
        this.workloadIdentity = workloadIdentity;
        this.ownedStsClient = null;
    }

    @Override
    public Credentials getCredentials() {
        if (sharedKeyCredentials != null) {
            return sharedKeyCredentials;
        }
        // Cached and refreshed inside the SDK provider, synchronously on this thread: one caller per
        // renewal window pays the round trip, and jclouds only ever sees the resolved value.
        AwsCredentials resolved = workloadIdentity.resolveCredentials();
        if (!(resolved instanceof AwsSessionCredentials session)) {
            // Not reachable through AssumeRoleWithWebIdentity, which always issues a session; keeping the
            // fallback means a differently-configured provider degrades to plain credentials rather than
            // to a ClassCastException at the first list call.
            return new Credentials(resolved.accessKeyId(), resolved.secretAccessKey());
        }
        return SessionCredentials.builder()
                .accessKeyId(session.accessKeyId())
                .secretAccessKey(session.secretAccessKey())
                .sessionToken(session.sessionToken())
                .expiration(session.expirationTime().map(Date::from).orElse(null))
                .build();
    }

    /**
     * The region STS is called in, derived from the <em>effective</em> S3 endpoint through the same
     * helper the SigV4 signer uses, so the two can never disagree. It never fails — an unrecognised host
     * yields {@code us-east-1} — which is why there is no failure branch here and the SDK's own region
     * chain (which probes the instance metadata service before giving up) is never consulted.
     *
     * @param endpoint the configured endpoint override, or {@code null}/blank for the provider default
     * @return the region name to sign STS requests for
     */
    static String stsRegion(String endpoint) {
        String effective = endpoint == null || endpoint.isBlank() ? DEFAULT_S3_ENDPOINT : endpoint.trim();
        URI uri;
        try {
            uri = URI.create(effective);
        } catch (IllegalArgumentException ex) {
            return defaultRegion(effective, "it is not a valid URI");
        }
        if (uri.getHost() == null) {
            // No scheme, or a host java.net.URI will not parse (an underscore, say). jclouds will reject
            // the same value later; deriving a region from it here must not be what fails first, or the
            // sync cycle reports a NullPointerException with nothing in it naming blob.endpoint.
            return defaultRegion(effective, "it has no parseable host — a scheme such as https:// may be missing");
        }
        return AwsHostNameUtils.parseRegionName(uri.getHost(), AwsHostNameUtils.parseServiceName(uri));
    }

    /**
     * The fallback region for an endpoint the rule cannot read, logged loudly because the value is almost
     * certainly a typo: jclouds signs the data plane for a region derived the same way, so whatever failed
     * here fails there too, and a bucket outside {@code us-east-1} then rejects every request.
     */
    private static String defaultRegion(String endpoint, String reason) {
        String fallback = AwsHostNameUtils.parseRegionName(URI.create(DEFAULT_S3_ENDPOINT).getHost(), "s3");
        log.warn("Cannot derive a region from blob.endpoint (BLOB_ENDPOINT) '{}': {}. Signing S3 and STS for {} "
                + "instead, which a bucket in any other region rejects.", endpoint, reason, fallback);
        return fallback;
    }

    /**
     * Closes the SDK credentials provider and the STS client this instance owns. Both are needed: the
     * provider's inherited {@code close()} releases a cache this subclass never uses and leaves the client
     * open. This is also why asynchronous credential updates stay off — a prefetch thread would be started
     * by a cache that nothing here ever closes, and would outlive shutdown.
     */
    @Override
    public void close() {
        closeQuietly(workloadIdentity);
        closeQuietly(ownedStsClient);
    }

    private static void closeQuietly(Object closeable) {
        if (closeable instanceof AutoCloseable autoCloseable) {
            try {
                autoCloseable.close();
            } catch (Exception ex) {
                log.debug("Error closing AWS credential resource", ex);
            }
        }
    }
}
