package com.epam.aidial.analytics.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Typed binding for the blob source ({@code blob.*}). Values are env-driven via {@code application.yml}.
 * Backs the {@code data-sync} ingest path. Access is provider-agnostic via jclouds: {@code provider}
 * selects the backend ({@code azureblob} or {@code aws-s3} for real storage, {@code filesystem} in tests),
 * {@code identity} and {@code credential} are the account name and key (or the AWS access-key pair), and
 * {@code bucket} is the container. Leaving the pair blank selects the provider's keyless mode — Azure AD
 * managed identity, or AWS workload identity (EKS IRSA).
 *
 * @param provider   jclouds blob provider (e.g. {@code azureblob}, {@code aws-s3}, {@code filesystem})
 * @param identity   storage account name or AWS access key id; blank selects the keyless mode
 * @param credential storage account key or AWS secret access key, supplied from the secret store; blank
 *                   selects the keyless mode
 * @param bucket     container or bucket name holding the logs
 * @param endpoint   optional endpoint override (blank uses the provider default); for {@code aws-s3} it
 *                   also fixes the region SigV4 and STS sign for
 * @param overrides  optional jclouds context overrides (e.g. {@code jclouds.filesystem.basedir})
 */
@ConfigurationProperties(prefix = "blob")
public record BlobProperties(
        String provider,
        String identity,
        String credential,
        String bucket,
        String endpoint,
        Map<String, String> overrides) {

    /**
     * Redacts {@code credential} and {@code overrides} (which may carry provider secrets) so an
     * accidental log or actuator dump never leaks them.
     */
    @Override
    public String toString() {
        return "BlobProperties[provider=" + provider + ", identity=" + identity
                + ", credential=***, bucket=" + bucket + ", endpoint=" + endpoint + ", overrides=***]";
    }
}
