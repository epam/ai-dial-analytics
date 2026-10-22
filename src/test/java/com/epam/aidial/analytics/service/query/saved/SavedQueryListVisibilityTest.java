package com.epam.aidial.analytics.service.query.saved;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which rows the list endpoint may return, isolated from the database.
 *
 * <p>The rule under test is that the owner filter is dropped <strong>only</strong> because security is
 * disabled — never merely because the author came back {@code null}. Those are different facts: in
 * {@code oidc} mode a token carrying no value for the configured principal claim authenticates with no
 * name, and conflating the two would hand that caller everyone else's {@code personal} saved queries.
 */
@ExtendWith(MockitoExtension.class)
class SavedQueryListVisibilityTest {

    @Mock
    private SavedQueryRepository repository;
    @Mock
    private SavedQueryAccess access;
    @Mock
    private SavedQueryBodyValidator validator;
    @Mock
    private SavedQueryBodyResolver resolver;
    @Mock
    private SavedQueryJson json;

    @InjectMocks
    private SavedQueryService service;

    @Test
    void filtersPersonalRowsByTheAuthenticatedOwner() {
        when(access.currentAuthor()).thenReturn("alice");
        when(repository.findByScopeAndOwnerIdOrderByUpdatedAtDesc(SavedQueryScope.PERSONAL, "alice"))
                .thenReturn(List.of(new SavedQueryEntity()));

        assertThat(service.list(SavedQueryScope.PERSONAL)).hasSize(1);
        verify(repository, never()).findByScopeOrderByUpdatedAtDesc(any());
    }

    @Test
    void returnsNoPersonalRowsWhenSecurityIsOnButThePrincipalDoesNotResolve() {
        // A JWT missing the configured principal claim is still an authenticated caller. It owns no
        // addressable row, so it sees none — the same answer findVisible gives it for a single row.
        when(access.currentAuthor()).thenReturn(null);
        when(access.isSecurityEnabled()).thenReturn(true);

        assertThat(service.list(SavedQueryScope.PERSONAL)).isEmpty();
        verify(repository, never()).findByScopeOrderByUpdatedAtDesc(any());
        verify(repository, never()).findByScopeAndOwnerIdOrderByUpdatedAtDesc(any(), anyString());
    }

    @Test
    void listsEveryPersonalRowOnlyWhenSecurityIsDisabled() {
        when(access.currentAuthor()).thenReturn(null);
        when(access.isSecurityEnabled()).thenReturn(false);
        when(repository.findByScopeOrderByUpdatedAtDesc(SavedQueryScope.PERSONAL))
                .thenReturn(List.of(new SavedQueryEntity(), new SavedQueryEntity()));

        assertThat(service.list(SavedQueryScope.PERSONAL)).hasSize(2);
    }

    @Test
    void commonScopeHasNoOwnerTermEvenForAnIdentifiedCaller() {
        when(repository.findByScopeOrderByUpdatedAtDesc(SavedQueryScope.COMMON))
                .thenReturn(List.of(new SavedQueryEntity()));

        assertThat(service.list(SavedQueryScope.COMMON)).hasSize(1);
        verify(repository, never()).findByScopeAndOwnerIdOrderByUpdatedAtDesc(any(), anyString());
    }
}
