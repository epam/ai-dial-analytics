package com.epam.aidial.analytics.web.mapper.table;

import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.security.SensitiveColumnAccess;
import com.epam.aidial.analytics.service.table.Cardinality;
import com.epam.aidial.analytics.service.table.Change;
import com.epam.aidial.analytics.service.table.ColumnInput;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.CreateTableInput;
import com.epam.aidial.analytics.service.table.DefineSchemaInput;
import com.epam.aidial.analytics.service.table.PatchInput;
import com.epam.aidial.analytics.service.table.PatchInput.Rename;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableSummary;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.TableView;
import com.epam.aidial.analytics.service.table.TableWriteMode;
import com.epam.aidial.analytics.service.table.TagOrdering;
import com.epam.aidial.analytics.service.table.TtlInput;
import com.epam.aidial.analytics.service.table.grant.EffectivePermissions;
import com.epam.aidial.analytics.service.table.grant.TableCallerPermissions;
import com.epam.aidial.analytics.service.table.relayout.TableLayouts;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicyCodec;
import com.epam.aidial.analytics.web.dto.table.CreateTableRequest;
import com.epam.aidial.analytics.web.dto.table.CreateTableRequest.ColumnRequest;
import com.epam.aidial.analytics.web.dto.table.CreateTableRequest.PartitionByRequest;
import com.epam.aidial.analytics.web.dto.table.DefineSchemaRequest;
import com.epam.aidial.analytics.web.dto.table.PatchSchemaRequest;
import com.epam.aidial.analytics.web.dto.table.TableDto;
import com.epam.aidial.analytics.web.dto.table.TableDto.ColumnDto;
import com.epam.aidial.analytics.web.dto.table.TableDto.GrainDto;
import com.epam.aidial.analytics.web.dto.table.TableDto.PartitionByDto;
import com.epam.aidial.analytics.web.dto.table.TableDto.PermissionsDto;
import com.epam.aidial.analytics.web.dto.table.TableDto.TtlDto;
import com.epam.aidial.analytics.web.dto.table.TableListDto;
import com.epam.aidial.analytics.web.dto.table.TtlRequest;
import com.epam.aidial.analytics.web.dto.table.UpdateTableRequest;
import com.epam.aidial.analytics.web.dto.table.access.AccessDto;
import com.epam.aidial.analytics.web.dto.table.rowpolicy.RowPolicyDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps between table-management DTOs and the catalog domain. Hand-written (rather than MapStruct)
 * because create/patch requests flatten into the service-layer input records and the response carries
 * a {@link TableView} aggregate (definition + columns + grain).
 */
@Component
@RequiredArgsConstructor
public class TableMapper {

    private final TableCallerPermissions callerPermissions;
    private final SensitiveColumnAccess sensitiveAccess;
    private final RowPolicyCodec rowPolicyCodec;
    private final TableLayouts layouts;

    public CreateTableInput toCreateInput(CreateTableRequest request) {
        // Create is identity-only: reject any physical-schema field with a clear pointer to the schema
        // resource. Request bodies refuse undeclared fields, but only with a generic 400 naming the field;
        // these stay declared on the request so that a caller who sends them is told where they belong.
        if (request.columns() != null || request.orderingKey() != null || request.partitionBy() != null
                || request.grainKey() != null || request.cardinality() != null || request.ttl() != null) {
            throw new TableValidationException("columns, physical keys, and ttl are not accepted at create; "
                    + "define the physical schema via POST /v1/tables/{name}/schema");
        }
        return new CreateTableInput(request.name(), request.description(), request.type(),
                request.sourceTable(), request.write(), request.tagOrder());
    }

    /**
     * The {@code POST /v1/tables} body that recreates {@code definition}'s identity: the inverse of
     * {@link #toCreateInput}, for the catalog export. A source carries its <b>stored</b> write mode (not the
     * effective one); an enrichment carries none, because create refuses it for that kind.
     */
    public CreateTableRequest toCreateRequest(TableDefinitionEntity definition) {
        final boolean enrichment = definition.getType() == TableType.ENRICHMENT;
        return new CreateTableRequest(definition.getName(), definition.getDescription(), definition.getType(), null,
                enrichment ? null : definition.getWriteMode(), null, null, null,
                definition.getSourceTable(), null, null, null,
                definition.getTagOrder() == null ? null : List.copyOf(definition.getTagOrder()));
    }

