package com.epam.aidial.analytics.web.controller.dashboard;

import com.epam.aidial.analytics.service.dashboard.DashboardService;
import com.epam.aidial.analytics.web.dto.dashboard.DashboardDto;
import com.epam.aidial.analytics.web.dto.dashboard.DashboardListDto;
import com.epam.aidial.analytics.web.dto.dashboard.DashboardRequestDto;
import com.epam.aidial.analytics.web.mapper.dashboard.DashboardMapper;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.RequiresPrivilege;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboards — widgets on a 12-column grid, each running one query, under a dashboard-wide time intent and
 * period comparison.
 *
 * <p>A dashboard stores authored intent only. It stores no query result and the service caches none: the
 * client runs every widget through {@code /v1/queries/execute} or {@code /v1/queries/execute-sql} under the
 * viewer's own credential, so table grants, sensitive columns and row policies apply per viewer. There is
 * deliberately no execute endpoint here.
 *
 * <p>Authorization reuses the saved-query privileges. Reads need {@code SAVED_QUERY_READ}, which
 * {@code SAVED_QUERY_WRITE} and {@code SAVED_QUERY_PUBLISH} both imply. Writes are deferred to {@code DashboardAccess}, because the request
 * body's {@code visibility} picks the privilege: {@code SAVED_QUERY_WRITE} for {@code private},
 * {@code SAVED_QUERY_PUBLISH} for {@code public} and for every visibility change. A dashboard the caller may
 * not see answers {@code 404}, never {@code 403}.
 */
@RestController
@RequestMapping("/v1/dashboards")
@RequiredArgsConstructor
@Tag(name = "Dashboards", description = "Store and share dashboards of query widgets.")
public class DashboardController {

    private final DashboardService service;
    private final DashboardMapper mapper;

    @GetMapping
    @RequiresPrivilege(Privilege.SAVED_QUERY_READ)
    @Operation(summary = "List dashboards",
            description = "Every dashboard the caller may see — its own private ones and every public one — most "
                    + "recently updated first, as list items without widgets, plus what the caller may create.")
    public DashboardListDto list() {
        return mapper.toListDto(service.list());
    }

    @PostMapping
    @RequiresPrivilege(value = Privilege.SAVED_QUERY_WRITE, deferred = true)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a dashboard",
            description = "Each inline query is validated by the same translation the query surface performs, "
                    + "against the caller's view of the catalog. id, owner_id, owner_email, generation, the "
                    + "timestamps and permissions are server-assigned and rejected if supplied. A public dashboard "
                    + "requires SAVED_QUERY_PUBLISH.")
    public DashboardDto create(@Valid @RequestBody DashboardRequestDto request) {
        return mapper.toDto(service.create(request));
    }

    @GetMapping("/{id}")
    @RequiresPrivilege(Privilege.SAVED_QUERY_READ)
    @Operation(summary = "Read a dashboard",
            description = "The full dashboard, widgets and inline bodies included, as stored. A dashboard the "
                    + "caller may not see is reported as not found.")
    public DashboardDto get(@PathVariable String id) {
        return mapper.toDto(service.get(id));
    }

    @PutMapping("/{id}")
    @RequiresPrivilege(value = Privilege.SAVED_QUERY_WRITE, deferred = true)
    @Operation(summary = "Replace a dashboard",
            description = "Whole-document replace. The body must carry the generation the client last read; a "
                    + "stale one answers 409 dashboard_conflict and changes nothing. Changing visibility requires "
                    + "SAVED_QUERY_PUBLISH. id, owner and created_at are preserved.")
    public DashboardDto replace(@PathVariable String id, @Valid @RequestBody DashboardRequestDto request) {
        return mapper.toDto(service.replace(id, request));
    }

    @DeleteMapping("/{id}")
    @RequiresPrivilege(value = Privilege.SAVED_QUERY_WRITE, deferred = true)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a dashboard")
    public void delete(@PathVariable String id) {
        service.delete(id);
    }
}
