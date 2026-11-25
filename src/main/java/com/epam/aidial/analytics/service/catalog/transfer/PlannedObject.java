package com.epam.aidial.analytics.service.catalog.transfer;

import com.epam.aidial.analytics.web.dto.catalog.BundlePipeline;
import com.epam.aidial.analytics.web.dto.catalog.BundleTable;
import com.epam.aidial.analytics.web.dto.catalog.ImportAction;
import com.epam.aidial.analytics.web.dto.catalog.ImportEffect;
import com.epam.aidial.analytics.web.dto.catalog.ImportEntry;
import com.epam.aidial.analytics.web.dto.catalog.NotApplied;
import com.epam.aidial.analytics.web.dto.catalog.PlannedChange;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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
    private List<PlannedChange> changes = List.of();
    private List<NotApplied> notApplied = List.of();
    private List<ImportEffect> effects = List.of();
    private BundlePipeline.Declaration redeclaration;
    private boolean updateIntended;
    private final Set<PlannedChange.Op> applied = EnumSet.noneOf(PlannedChange.Op.class);

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

    boolean updates() {
        return action == ImportAction.UPDATE;
    }

    /**
     * Whether {@code OVERWRITE} set out to update this object, even if a later check refused it: authorization asks for
     * the update's privileges before any refusal, so a caller missing them is told so first.
     */
    boolean intendsUpdate() {
        return updateIntended;
    }

    /** Whether the import writes this object, by creating or by updating it. */
    boolean writes() {
        return creates() || updates();
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

    /** To be changed under {@code OVERWRITE} as {@code diff} says. */
    void update(ObjectDiff.Result diff) {
        this.action = ImportAction.UPDATE;
        this.updateIntended = true;
        diff(diff);
        this.redeclaration = diff.redeclaration();
    }

    /** Records what {@code OVERWRITE} would change and keep, without changing the action. */
    void diff(ObjectDiff.Result diff) {
        this.changes = diff.changes();
        this.notApplied = diff.notApplied();
        this.effects = diff.effects();
    }

    List<PlannedChange> changes() {
        return changes;
    }

    boolean hasChange(PlannedChange.Op op) {
        return changes.stream().anyMatch(change -> change.op() == op);
    }

    /** The declaration an update re-declares the pipeline with: the bundle's, with the target-owned members kept. */
    BundlePipeline.Declaration redeclaration() {
        return redeclaration;
    }

    /** Records that the apply committed the step {@code op}, which compensation then reverts. */
    void applied(PlannedChange.Op op) {
        applied.add(op);
    }

    /** Forgets the applied steps: the validation pass runs the same steps first, in a transaction it rolls back. */
    void clearApplied() {
        applied.clear();
    }

    boolean wasApplied(PlannedChange.Op op) {
        return applied.contains(op);
    }

    /** Refused because the object exists under {@code FAIL_IF_EXISTS}. */
    void conflict(String problem) {
        this.conflict = true;
        fail(problem);
    }

    /** Refused under any policy. A refused object is never created, and every object needing it is refused too. */
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
        return new ImportEntry(name, action, prev, next, differs, metadataOnly, changes, notApplied, effects,
                List.copyOf(problems),
                pipeline ? armable : null, pipeline ? List.copyOf(armProblems) : null, pipeline ? reusedName : null, status);
    }
}