    /**
     * The {@code POST /v1/tables/{name}/schema} body that recreates {@code definition}'s schema: the inverse
     * of {@link #toDefineInput}, for the catalog export.
     *
     * <p>Every stored column travels, provenance columns included and sensitive ones too, in stored order,
     * with {@code nullable}, {@code sensitive} and {@code heavy} written out — the request's defaults differ
     * by kind, so leaving them implicit could change a column. It is not filtered by the caller's clearance:
     * the export refuses a caller who may not see a sensitive column rather than dropping it here. Members
     * define-schema refuses for the kind are left out: {@code write} always, and for an enrichment its
     * {@code partition_by}, its {@code ordering_key} (the grain) and the scan-metadata pair. The enrichment's
     * grain column is not a mapped column, so it is not among the columns.
     */
    public DefineSchemaRequest toDefineSchemaRequest(TableDefinitionEntity definition, List<ColumnMappingEntity> columns) {
        final boolean enrichment = definition.getType() == TableType.ENRICHMENT;
        final List<ColumnRequest> declared = columns.stream()
                .sorted(Comparator.comparingInt(ColumnMappingEntity::getOrdinal))
                .map(col -> new ColumnRequest(col.getSourceName(), col.getName(), col.getType(), col.getElementType(),
                        col.getEnumValues() == null ? null : List.copyOf(col.getEnumValues()), col.isNullable(),
                        col.getDescription(), col.getDisplayName(), col.getTag(), col.isSensitive(), col.isHeavy()))
                .toList();
        final TtlInput ttl = definition.ttl();
        return new DefineSchemaRequest(
                declared,
                enrichment ? null : List.copyOf(definition.getOrderingKey()),
                enrichment || definition.getPartitionColumn() == null ? null
                        : new PartitionByRequest(definition.getPartitionColumn(), definition.getPartitionGranularity()),
                enrichment ? definition.getGrainKey() : null,
                null,
                enrichment ? null : definition.getIdentityColumn(),
                enrichment ? null : definition.getVersionColumn(),
                ttl == null ? null : new TtlRequest(ttl.column(), ttl.interval(), ttl.unit()),
                null);
    }

    /**
     * The metadata-update surface's one mapped member: the tag ordering, as a merge-patch
     * {@link Change}. Exists so that every mutating surface is translated <em>here</em> — which is what
     * keeps {@link #rejectImmutableWrite} applied to all three of them, rather than to the two that happen
     * to have a mapper method and by hand in the controller for the third.
     */
    public Change<List<String>> toTagOrderChange(UpdateTableRequest request) {
        rejectImmutableWrite(request.write());
        return change(request.tagOrder());
    }

    /**
     * Guards the immutability of the write discipline on every mutating surface. The facet selects the
     * ClickHouse engine, which is fixed by {@code CREATE TABLE} and cannot be {@code ALTER}ed, so it is
     * declared once at create; a caller who sends it later gets this clear error rather than a silently
     * ignored field (the same treatment the frozen physical keys get).
     */
    private static void rejectImmutableWrite(TableWriteMode write) {
        if (write != null) {
            throw new TableValidationException("write is immutable: it selects the ClickHouse engine, "
                    + "which is frozen at materialization; it is declared at POST /v1/tables");
        }
    }

    public DefineSchemaInput toDefineInput(DefineSchemaRequest request) {
        rejectImmutableWrite(request.write());
        final List<ColumnInput> columns = request.columns() == null ? null : request.columns().stream()
                .map(this::toColumnInput)
                .toList();
        // At define, an empty ttl object is meaningless (there is nothing to remove yet) — the member,
        // when present, must be the complete declaration.
        if (request.ttl() != null && request.ttl().isEmpty()) {
            throw new TableValidationException("ttl requires column, interval, and unit");
        }
        return new DefineSchemaInput(
                columns,
                request.orderingKey(),
                request.partitionBy() == null ? null : request.partitionBy().column(),
                request.partitionBy() == null ? null : request.partitionBy().granularity(),
                request.grainKey(),
                request.cardinality(),
                toTtlInput(request.ttl()),
                request.identityColumn(),
                request.versionColumn());
    }

