package com.epam.aidial.analytics.service.catalog.transfer;

import com.epam.aidial.analytics.service.table.TableAccessService.AccessEntry;
import com.epam.aidial.analytics.service.table.TableAccessService.IneffectiveGrant;
import com.epam.aidial.analytics.web.dto.catalog.AccessImportEntry;
import com.epam.aidial.analytics.web.dto.catalog.BundleAccessEntry;
import com.epam.aidial.analytics.web.dto.catalog.ImportAction;
import com.epam.aidial.analytics.web.dto.table.access.AccessEntryReplacedDto.IneffectiveGrantDto;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * One role entry of a bundle's {@code access} as the import plans, validates and applies it. Mutable, as
 * {@link PlannedObject} is: each stage adds to it.
 */
final class PlannedAccess {

    private final String table;
    private final String key;
    private final String role;
    private final BundleAccessEntry bundled;
    private final AccessEntry stored;
    private BundleAccessEntry next;
    private ImportAction action;
    private final List<String> problems = new ArrayList<>();
    private List<IneffectiveGrant> ineffectiveGrants = List.of();
    private String status;

    /**
     * @param key    the role key as the bundle writes it
     * @param stored the target table's entry for the trimmed role, or {@code null} when it has none
     */
    PlannedAccess(String table, String key, BundleAccessEntry bundled, AccessEntry stored) {
        this.table = table;
        this.key = key;
        this.role = StringUtils.trimToEmpty(key);
        this.bundled = bundled;
        this.stored = stored;
        this.next = bundled;
    }

    String table() {
        return table;
    }

    String key() {
        return key;
    }

    String role() {
        return role;
    }

    BundleAccessEntry bundled() {
        return bundled;
    }

    /** Checked: {@code entry} is what a {@code PUT} of the bundle's entry would store. */
    void checked(AccessEntry entry) {
        this.next = BundleAccessEntry.of(entry);
        this.action = stored == null ? ImportAction.CREATE : entry.equals(stored) ? ImportAction.SKIP : ImportAction.REPLACE;
    }

    void fail(String problem) {
        this.action = ImportAction.FAIL;
        this.next = bundled;
        problems.add(problem);
    }

    boolean failed() {
        return action == ImportAction.FAIL;
    }

    void ineffectiveGrants(List<IneffectiveGrant> grants) {
        this.ineffectiveGrants = List.copyOf(grants);
    }

    void clearIneffectiveGrants() {
        this.ineffectiveGrants = List.of();
    }

    List<String> problems() {
        return problems;
    }

    void status(String value) {
        this.status = value;
    }

    String status() {
        return status;
    }

    AccessImportEntry toEntry() {
        return new AccessImportEntry(table, role, action, stored == null ? null : BundleAccessEntry.of(stored), next,
                List.copyOf(problems), IneffectiveGrantDto.of(ineffectiveGrants), status);
    }
}
