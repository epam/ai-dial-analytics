package com.epam.aidial.analytics.service.security;

import com.epam.aidial.analytics.web.security.Privilege;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for {@link SystemIdentity}.
 *
 * <p>The restoration cases matter more than the elevation one. Spring's scheduler pool is shared with the
 * blob poller and the data-sync job, and the security context is thread-local — a leaked elevated context
 * would silently grant whatever ran next on that thread the ability to read sensitive columns, with nothing
 * to indicate it had happened.
 */
class SystemIdentityTest {

    private final SystemIdentity systemIdentity = new SystemIdentity();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static Authentication caller() {
        return new UsernamePasswordAuthenticationToken(
                "someone@example.com", null, List.of(new SimpleGrantedAuthority("PRIVILEGE_TABLE_READ")));
    }

    @Test
    void workRunsAsSystemPrincipalHoldingExactlyTwoReadPrivileges() {
        final var seen = new java.util.concurrent.atomic.AtomicReference<Authentication>();

        systemIdentity.runAsSystem(() -> seen.set(SecurityContextHolder.getContext().getAuthentication()));

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().getName()).isEqualTo(SystemIdentity.SYSTEM_PRINCIPAL);
        // Exactly what a scheduled refresh over a sensitive column needs, and nothing more — closed under
        // the implication DAG, so the top rung confers the rungs below it exactly as it does for any other
        // holder. The narrowness is the point: before privileges existed the narrowest grantable thing was
        // full administrative authority, which had to be justified in prose rather than by construction.
        assertThat(seen.get().getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("PRIVILEGE_TABLE_SENSITIVE", "PRIVILEGE_TABLE_READ",
                        "PRIVILEGE_TABLE_GET", "PRIVILEGE_TABLE_LIST");
    }

    @Test
    void theSystemPrincipalHoldsNoAdministrativePrivilege() {
        final var seen = new java.util.concurrent.atomic.AtomicReference<Authentication>();

        systemIdentity.runAsSystem(() -> seen.set(SecurityContextHolder.getContext().getAuthentication()));

        assertThat(SystemIdentity.SYSTEM_PRIVILEGES)
                .doesNotContain(Privilege.TABLE_GRANT, Privilege.TABLE_CREATE,
                        Privilege.TABLE_DELETE, Privilege.REGISTRY_ADMIN);
        assertThat(seen.get().getAuthorities()).hasSize(SystemIdentity.SYSTEM_PRIVILEGES.size());
    }

    @Test
    void emptyContextIsRestoredAfterwards() {
        systemIdentity.runAsSystem(() -> { });

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void callersContextIsRestoredAfterwards() {
        final SecurityContext original = SecurityContextHolder.createEmptyContext();
        original.setAuthentication(caller());
        SecurityContextHolder.setContext(original);

        systemIdentity.runAsSystem(() -> { });

        assertThat(SecurityContextHolder.getContext()).isSameAs(original);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                .isEqualTo("someone@example.com");
    }

    @Test
    void contextIsRestoredEvenWhenTheWorkThrows() {
        final SecurityContext original = SecurityContextHolder.createEmptyContext();
        original.setAuthentication(caller());
        SecurityContextHolder.setContext(original);

        // The failure path is the one that matters: a refresh that throws must not leave the thread elevated
        // for the blob poller or data-sync job that shares it.
        assertThatThrownBy(() -> systemIdentity.runAsSystem(() -> {
            throw new IllegalStateException("refresh blew up");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(SecurityContextHolder.getContext()).isSameAs(original);
    }

    @Test
    void elevatedContextDoesNotLeakIntoCallersOwnContextObject() {
        final SecurityContext original = SecurityContextHolder.createEmptyContext();
        original.setAuthentication(caller());
        SecurityContextHolder.setContext(original);

        systemIdentity.runAsSystem(() -> { });

        // The elevation uses a fresh context rather than mutating the caller's, so the caller's object is
        // untouched even if something else still holds a reference to it.
        assertThat(original.getAuthentication().getName()).isEqualTo("someone@example.com");
    }
}