    public PatchInput toPatchInput(PatchSchemaRequest request) {
        // PATCH is the live-schema surface: the physical keys are frozen after materialization, and tag
        // ordering is table metadata. Reject either with a clear pointer (mirrors the create side) rather
        // than silently ignoring it — this precedes the endpoint's materialization-state (409) check.
        rejectImmutableWrite(request.write());
        if (request.orderingKey() != null || request.partitionBy() != null || request.grainKey() != null
                || request.cardinality() != null) {
            throw new TableValidationException("physical keys are frozen after materialization and are not "
                    + "accepted on PATCH; they are set when the schema is defined via "
                    + "POST /v1/tables/{name}/schema");
        }
        if (request.tagOrder() != null) {
            throw new TableValidationException(
                    "tag_order is not accepted on PATCH; it is table metadata — set it via PUT /v1/tables/{name}");
        }
        // An enum column's domain is fixed for the life of the column: changing it is an ALTER MODIFY
        // COLUMN that rewrites the column and fails against any stored row outside the new domain.
        // Declared on the request only to be rejected here, with a message saying how a domain is widened,
        // rather than left to the generic unknown-field 400.
        if (request.update() != null
                && request.update().stream().anyMatch(u -> u.enumValues() != null)) {
            throw new TableValidationException("enum_values cannot be changed on an existing column; "
                    + "a domain is widened by dropping and re-adding the column, as a type change is");
        }
        final List<ColumnInput> add = request.add() == null ? null : request.add().stream()
                .map(this::toColumnInput)
                .toList();
        final List<Rename> rename = request.rename() == null ? null : request.rename().stream()
                .map(r -> new Rename(r.from(), r.to()))
                .toList();
        final List<PatchInput.ColumnMetadataUpdate> update = request.update() == null ? null
                : request.update().stream()
                        .map(u -> new PatchInput.ColumnMetadataUpdate(
                                u.name(),
                                change(u.tag()),
                                change(u.displayName()),
                                change(u.description()),
                                change(u.sensitive()),
                                change(u.heavy())))
                        .toList();
        return new PatchInput(add, request.drop(), rename, update, ttlChange(request.ttl()));
    }

    /** A request field maps to a "set" change when present (non-null) and "leave" when absent (null). */
    private static <T> Change<T> change(T value) {
        return value == null ? Change.leave() : Change.set(value);
    }

    /**
     * The PATCH {@code ttl} member's merge-patch: absent/null leaves the TTL unchanged, an empty
     * object removes it, a full object sets/replaces it (a partial object is rejected).
     */
    private Change<TtlInput> ttlChange(TtlRequest ttl) {
        if (ttl == null) {
            return Change.leave();
        }
        return ttl.isEmpty() ? Change.set(null) : Change.set(toTtlInput(ttl));
    }

    /** Maps a (present) TTL declaration, rejecting a partial object (some but not all fields). */
    private TtlInput toTtlInput(TtlRequest ttl) {
        if (ttl == null) {
            return null;
        }
        if (ttl.column() == null || ttl.interval() == null || ttl.unit() == null) {
            throw new TableValidationException("ttl requires column, interval, and unit");
        }
        return new TtlInput(ttl.column(), ttl.interval(), ttl.unit());
    }

    public TableDto toDto(TableView view) {
        return toDto(view, false, layouts.layout(view.definition()));
    }

    /**
     * The revision read's DTO: as {@link #toDto(TableView)}, additionally carrying the definition's {@code access}
     * and {@code row_policies} when {@code includeGrants} — the revision read's choice, made against the bar the
     * live grant reads set. Both are then always present, empty when nothing is granted, so their absence
     * means withheld and never none. It carries no {@code layout}: that is the table's current state, which the
     * audit trail does not record, so a past revision cannot report it.
     */
    public TableDto toDto(TableView view, boolean includeGrants) {
        return toDto(view, includeGrants, null);
    }

    private TableDto toDto(TableView view, boolean includeGrants, String layout) {
        final TableDefinitionEntity entity = view.definition();
        final List<String> tagOrder = entity.getTagOrder();
        // When an ordering is set, present the columns sorted by it (via the shared comparator, so the
        // read and discovery surfaces agree); otherwise keep declared (ordinal) order. Presentation
        // only — the stored column ordinals are untouched.
        // A sensitive column is shown only to a caller holding the clearance on this table, the same rule
        // the query surface applies. Every TableDto carrying columns is built here — get, the mutation
        // responses and the revision read — so this is the catalog read surface's single chokepoint, and a
        // revision is judged on the flags it carried then, since those are the flags on its snapshot rows.
        final boolean cleared = sensitiveAccess.maySeeSensitive(entity.getName());
        List<ColumnMappingEntity> ordered = view.columns().stream()
                .filter(col -> cleared || !col.isSensitive())
                .toList();
        if (tagOrder != null && !tagOrder.isEmpty()) {
            ordered = ordered.stream()
                    .sorted(TagOrdering.comparator(
                            tagOrder, ColumnMappingEntity::getTag, ColumnMappingEntity::getName))
                    .toList();
        }
        final List<ColumnDto> columns = ordered.stream()
                .map(col -> new ColumnDto(
                        col.getSourceName(), col.getName(), col.getType(), col.getElementType(),
                        col.getEnumValues(), col.isNullable(),
                        col.getDescription(), col.getDisplayName(), col.getTag(), col.isSensitive(),
                        col.isHeavy()))
                .toList();
        return new TableDto(
                entity.getName(),
                entity.getDescription(),
                entity.getType(),
                entity.effectiveWriteMode(),
                entity.getSourceTable(),
                entity.getStatus(),
                entity.isSystem(),
                entity.getIdentityColumn(),
                entity.getVersionColumn(),
                List.copyOf(entity.getOrderingKey()),
                partitionBy(entity),
                ttl(entity),
                grain(entity),
                tagOrder == null ? null : List.copyOf(tagOrder),
                columns,
                null,
                permissions(entity),
                includeGrants ? AccessDto.of(entity.getAccess()) : null,
                includeGrants ? rowPolicies(entity) : null,
                layout);
    }

