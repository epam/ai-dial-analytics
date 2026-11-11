package com.epam.aidial.analytics.service.setting;

import com.epam.aidial.analytics.exception.SettingNotFoundException;
import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import com.epam.aidial.analytics.web.security.SecurityModeResolver;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Reads and replaces the sessions-source setting (see the {@code instance-settings} capability).
 *
 * <p>A replace validates the body against the caller's view of the catalog, then loads-or-creates the row and
 * saves it whole, in one transaction, through the persistence context so Envers records the revision. Two first
 * writes racing to insert the row cannot both succeed: the loser fails on the primary key, and is retried once,
 * when it finds the row and updates it — last writer wins, and every read sees one complete body.
 */
@Service
public class SessionsSourceSettingService {

    private static final String NAME = InstanceSettingName.SESSIONS_SOURCE.wireName();

    private final InstanceSettingRepository repository;
    private final SessionsSourceValidator validator;
    private final SecurityClaimsExtractor claims;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readTransactions;
    private final boolean securityEnabled;

    public SessionsSourceSettingService(
            InstanceSettingRepository repository,
            SessionsSourceValidator validator,
            SecurityClaimsExtractor claims,
            JsonMapper jsonMapper,
            PlatformTransactionManager transactionManager,
            SecurityModeResolver securityMode) {
        this.repository = repository;
        this.validator = validator;
        this.claims = claims;
        this.jsonMapper = jsonMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readTransactions = new TransactionTemplate(transactionManager);
        this.readTransactions.setReadOnly(true);
        this.securityEnabled = securityMode.isEnforced();
    }

    /** The stored setting; {@link SettingNotFoundException} when it has never been written. */
    public StoredSessionsSourceSetting get() {
        return readTransactions.execute(status -> repository.findById(NAME)
                .map(this::toStored)
                .orElseThrow(() -> new SettingNotFoundException("The sessions-source setting is not configured")));
    }

    /** Validates and stores {@code setting} whole, returning it as stored. */
    public StoredSessionsSourceSetting replace(SessionsSourceSetting setting) {
        final String value = write(setting);
        final String author = currentAuthor();
        try {
            return transactions.execute(status -> store(setting, value, author));
        } catch (DataIntegrityViolationException firstInsertLost) {
            return transactions.execute(status -> store(setting, value, author));
        }
    }

    private StoredSessionsSourceSetting store(SessionsSourceSetting setting, String value, String author) {
        // Validated inside the transaction, so the catalog read and the write see one snapshot.
        validator.validate(setting);
        final InstanceSettingEntity entity = repository.findById(NAME).orElseGet(() -> {
            final InstanceSettingEntity created = new InstanceSettingEntity();
            created.setName(NAME);
            return created;
        });
        entity.setValue(value);
        // PostgreSQL keeps microseconds; truncating here makes the response equal to every later read.
        entity.setUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        entity.setUpdatedBy(author);
        return toStored(repository.saveAndFlush(entity));
    }

    private String currentAuthor() {
        if (!securityEnabled) {
            return null;
        }
        final String email = claims.getEmail();
        return email != null && !email.isBlank() ? email : claims.getAuthor();
    }

    private StoredSessionsSourceSetting toStored(InstanceSettingEntity entity) {
        try {
            final SessionsSourceSetting setting = jsonMapper.readValue(entity.getValue(), SessionsSourceSetting.class);
            return new StoredSessionsSourceSetting(setting, entity.getUpdatedAt(), entity.getUpdatedBy());
        } catch (JacksonException e) {
            // Written by this same mapper, so an unreadable row is a server fault, not a client error.
            throw new IllegalStateException("The stored sessions-source setting cannot be read", e);
        }
    }

    private String write(SessionsSourceSetting setting) {
        try {
            return jsonMapper.writeValueAsString(setting);
        } catch (JacksonException e) {
            throw new IllegalStateException("The sessions-source setting cannot be serialized", e);
        }
    }
}
