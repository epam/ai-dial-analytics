package com.epam.aidial.analytics.web.controller.setting;

import com.epam.aidial.analytics.service.setting.AppRoute;
import com.epam.aidial.analytics.service.setting.AppRoutesSetting;
import com.epam.aidial.analytics.service.setting.AppRoutesSettingService;
import com.epam.aidial.analytics.service.setting.SessionsSourceRoles;
import com.epam.aidial.analytics.service.setting.SessionsSourceSetting;
import com.epam.aidial.analytics.service.setting.SessionsSourceSettingService;
import com.epam.aidial.analytics.service.setting.StoredAppRoutesSetting;
import com.epam.aidial.analytics.service.setting.StoredSessionsSourceSetting;
import com.epam.aidial.analytics.web.dto.setting.AppRouteDto;
import com.epam.aidial.analytics.web.dto.setting.AppRoutesSettingDto;
import com.epam.aidial.analytics.web.dto.setting.AppRoutesSettingRequestDto;
import com.epam.aidial.analytics.web.dto.setting.SessionsSourceRolesDto;
import com.epam.aidial.analytics.web.dto.setting.SessionsSourceSettingDto;
import com.epam.aidial.analytics.web.dto.setting.SessionsSourceSettingRequestDto;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.RequiresPrivilege;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Instance settings: one value per ADAS instance, authored by an administrator and read by every analyst.
 *
 * <p>The sessions-source setting names the catalog entity holding session rows and binds its flat fields to the
 * session roles. It is validated on write against the catalog as the writer sees it and stored as written; it is
 * not revalidated on read. The app-routes setting lists the DIAL application routes an enrichment pipeline may
 * call, and refuses to drop one an enabled pipeline uses. Reads need {@code SETTINGS_READ}, writes
 * {@code SETTINGS_WRITE}.
 */
@RestController
@RequestMapping("/v1/settings")
@RequiredArgsConstructor
@Tag(name = "Settings", description = "Per-instance settings.")
public class SettingsController {

    private final SessionsSourceSettingService sessionsSource;
    private final AppRoutesSettingService appRoutes;

    @GetMapping("/sessions-source")
    @RequiresPrivilege(Privilege.SETTINGS_READ)
    @Operation(summary = "Read the sessions-source setting",
            description = "The stored setting as written, with the time and author of its last write.")
    @ApiResponse(responseCode = "200", description = "Stored setting",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SessionsSourceSettingDto.class)))
    @ApiResponse(responseCode = "403", description = "Caller lacks SETTINGS_READ")
    @ApiResponse(responseCode = "404", description = "No setting stored (setting_not_found)")
    public SessionsSourceSettingDto getSessionsSource() {
        return toDto(sessionsSource.get());
    }

    @PutMapping("/sessions-source")
    @RequiresPrivilege(Privilege.SETTINGS_WRITE)
    @Operation(summary = "Replace the sessions-source setting",
            description = "Create-or-replace the whole setting. base_table must be an ACTIVE source table; every bound "
                    + "role must name a field of its entity schema, as the caller sees it, whose type fits the role "
                    + "(session_id, project, title: string; time: timestamp; cost: decimal, integer or long; turns: "
                    + "integer or long), and that is neither an array nor projection-only. updated_at and updated_by "
                    + "are server-stamped and rejected if supplied.")
    @ApiResponse(responseCode = "200", description = "Stored setting",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SessionsSourceSettingDto.class)))
    @ApiResponse(responseCode = "400", description = "Malformed body or unknown member")
    @ApiResponse(responseCode = "403", description = "Caller lacks SETTINGS_WRITE")
    @ApiResponse(responseCode = "422", description = "Setting does not validate against the catalog (validation_error)")
    public SessionsSourceSettingDto replaceSessionsSource(@RequestBody SessionsSourceSettingRequestDto request) {
        final SessionsSourceRolesDto roles = request.roles();
        final SessionsSourceSetting setting = new SessionsSourceSetting(request.baseTable(), roles == null ? null
                : new SessionsSourceRoles(roles.sessionId(), roles.time(), roles.project(), roles.title(), roles.cost(), roles.turns()));
        return toDto(sessionsSource.replace(setting));
    }

    @GetMapping("/app-routes")
    @RequiresPrivilege(Privilege.SETTINGS_READ)
    @Operation(summary = "Read the app-routes setting",
            description = "The DIAL application routes an enrichment pipeline may call, as written, with the time and "
                    + "author of the last write.")
    @ApiResponse(responseCode = "200", description = "Stored setting",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AppRoutesSettingDto.class)))
    @ApiResponse(responseCode = "403", description = "Caller lacks SETTINGS_READ")
    @ApiResponse(responseCode = "404", description = "No setting stored (setting_not_found); no route is allowed")
    public AppRoutesSettingDto getAppRoutes() {
        return toDto(appRoutes.get());
    }

    @PutMapping("/app-routes")
    @RequiresPrivilege(Privilege.SETTINGS_WRITE)
    @Operation(summary = "Replace the app-routes setting",
            description = "Create-or-replace the whole allowlist of (deployment, route) pairs, compared exactly and "
                    + "case-sensitively. Each route uses the transform.route grammar. A write dropping a pair that an "
                    + "enabled pipeline calls is refused; disable the pipeline first.")
    @ApiResponse(responseCode = "200", description = "Stored setting",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AppRoutesSettingDto.class)))
    @ApiResponse(responseCode = "400", description = "Malformed body or unknown member")
    @ApiResponse(responseCode = "403", description = "Caller lacks SETTINGS_WRITE")
    @ApiResponse(responseCode = "409", description = "An enabled pipeline calls a dropped pair (app_route_in_use)")
    @ApiResponse(responseCode = "422", description = "Setting does not validate (validation_error)")
    public AppRoutesSettingDto replaceAppRoutes(@RequestBody AppRoutesSettingRequestDto request) {
        final List<AppRoute> routes = request.routes() == null ? null : request.routes().stream()
                .map(route -> route == null ? null : new AppRoute(route.deployment(), route.route()))
                .toList();
        return toDto(appRoutes.replace(new AppRoutesSetting(routes)));
    }

    private static AppRoutesSettingDto toDto(StoredAppRoutesSetting stored) {
        final List<AppRoute> routes = stored.setting().routes() == null ? List.of() : stored.setting().routes();
        return new AppRoutesSettingDto(
                routes.stream().map(route -> new AppRouteDto(route.deployment(), route.route())).toList(),
                stored.updatedAt(), stored.updatedBy());
    }

    private static SessionsSourceSettingDto toDto(StoredSessionsSourceSetting stored) {
        final SessionsSourceSetting setting = stored.setting();
        final SessionsSourceRoles roles = setting.roles() == null
                ? new SessionsSourceRoles(null, null, null, null, null, null)
                : setting.roles();
        return new SessionsSourceSettingDto(setting.baseTable(),
                new SessionsSourceRolesDto(roles.sessionId(), roles.time(), roles.project(), roles.title(), roles.cost(), roles.turns()),
                stored.updatedAt(), stored.updatedBy());
    }
}
