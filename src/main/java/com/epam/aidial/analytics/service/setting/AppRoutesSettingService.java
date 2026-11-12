package com.epam.aidial.analytics.service.setting;

import com.epam.aidial.analytics.exception.AppRouteInUseException;
import com.epam.aidial.analytics.exception.SettingNotFoundException;
import com.epam.aidial.analytics.service.enrichment.model.TransformPreset;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.service.pipeline.PipelineSpecCodec;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import com.epam.aidial.analytics.web.security.SecurityModeResolver;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads and replaces the app-routes setting, and answers the arm gate's question of whether a route is allowed (see
 * the {@code instance-settings} and {@code enrichment-registry} capabilities).
 *
 * <p>The invariant is that every <b>enabled</b> {@code dial_app_route} pipeline's pair is listed. Two writes could
 * break it, and each is checked here under one transaction-scoped advisory lock: arming a pipeline
 * ({@link #allows}) and replacing the setting ({@link #replace}). Holding the lock to commit serializes the
 * two, so neither check can pass against the other's old state. Because the invariant always holds, the data plane
 * and the runner do not re-check it.
 */
@Service
public class AppRoutesSettingService {

    private static final String NAME = InstanceSettingName.APP_ROUTES.wireName();

    /**
     * The advisory lock both writes take. It is namespaced the same way the relayout locks are, so the key space is
     * shared without colliding.
     */
    private static final String LOCK_SQL = "SELECT pg_advisory_xact_lock(hashtext('instance_setting:" + NAME + "'))";

    private final InstanceSettingRepository repository;
    private final PipelineRepository pipelines;
    private final PipelineSpecCodec specCodec;
    private final SecurityClaimsExtractor claims;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final boolean securityEnabled;

    public AppRoutesSettingService(
            InstanceSettingRepository repository,
            PipelineRepository pipelines,
            PipelineSpecCodec specCodec,
            SecurityClaimsExtractor claims,
            JsonMapper jsonMapper,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            SecurityModeResolver securityMode) {
        this.repository = repository;
        this.pipelines = pipelines;
        this.specCodec = specCodec;
        this.claims = claims;
        this.jsonMapper = jsonMapper;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.securityEnabled = securityMode.isEnforced();
    }

    /** The stored setting; {@link SettingNotFoundException} when it has never been written. */
    public StoredAppRoutesSetting get() {
        return repository.findById(NAME)
                .map(this::toStored)
                .orElseThrow(() -> new SettingNotFoundException("The app-routes setting is not configured"));
    }

    /** Validates and stores {@code setting} whole, refusing it when it drops a pair an enabled pipeline calls. */
    public StoredAppRoutesSetting replace(AppRoutesSetting setting) {
        AppRoutesValidator.validate(setting);
        final String value = write(setting);
        final String author = securityEnabled ? claims.getAuthor() : null;
        try {
            return transactions.execute(status -> store(setting, value, author));
        } catch (DataIntegrityViolationException firstInsertLost) {
            return transactions.execute(status -> store(setting, value, author));
        }
    }

    /**
     * Whether the setting lists {@code (deployment, route)}; an absent setting lists nothing. Called by the arm gate
     * inside the arming write's transaction, so the lock taken here is held until that write commits. Called from
     * a preview, the lock is released at the end of that read.
     */
    public boolean allows(String deployment, String route) {
        lock();
        return repository.findById(NAME)
                .map(this::toStored)
                .map(stored -> stored.setting().allows(deployment, route))
                .orElse(false);
    }

    private StoredAppRoutesSetting store(AppRoutesSetting setting, String value, String author) {
        lock();
        requireNoEnabledPipelineLosesItsRoute(setting);
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

    private void requireNoEnabledPipelineLosesItsRoute(AppRoutesSetting setting) {
        final List<String> losing = new ArrayList<>();
        for (PipelineEntity pipeline : pipelines.list(null, PipelineKind.ENRICH, true, null)) {
            final EnrichSpec.TransformSpec transform = Optional.ofNullable(specCodec.readEnrich(pipeline))
                    .map(EnrichSpec::getTransform)
                    .orElse(null);
            if (transform == null || transform.getPreset() != TransformPreset.DIAL_APP_ROUTE) {
                continue;
            }
            if (!setting.allows(transform.getModel(), transform.getRoute())) {
                losing.add(pipeline.getName() + " (" + transform.getModel() + ", " + transform.getRoute() + ")");
            }
        }
        if (!losing.isEmpty()) {
            throw new AppRouteInUseException("the new '" + NAME + "' setting drops a route that an enabled pipeline"
                    + " calls: " + String.join(", ", losing) + "; disable the pipeline first");
        }
    }

    private void lock() {
        jdbc.execute(LOCK_SQL);
    }

    private StoredAppRoutesSetting toStored(InstanceSettingEntity entity) {
        try {
            final AppRoutesSetting setting = jsonMapper.readValue(entity.getValue(), AppRoutesSetting.class);
            return new StoredAppRoutesSetting(setting, entity.getUpdatedAt(), entity.getUpdatedBy());
        } catch (JacksonException e) {
            // Written by this same mapper, so an unreadable row is a server fault, not a client error.
            throw new IllegalStateException("The stored app-routes setting cannot be read", e);
        }
    }

    private String write(AppRoutesSetting setting) {
        try {
            return jsonMapper.writeValueAsString(setting);
        } catch (JacksonException e) {
            throw new IllegalStateException("The app-routes setting cannot be serialized", e);
        }
    }
}
