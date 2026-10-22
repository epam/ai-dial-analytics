package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.exception.PipelineAccessDeniedException;
import com.epam.aidial.analytics.service.security.RowPolicyFilter;
import com.epam.aidial.analytics.service.table.grant.TableAccessEvaluator;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.SecurityModeResolver;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Authorizes the pipeline-scoped data plane by the two privileges it actually exercises:
 * {@link Privilege#TABLE_READ} on the pipeline's <b>read source</b> and {@link Privilege#TABLE_WRITE} on
 * its <b>target enrichment</b>, each held globally or granted on that table.
 *
 * <p>Authorizing both directions from the target's write grant alone was a confused deputy. The scan and
 * group-assembly endpoints return rows of the read source, and a scan projection may include the columns
 * of the source's <em>other</em> active enrichments, so a {@code write} grant on one small derived
 * enrichment was a read capability over the table it derives from and over its siblings. The access model
 * now carries a read grant, so the read direction is authorized against the table actually read.
 *
 * <p>An Enrichment Runner therefore needs {@link Privilege#REGISTRY_READ} for the sync feed plus per-table
 * grants on the sources it scans and the targets it writes; it needs no bundle conferring global read.
 *
 * <p>A caller confined by a row policy on a table the operation reads is refused {@code 403
 * row_policy_confined}: these reads carry pipeline settings and no row filter (see {@link RowPolicyFilter}).
 *
 * <p>Called programmatically rather than at the endpoint gate, so it mirrors that gate's {@code none}-mode
 * behaviour: the check is bypassed when security is not enforced (otherwise the empty-authorities default
 * would deny every scan and write). The mode comes from the single {@link SecurityModeResolver}.
 */
@Component
@RequiredArgsConstructor
public class PipelineAuthorizer {

    private final TableAccessEvaluator tableAccess;
    private final SecurityModeResolver securityMode;
    private final RowPolicyFilter rowPolicies;

    /**
     * Requires {@code TABLE_READ} on {@code readSource} and {@code TABLE_WRITE} on
     * {@code targetEnrichment}; a no-op when security is not enforced.
     *
     * @param readSource       the table the operation reads rows of, or {@code null} for a write-only path
     *                         ({@code POST /v1/pipelines/{name}/rows} carries caller rows and reads nothing)
     * @param targetEnrichment the enrichment the operation writes
     */
    public void authorize(String readSource, String targetEnrichment) {
        if (!securityMode.isEnforced()) {
            return;
        }
        if (StringUtils.isNotBlank(readSource) && !tableAccess.isAllowed(readSource, Privilege.TABLE_READ)) {
            throw new PipelineAccessDeniedException(
                    "caller lacks read access to pipeline read source '" + readSource + "'");
        }
        if (!tableAccess.isAllowed(targetEnrichment, Privilege.TABLE_WRITE)) {
            throw new PipelineAccessDeniedException(
                    "caller lacks write access to target enrichment '" + targetEnrichment + "'");
        }
        // The reads below run under pipeline settings, which carry no row-policy filter: a caller confined on a
        // table they read is refused rather than served unfiltered rows. A write-only path reads nothing.
        if (StringUtils.isNotBlank(readSource)) {
            rowPolicies.requireUnconfinedPipeline(readSource, targetEnrichment, "read through this pipeline");
        }
    }
}
