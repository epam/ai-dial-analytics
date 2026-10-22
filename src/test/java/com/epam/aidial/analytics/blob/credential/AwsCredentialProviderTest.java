package com.epam.aidial.analytics.blob.credential;

import org.jclouds.aws.domain.SessionCredentials;
import org.jclouds.domain.Credentials;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AwsCredentialProvider}: the shared-key-vs-workload-identity decision, the mapping
 * from SDK credentials onto the jclouds credential types the S3 SigV4 signer understands, and the region
 * derivation that keeps the STS call and the S3 signature on the same region.
 *
 * <p>No AWS is contacted and no environment variable is read: the workload-identity cases go through the
 * package-private constructor, which takes an already-built credentials provider.
 */
class AwsCredentialProviderTest {

    private static final String ACCESS_KEY = "AKIAEXAMPLEKEYID";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/EXAMPLEKEY";

    @Test
    void keyPairYieldsPlainCredentials() {
        AwsCredentialProvider provider = new AwsCredentialProvider(ACCESS_KEY, SECRET_KEY, null);

        Credentials credentials = provider.getCredentials();

        assertThat(credentials.identity).isEqualTo(ACCESS_KEY);
        assertThat(credentials.credential).isEqualTo(SECRET_KEY);
        // A shared key signs without a session token, so it must NOT arrive as SessionCredentials — the
        // signer would then emit an X-Amz-Security-Token header S3 rejects.
        assertThat(credentials).isNotInstanceOf(SessionCredentials.class);
    }

    @Test
    void workloadIdentityYieldsSessionCredentials() {
        Instant expiry = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        AwsCredentialsProvider stub = () -> AwsSessionCredentials.builder()
                .accessKeyId("ASIAEXAMPLETEMPID")
                .secretAccessKey("temp-secret")
                .sessionToken("temp-session-token")
                .expirationTime(expiry)
                .build();

        Credentials credentials = new AwsCredentialProvider(stub).getCredentials();

        assertThat(credentials).isInstanceOf(SessionCredentials.class);
        SessionCredentials session = (SessionCredentials) credentials;
        assertThat(session.getAccessKeyId()).isEqualTo("ASIAEXAMPLETEMPID");
        assertThat(session.getSecretAccessKey()).isEqualTo("temp-secret");
        assertThat(session.getSessionToken()).isEqualTo("temp-session-token");
        assertThat(session.getExpiration().get()).isEqualTo(Date.from(expiry));
        // SigV4 signs with the inherited identity/credential pair, not with the accessKeyId accessor, so a
        // builder that only filled the AWS-specific fields would sign with a null key.
        assertThat(session.identity).isEqualTo("ASIAEXAMPLETEMPID");
        assertThat(session.credential).isEqualTo("temp-secret");
    }

    @Test
    void sessionlessResolutionDegradesToPlainCredentials() {
        AwsCredentialsProvider stub = () -> AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY);

        Credentials credentials = new AwsCredentialProvider(stub).getCredentials();

        assertThat(credentials).isNotInstanceOf(SessionCredentials.class);
        assertThat(credentials.identity).isEqualTo(ACCESS_KEY);
    }

    @Test
    void credentialsAreResolvedPerCallSoRotationIsVisible() {
        String[] token = {"first"};
        AwsCredentialsProvider rotating = () -> AwsSessionCredentials.create("id", "secret", token[0]);
        AwsCredentialProvider provider = new AwsCredentialProvider(rotating);

        assertThat(((SessionCredentials) provider.getCredentials()).getSessionToken()).isEqualTo("first");
        token[0] = "second";
        assertThat(((SessionCredentials) provider.getCredentials()).getSessionToken()).isEqualTo("second");
    }

    @Test
    void regionComesFromTheRegionalEndpoint() {
        assertThat(AwsCredentialProvider.stsRegion("https://s3.eu-central-1.amazonaws.com")).isEqualTo("eu-central-1");
        assertThat(AwsCredentialProvider.stsRegion("https://example-bucket.s3.us-east-1.amazonaws.com"))
                .isEqualTo("us-east-1");
    }

    @Test
    void blankEndpointFallsBackToTheProviderDefaultRegion() {
        // The aws-s3 default endpoint is the global one, which SigV4 signs for us-east-1; STS must agree.
        assertThat(AwsCredentialProvider.stsRegion(null)).isEqualTo("us-east-1");
        assertThat(AwsCredentialProvider.stsRegion("  ")).isEqualTo("us-east-1");
        assertThat(AwsCredentialProvider.stsRegion("https://s3.amazonaws.com")).isEqualTo("us-east-1");
    }

    @Test
    void unknownHostStillResolvesToDefaultRegion() {
        // parseRegionName has no failure mode, which is why there is no failure branch in the provider.
        assertThat(AwsCredentialProvider.stsRegion("http://127.0.0.1:9000")).isEqualTo("us-east-1");
    }

    @Test
    void unreadableEndpointStillResolvesToDefaultRegion() {
        // Each of these reaches parseRegionName with a null host or fails URI.create outright, and would
        // otherwise abort the sync cycle with an NPE naming neither BLOB_ENDPOINT nor a region. jclouds
        // rejects such a value on its own terms; deriving a region must not be what fails first.
        assertThat(AwsCredentialProvider.stsRegion("s3.eu-central-1.amazonaws.com")).isEqualTo("us-east-1");
        assertThat(AwsCredentialProvider.stsRegion("https://buck_et.s3.amazonaws.com")).isEqualTo("us-east-1");
        assertThat(AwsCredentialProvider.stsRegion("minio.svc:9000")).isEqualTo("us-east-1");
        assertThat(AwsCredentialProvider.stsRegion("not a uri")).isEqualTo("us-east-1");
    }

    @Test
    void surroundingWhitespaceDoesNotDefeatTheDerivation() {
        // application.yml binds blob.endpoint verbatim; BlobProperties has no compact constructor to trim it.
        assertThat(AwsCredentialProvider.stsRegion(" https://s3.eu-central-1.amazonaws.com ")).isEqualTo("eu-central-1");
    }
}
