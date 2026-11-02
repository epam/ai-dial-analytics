package com.epam.aidial.analytics.service.dashboard;

import com.epam.aidial.analytics.exception.DashboardValidationException;
import com.epam.aidial.analytics.exception.SavedQueryValidationException;
import com.epam.aidial.analytics.service.dashboard.model.DashboardWidget;
import com.epam.aidial.analytics.service.dashboard.model.InlineQuery;
import com.epam.aidial.analytics.service.dashboard.model.WidgetLayout;
import com.epam.aidial.analytics.service.query.saved.SavedQueryBodyValidator;
import com.epam.aidial.analytics.service.query.saved.model.SavedQueryTime;
import com.epam.aidial.analytics.web.dto.dashboard.DashboardRequestDto;
import com.epam.aidial.analytics.web.dto.dashboard.InlineQueryRequestDto;
import com.epam.aidial.analytics.web.dto.dashboard.LayoutRequestDto;
import com.epam.aidial.analytics.web.dto.dashboard.WidgetRequestDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Envelope validation for a dashboard: everything checkable without the catalog. Every rule answers
 * {@code 422 validation_error}; translating each inline body against the caller's catalog view is the
 * service's job, after authorization.
 *
 * <p>Each inline body is held to exactly the rules a saved query body is, through
 * {@link SavedQueryBodyValidator}'s body-level entry points, so the two cannot drift. A widget's message
 * names the widget: {@code widget <id>: }, or {@code widgets[<n>]: } while its id is itself in question.
 */
@Component
@RequiredArgsConstructor
public class DashboardValidator {

    /** The most widgets one dashboard may hold. */
    static final int MAX_WIDGETS = 100;

    /** The longest widget id, in characters. */
    static final int MAX_WIDGET_ID_LENGTH = 64;

    /** The longest name, description, title, value column or saved-query reference, in characters. */
    static final int MAX_TEXT_LENGTH = 1000;

    /**
     * The largest serialized {@code widgets} document, in bytes. The per-body cap alone would let 100
     * widgets each carry a body at the saved-query maximum, a row of several MiB re-planned on every
     * replace and copied into the audit table on every revision.
     */
    static final int MAX_WIDGETS_BYTES = 1024 * 1024;

    /** The grid's width in columns. */
    static final int GRID_COLUMNS = 12;

    static final int MIN_ROW_LIMIT = 1;
    static final int MAX_ROW_LIMIT = 1000;

    /** Which operation a body arrived on; {@code generation} is refused on one and required on the other. */
    public enum Operation {
        CREATE,
        REPLACE
    }

    private final SavedQueryBodyValidator bodyValidator;
    private final DashboardJson json;

    /**
     * Runs every envelope rule in the order that gives the most specific message first, and returns the
     * body as it is to be stored. On a replace {@code generation} is not examined here: the service checks
     * it before body validation, so a caller that may write the dashboard learns of a stale read first.
     */
    public ValidatedDashboard validate(DashboardRequestDto request, Operation operation) {
        if (request == null) {
            throw new DashboardValidationException("a dashboard body is required");
        }
        rejectServerAssignedFields(request, operation);
        final String name = requiredText(request.name(), "'name'", "");
        bounded(request.description(), "'description'", "");
        if (request.visibility() == null) {
            throw new DashboardValidationException("'visibility' is required: 'private' or 'public'");
        }
        final SavedQueryTime time = timeIntent(request.time());
        final List<DashboardWidget> widgets = widgets(request.widgets());
        if (json.serializedSize(widgets) > MAX_WIDGETS_BYTES) {
            throw new DashboardValidationException(
                    "the widgets are over the " + MAX_WIDGETS_BYTES + "-byte maximum for one dashboard");
        }
        return new ValidatedDashboard(
                name, request.description(), request.visibility(), time, request.compare(), widgets);
    }

    /**
     * Server-assigned members are refused rather than silently ignored, so a client that sends one learns
     * at once that it is not an input. An explicit {@code null} counts as absent.
     */
    private static void rejectServerAssignedFields(DashboardRequestDto request, Operation operation) {
        final List<String> supplied = new ArrayList<>();
        addIfPresent(supplied, request.id(), "id");
        addIfPresent(supplied, request.ownerId(), "owner_id");
        addIfPresent(supplied, request.ownerEmail(), "owner_email");
        if (operation == Operation.CREATE) {
            addIfPresent(supplied, request.generation(), "generation");
        }
        addIfPresent(supplied, request.createdAt(), "created_at");
        addIfPresent(supplied, request.updatedAt(), "updated_at");
        addIfPresent(supplied, request.permissions(), "permissions");
        if (!supplied.isEmpty()) {
            throw new DashboardValidationException(
                    "these fields are assigned by the server and must not be supplied: " + String.join(", ", supplied));
        }
    }

    private static void addIfPresent(List<String> supplied, JsonNode node, String member) {
        if (node != null && !node.isNull()) {
            supplied.add(member);
        }
    }

    /** The dashboard's time intent, held to the saved-query time-intent rule and its {@code 422}. */
    private SavedQueryTime timeIntent(JsonNode time) {
        try {
            return bodyValidator.validTimeIntent(time);
        } catch (SavedQueryValidationException e) {
            throw new DashboardValidationException(e.getMessage());
        }
    }

