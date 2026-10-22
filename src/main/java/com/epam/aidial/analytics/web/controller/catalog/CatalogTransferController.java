package com.epam.aidial.analytics.web.controller.catalog;

import com.epam.aidial.analytics.exception.CatalogBundleMalformedException;
import com.epam.aidial.analytics.service.catalog.transfer.CatalogExportService;
import com.epam.aidial.analytics.service.catalog.transfer.CatalogImportService;
import com.epam.aidial.analytics.web.dto.catalog.CatalogBundle;
import com.epam.aidial.analytics.web.dto.catalog.ExportPreview;
import com.epam.aidial.analytics.web.dto.catalog.ExportRequest;
import com.epam.aidial.analytics.web.dto.catalog.ImportPreview;
import com.epam.aidial.analytics.web.dto.catalog.ImportResult;
import com.epam.aidial.analytics.web.dto.catalog.ResolutionPolicy;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.RequiresPrivilege;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Catalog promotion: export user tables and pipelines as a versioned bundle on one environment, and import it on
 * another. Shaped like deployment manager's config export/import — the export selection in a JSON body, the import
 * as a multipart upload, and a preview twin for each call — so one admin UI drives both services alike.
 */
@RestController
@RequestMapping("/v1/catalog")
@Tag(name = "Catalog transfer", description = "Export and import tables, columns, grants and pipelines.")
public class CatalogTransferController {

    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final String ACKNOWLEDGE_DESCRIPTION = "Accept creating a pipeline whose name this environment "
            + "used before. The Enrichment Runner keeps a deleted pipeline's scan cursor, dead letters and spend counters "
            + "by name, so a recreated one resumes from them";

    private static final String POLICY_DESCRIPTION = "What to do with a bundle object that already exists: "
            + "FAIL_IF_EXISTS refuses the import (409), SKIP_IF_EXISTS keeps the existing object unchanged and imports "
            + "the rest. OVERWRITE is refused (422): import never changes an existing object.";

    private final CatalogExportService exportService;
    private final CatalogImportService importService;
    private final DataSize maxFileSize;

    public CatalogTransferController(CatalogExportService exportService, CatalogImportService importService,
            @Value("${spring.servlet.multipart.max-file-size:20MB}") DataSize maxFileSize) {
        this.exportService = exportService;
        this.importService = importService;
        this.maxFileSize = maxFileSize;
    }

    @PostMapping("/export")
    @RequiresPrivilege(Privilege.REGISTRY_READ)
    @Operation(summary = "Export a catalog bundle (writes nothing)",
            description = "Returns a bundle file of the listed tables and pipelines with their dependency closure, "
                    + "computed in both directions; an empty or absent list exports every exportable object. System "
                    + "tables travel by name, as required system tables; OTLP landing tables and PENDING/FAILED tables are left "
                    + "out. Each table needs TABLE_GET, and one with sensitive columns the caller may not see is "
                    + "refused (403). Grants never travel: each environment sets its own.")
    public ResponseEntity<CatalogBundle> export(@Valid @RequestBody(required = false) ExportRequest request) {
        final CatalogBundle bundle = exportService.export(request);
        final String filename = "adas-catalog-" + FILE_TIMESTAMP.format(bundle.exportedAt()) + ".json";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(bundle);
    }

    @PostMapping("/export/preview")
    @RequiresPrivilege(Privilege.REGISTRY_READ)
    @Operation(summary = "Preview a catalog export (writes nothing)",
            description = "Takes the export body and returns the objects the bundle would carry, each with the reason "
                    + "it is included, plus the system tables it would require and the objects left out.")
    public ExportPreview exportPreview(@Valid @RequestBody(required = false) ExportRequest request) {
        return exportService.preview(request);
    }

    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPrivilege(Privilege.TABLE_CREATE)
    @Operation(summary = "Preview a catalog import (writes nothing)",
            description = "Runs the import's validation pass — the real create services inside a rolled-back "
                    + "transaction, then the arm-time pipeline checks — and returns what an import would do with each "
                    + "object. Answers 200 whenever the bundle parses, problems included.")
    public ImportPreview importPreview(
            @Parameter(description = "The bundle file, as exported") @RequestPart("file") MultipartFile file,
            @Parameter(description = POLICY_DESCRIPTION, required = true)
            @RequestParam("resolution_policy") ResolutionPolicy resolutionPolicy,
            @Parameter(description = ACKNOWLEDGE_DESCRIPTION)
            @RequestParam(value = "acknowledge_reused_names", defaultValue = "false") boolean acknowledgeReusedNames) {
        return importService.preview(bytes(file), acknowledgeReusedNames, resolutionPolicy);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPrivilege(Privilege.TABLE_CREATE)
    @Operation(summary = "Import a catalog bundle (writes)",
            description = "Runs the same validation pass as the preview and, only if it finds no problem, creates each "
                    + "object in dependency order as its own step: tables, without grants, then pipelines, always "
                    + "disabled. On a failure part-way it deletes again what it created. 409 catalog_import_conflict "
                    + "under FAIL_IF_EXISTS when an object exists; 422 catalog_import_invalid when the pass finds a "
                    + "problem — the preview reports each one.")
    public ImportResult importBundle(
            @Parameter(description = "The bundle file, as exported") @RequestPart("file") MultipartFile file,
            @Parameter(description = POLICY_DESCRIPTION, required = true)
            @RequestParam("resolution_policy") ResolutionPolicy resolutionPolicy,
            @Parameter(description = ACKNOWLEDGE_DESCRIPTION)
            @RequestParam(value = "acknowledge_reused_names", defaultValue = "false") boolean acknowledgeReusedNames) {
        return importService.apply(bytes(file), acknowledgeReusedNames, resolutionPolicy);
    }

    /**
     * The upload's content. The size is checked here as well as by the servlet container's multipart limit, which
     * a request reaching the handler by any other route (a test's mock request, a resolver configured differently)
     * would not have passed through.
     */
    private byte[] bytes(MultipartFile file) {
        if (file.getSize() > maxFileSize.toBytes()) {
            throw new MaxUploadSizeExceededException(maxFileSize.toBytes());
        }
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new CatalogBundleMalformedException("The uploaded file could not be read");
        }
    }
}
