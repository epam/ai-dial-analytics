package com.epam.aidial.analytics.service.catalog.transfer;

import com.epam.aidial.analytics.web.dto.catalog.BundlePipeline;
import com.epam.aidial.analytics.web.dto.catalog.BundleTable;
import com.epam.aidial.analytics.web.dto.catalog.ImportAction;
import com.epam.aidial.analytics.web.dto.catalog.ImportEntry;

import java.util.ArrayList;
import java.util.List;

/** One bundle object as the import plans, validates and applies it. Mutable: each stage adds to it. */
final class PlannedObject {

    /** What kind of bundle member this is. */
    enum Kind {
        SYSTEM_TABLE("system_table"),
        TABLE("table"),
        PIPELINE("pipeline");

        private final String code;

        Kind(String code) {
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private final Kind kind;
    private final String name;
    private final Object next;
    private Object existing;
    private Object prev;
    private ImportAction action = ImportAction.CREATE;
    private Boolean differs;
    private Boolean metadataOnly;
    private boolean conflict;
    private final List<String> problems = new ArrayList<>();
    private Boolean armable;
    private final List<String> armProblems = new ArrayList<>();
    private Boolean reusedName;
    private String status;

    PlannedObject(Kind kind, String name, Object next) {
        this.kind = kind;
        this.name = name;
        this.next = next;
    }

    Kind kind() {
        return kind;
    }

    String name() {
        return name;
    }

    BundleTable table() {
        return (BundleTable) next;
    }

    BundlePipeline pipeline() {
        return (BundlePipeline) next;
    }

    /** The target's existing object in bundle shape, whatever the caller may see; null when the object is new. */
    Object existing() {
        return existing;
    }

    ImportAction action() {
        return action;
    }

    boolean creates() {
        return action == ImportAction.CREATE;
    }

    /**
     * Records the target's object of this name: {@code full} is what the import compares and validates against,
     * {@code visible} what the entry reports as {@code prev} — the caller's view of it, or null when the caller
     * may not see it at all.
     */
    void found(Object full, Object visible) {
        this.existing = full;
        this.prev = visible;
    }

    void skip() {
        this.action = ImportAction.SKIP;
    }

    /** Refused because the object exists under {@code FAIL_IF_EXISTS}. */
    void conflict(String problem) {
        this.conflict = true;
        fail(problem);
    }

    /** Refused under either policy. A refused object is never created, and every object needing it is refused too. */
    void fail(String problem) {
        this.action = ImportAction.FAIL;
        problems.add(problem);
    }

    boolean failed() {
        return action == ImportAction.FAIL;
    }

    boolean isConflict() {
        return conflict;
    }

    List<String> problems() {
        return problems;
    }

    void differs(boolean value, boolean onlyMetadata) {
        this.differs = value;
        this.metadataOnly = onlyMetadata;
    }

    void armable(boolean value) {
        this.armable = value;
    }

    void armProblem(String problem) {
        this.armable = false;
        armProblems.add(problem);
    }

    void reusedName(boolean value) {
        this.reusedName = value;
    }

    void status(String value) {
        this.status = value;
    }

    String status() {
        return status;
    }

    ImportEntry toEntry() {
        final boolean pipeline = kind == Kind.PIPELINE;
        return new ImportEntry(name, action, prev, next, differs, metadataOnly, List.copyOf(problems),
                pipeline ? armable : null, pipeline ? List.copyOf(armProblems) : null, pipeline ? reusedName : null, status);
    }
}