    private List<DashboardWidget> widgets(List<WidgetRequestDto> requested) {
        if (requested == null) {
            throw new DashboardValidationException("'widgets' is required; send [] for a dashboard without widgets");
        }
        if (requested.size() > MAX_WIDGETS) {
            throw new DashboardValidationException(
                    "a dashboard holds at most " + MAX_WIDGETS + " widgets, not " + requested.size());
        }
        final Set<String> ids = new HashSet<>();
        final List<DashboardWidget> widgets = new ArrayList<>(requested.size());
        for (int index = 0; index < requested.size(); index++) {
            final WidgetRequestDto widget = requested.get(index);
            final String position = "widgets[" + index + "]: ";
            if (widget == null) {
                throw new DashboardValidationException(position + "a widget must be an object");
            }
            final String id = widgetId(widget.id(), position);
            if (!ids.add(id)) {
                throw new DashboardValidationException("widget " + id + ": another widget already has this id");
            }
            widgets.add(widget(widget, id, "widget " + id + ": "));
        }
        return widgets;
    }

    private static String widgetId(String id, String position) {
        if (id == null || id.isBlank()) {
            throw new DashboardValidationException(position + "'id' is required and must not be blank");
        }
        if (length(id) > MAX_WIDGET_ID_LENGTH) {
            throw new DashboardValidationException(
                    position + "'id' is longer than " + MAX_WIDGET_ID_LENGTH + " characters");
        }
        return id;
    }

    private DashboardWidget widget(WidgetRequestDto widget, String id, String prefix) {
        final String savedQueryId = blankToNull(widget.savedQueryId());
        if ((savedQueryId == null) == (widget.inlineQuery() == null)) {
            throw new DashboardValidationException(prefix + "exactly one of 'saved_query_id' or 'inline_query' must be "
                    + "set, not " + (savedQueryId == null ? "neither" : "both"));
        }
        bounded(savedQueryId, "'saved_query_id'", prefix);
        bounded(widget.title(), "'title'", prefix);
        bounded(widget.description(), "'description'", prefix);
        bounded(widget.valueColumn(), "'value_column'", prefix);
        final int rowLimit = rowLimit(widget.rowLimit(), prefix);
        final WidgetLayout layout = layout(widget.layout(), prefix);
        final InlineQuery inline = widget.inlineQuery() == null ? null : inlineQuery(widget.inlineQuery(), prefix);
        return new DashboardWidget(id, savedQueryId, inline, widget.title(), widget.description(), rowLimit,
                widget.view(), widget.valueColumn(), widget.changeDirection(), layout);
    }

    private static int rowLimit(JsonNode node, String prefix) {
        final Long value = integral(node);
        if (value == null || value < MIN_ROW_LIMIT || value > MAX_ROW_LIMIT) {
            throw new DashboardValidationException(prefix + "'row_limit' must be a whole number from "
                    + MIN_ROW_LIMIT + " to " + MAX_ROW_LIMIT);
        }
        return value.intValue();
    }

    /**
     * The grid fit, with {@code x} and {@code w} bounded before their sum is taken so the sum cannot
     * overflow. Overlap with another widget is deliberately not checked: placement is the client's concern.
     */
    private static WidgetLayout layout(LayoutRequestDto layout, String prefix) {
        if (layout == null) {
            throw new DashboardValidationException(prefix + "'layout' is required");
        }
        final long x = coordinate(layout.x(), "x", 0, GRID_COLUMNS - 1, prefix);
        final long y = coordinate(layout.y(), "y", 0, Long.MAX_VALUE, prefix);
        final long w = coordinate(layout.w(), "w", 1, GRID_COLUMNS, prefix);
        final long h = coordinate(layout.h(), "h", 1, Long.MAX_VALUE, prefix);
        if (x + w > GRID_COLUMNS) {
            throw new DashboardValidationException(prefix + "the widget does not fit the " + GRID_COLUMNS
                    + "-column grid: 'x' + 'w' is " + (x + w));
        }
        return new WidgetLayout((int) x, y, (int) w, h);
    }

    private static long coordinate(JsonNode node, String member, long min, long max, String prefix) {
        final Long value = integral(node);
        if (value == null || value < min || value > max) {
            throw new DashboardValidationException(prefix + "'layout." + member + "' must be a whole number "
                    + (max == Long.MAX_VALUE ? "of at least " + min : "from " + min + " to " + max));
        }
        return value;
    }

    private InlineQuery inlineQuery(InlineQueryRequestDto inline, String prefix) {
        final String name = requiredText(inline.name(), "'inline_query.name'", prefix);
        final String sql = SavedQueryBodyValidator.sqlBody(inline.sql());
        try {
            // A widget's inline query has no time intent of its own, so nothing rides along in the cap.
            bodyValidator.validateBody(inline.query(), inline.sql(), inline.chart(), 0);
        } catch (SavedQueryValidationException e) {
            throw new DashboardValidationException(prefix + e.getMessage());
        }
        final InlineResultView resultView = inline.resultView() == null ? InlineResultView.TABLE : inline.resultView();
        return new InlineQuery(name, inline.query(), sql, resultView, inline.chart());
    }

    /**
     * A required, non-blank, bounded text member, trimmed as it is stored. Blankness is judged on the trimmed
     * value: {@code trim()} also strips control characters, which {@code isBlank()} does not count as blank.
     */
    private static String requiredText(String value, String member, String prefix) {
        final String trimmed = value == null ? "" : value.trim();
        if (trimmed.isBlank()) {
            throw new DashboardValidationException(prefix + member + " is required and must not be blank");
        }
        bounded(trimmed, member, prefix);
        return trimmed;
    }

    private static void bounded(String value, String member, String prefix) {
        if (value != null && length(value) > MAX_TEXT_LENGTH) {
            throw new DashboardValidationException(
                    prefix + member + " is longer than " + MAX_TEXT_LENGTH + " characters");
        }
    }

    /** A JSON integer's value, or null for anything else — a fraction, a string, a boolean, or a value past {@code long}. */
    private static Long integral(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong() ? node.longValue() : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static int length(String value) {
        return value.codePointCount(0, value.length());
    }
}
