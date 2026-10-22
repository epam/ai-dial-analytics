package com.epam.aidial.analytics.blob.credential;

import org.jclouds.domain.Credentials;

/**
 * Supplies jclouds {@link Credentials} for a blob provider. The provider is consulted per request
 * via {@code ContextBuilder.credentialsSupplier}, so implementations may return refreshable
 * credentials (e.g. short-lived Azure AD tokens).
 */
public interface CredentialProvider {

    /**
     * @return the credentials to use for the next blob operation
     */
    Credentials getCredentials();
}