    private Map<String, RowPolicyDto> rowPolicies(TableDefinitionEntity entity) {
        final Map<String, RowPolicyDto> policies = new LinkedHashMap<>();
        rowPolicyCodec.read(entity.getRowPolicies()).forEach((role, policy) -> policies.put(role, RowPolicyDto.of(policy)));
        return policies;
    }

    /** Lean list-item DTO: structural fields plus the exposed-column count (use single-get for columns). */
    public TableListDto toListDto(List<TableSummary> summaries) {
        return new TableListDto(summaries.stream().map(this::toListItemDto).toList());
    }

    private TableDto toListItemDto(TableSummary summary) {
        final TableDefinitionEntity entity = summary.definition();
        final List<String> tagOrder = entity.getTagOrder();
        return new TableDto(
                entity.getName(),
                entity.getDescription(),
                entity.getType(),
                entity.effectiveWriteMode(),
                entity.getSourceTable(),
                entity.getStatus(),
                entity.isSystem(),
                entity.getIdentityColumn(),
                entity.getVersionColumn(),
                List.copyOf(entity.getOrderingKey()),
                partitionBy(entity),
                ttl(entity),
                null,
                tagOrder == null ? null : List.copyOf(tagOrder),
                null,
                summary.columnCount(),
                permissions(entity),
                null,
                null,
                null);
    }

    /** The caller's effective permissions, resolved from the definition already in hand (no queries). */
    private PermissionsDto permissions(TableDefinitionEntity entity) {
        final EffectivePermissions effective = callerPermissions.resolve(entity);
        return new PermissionsDto(
                effective.get(), effective.read(),
                effective.sensitive(), effective.write(), effective.modify(), effective.rowsRestricted());
    }

    private ColumnInput toColumnInput(ColumnRequest col) {
        return new ColumnInput(
                col.sourceName(), col.name(), col.type(), col.elementType(), col.enumValues(), col.nullable(),
                col.description(), col.displayName(), col.tag(), Boolean.TRUE.equals(col.sensitive()),
                Boolean.TRUE.equals(col.heavy()));
    }

    /**
     * The table's declared time partitioning, for either kind, or null when it declares none.
     *
     * <p>Was source-only: an enrichment's pair was neither settable nor readable, so V38 left it unset on
     * {@code dial_usage_log_payload} on the grounds that a value there "would be stored and never read".
     * It is read now — an enrichment whose partition column matches its source's lets a keyed grain join
     * bound the enrichment side to the partitions that can hold the row, which is the difference between a
     * single-row body read costing megabytes and costing gigabytes. A value that changes how every query
     * over the entity is planned has to be visible on the definition that carries it; an operator asking
     * why a body read is slow must be able to see whether the alignment is declared.
     *
     * <p>Readable is not writable: an enrichment still cannot declare this through the API (422 from
     * {@code CatalogSchemaWriter}). The declaration asserts that both tables hold the same value for the
     * same grain key, which holds for a table populated from one record on both sides and not for one
     * populated by arbitrary row writes — and nothing at query time can catch a false claim.
     */
    private PartitionByDto partitionBy(TableDefinitionEntity entity) {
        if (entity.getPartitionColumn() == null) {
            return null;
        }
        return new PartitionByDto(entity.getPartitionColumn(), entity.getPartitionGranularity());
    }

    /**
     * The grain as the API surfaces it. {@code cardinality} is not stored: it has exactly one legal value,
     * which {@link com.epam.aidial.analytics.service.table.CatalogSchemaWriter} enforces on the way in, so
     * every row that has a grain key has the same cardinality. Emitting the constant here reproduces the
     * former column exactly while leaving nothing redundant in the catalog.
     */
    private GrainDto grain(TableDefinitionEntity entity) {
        return entity.getGrainKey() == null ? null
                : new GrainDto(entity.getGrainKey(), Cardinality.ZERO_OR_ONE);
    }

    private TtlDto ttl(TableDefinitionEntity entity) {
        return entity.getTtlColumn() == null ? null
                : new TtlDto(entity.getTtlColumn(), entity.getTtlInterval(), entity.getTtlUnit());
    }
}
