package com.epam.aidial.analytics.service.dashboard;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.exception.DashboardValidationException;
import com.epam.aidial.analytics.service.dashboard.DashboardValidator.Operation;
import com.epam.aidial.analytics.service.dashboard.model.DashboardWidget;
import com.epam.aidial.analytics.service.query.saved.SavedQueryBodyValidator;
import com.epam.aidial.analytics.web.dto.dashboard.DashboardRequestDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Envelope validation for a dashboard: every rule checkable without the catalog, each answering
 * {@code 422 validation_error}. Bodies are bound from JSON with the application's mapper, so the
 * {@code JsonNode} members reach the validator in the shape a request would give them.
 */
class DashboardValidatorTest {

    private static final JsonMapper MAPPER = new JsonMapperConfiguration().jsonMapper();

    private static final String SQL_WIDGET = """
            {"id":"w1","inline_query":{"name":"Chats","sql":"SELECT 1"},"row_limit":100,
             "layout":{"x":0,"y":0,"w":6,"h":4}}""";

    private final DashboardValidator validator = newValidator();

    @Test
    void acceptsDashboardWithoutWidgetsAndTrimsItsName() {
        final ValidatedDashboard dashboard = validate("{\"name\":\"  Weekly  \",\"visibility\":\"private\",\"widgets\":[]}");
        assertThat(dashboard.name()).isEqualTo("Weekly");
        assertThat(dashboard.widgets()).isEmpty();
    }

    @Test
    void acceptsWidgetAndFillsInItsResultView() {
        final DashboardWidget widget = validate(dashboard(SQL_WIDGET)).widgets().getFirst();
        assertThat(widget.inlineQuery().resultView()).isEqualTo(InlineResultView.TABLE);
        assertThat(widget.rowLimit()).isEqualTo(100);
    }

    /** U+0001 is not blank to {@code isBlank()} but trims away, so it would store an empty name. */
    @ParameterizedTest
    @ValueSource(strings = {"   ", "\\u0001", " \\u0008\\t "})
    void rejectsNameThatIsBlankOnceTrimmed(String name) {
        assertRejected("{\"name\":\"" + name + "\",\"visibility\":\"private\",\"widgets\":[]}", "'name'");
    }

    @Test
    void rejectsLongNameOrDescription() {
        final String long1001 = "x".repeat(1001);
        assertRejected("{\"name\":\"" + long1001 + "\",\"visibility\":\"private\",\"widgets\":[]}", "'name'");
        assertRejected("{\"name\":\"n\",\"description\":\"" + long1001 + "\",\"visibility\":\"private\",\"widgets\":[]}",
                "'description'");
        assertThatCode(() -> validate("{\"name\":\"" + "x".repeat(1000) + "\",\"visibility\":\"private\",\"widgets\":[]}"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingVisibility() {
        assertRejected("{\"name\":\"n\",\"widgets\":[]}", "'visibility'");
    }

    @Test
    void rejectsMissingOrNullWidgets() {
        assertRejected("{\"name\":\"n\",\"visibility\":\"private\"}", "'widgets'");
        assertRejected("{\"name\":\"n\",\"visibility\":\"private\",\"widgets\":null}", "'widgets'");
    }

    @Test
    void rejectsMoreThanOneHundredWidgets() {
        assertThatCode(() -> validate(dashboard(widgets(100)))).doesNotThrowAnyException();
        assertRejected(dashboard(widgets(101)), "at most 100");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"id\":\"dash_x\"", "\"owner_id\":\"someone\"", "\"owner_email\":\"a@b\"",
            "\"generation\":0", "\"created_at\":\"2026-01-01T00:00:00Z\"", "\"updated_at\":\"2026-01-01T00:00:00Z\"",
            "\"permissions\":{\"write\":true}"})
    void rejectsServerAssignedMembersOnCreate(String member) {
        assertRejected("{\"name\":\"n\",\"visibility\":\"private\",\"widgets\":[]," + member + "}", "assigned by the server");
    }

    @Test
    void acceptsGenerationOnReplaceButStillRejectsTheOtherServerAssignedMembers() {
        assertThatCode(() -> validator.validate(
                parse("{\"name\":\"n\",\"visibility\":\"private\",\"widgets\":[],\"generation\":3}"), Operation.REPLACE))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(
                parse("{\"name\":\"n\",\"visibility\":\"private\",\"widgets\":[],\"generation\":3,\"permissions\":{}}"),
                Operation.REPLACE))
                .isInstanceOf(DashboardValidationException.class)
                .hasMessageContaining("permissions");
    }

    @Test
    void treatsAnExplicitNullServerAssignedMemberAsAbsent() {
        assertThatCode(() -> validate("{\"name\":\"n\",\"visibility\":\"private\",\"widgets\":[],\"owner_id\":null}"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankLongOrDuplicateWidgetId() {
        assertRejected(dashboard(SQL_WIDGET.replace("\"w1\"", "\"  \"")), "widgets[0]: 'id'");
        assertRejected(dashboard(SQL_WIDGET.replace("\"w1\"", "\"" + "w".repeat(65) + "\"")), "64");
        assertThatCode(() -> validate(dashboard(SQL_WIDGET.replace("\"w1\"", "\"" + "w".repeat(64) + "\""))))
                .doesNotThrowAnyException();
        assertRejected(dashboard(SQL_WIDGET + "," + SQL_WIDGET), "another widget already has this id");
    }

    @Test
    void rejectsWidgetWithTwoOrNoSources() {
        assertRejected(dashboard(SQL_WIDGET.replace("{\"id\":\"w1\",", "{\"id\":\"w1\",\"saved_query_id\":\"sq_1\",")),
                "widget w1: exactly one of 'saved_query_id' or 'inline_query' must be set, not both");
        assertRejected(dashboard("{\"id\":\"w1\",\"row_limit\":1,\"layout\":{\"x\":0,\"y\":0,\"w\":1,\"h\":1}}"),
                "not neither");
    }

    @Test
    void treatsBlankSavedQueryIdAsAbsent() {
        assertRejected(dashboard("{\"id\":\"w1\",\"saved_query_id\":\" \",\"row_limit\":1,"
                + "\"layout\":{\"x\":0,\"y\":0,\"w\":1,\"h\":1}}"), "not neither");
        final DashboardWidget widget = validate(dashboard(SQL_WIDGET.replace(
                "{\"id\":\"w1\",", "{\"id\":\"w1\",\"saved_query_id\":\"\","))).widgets().getFirst();
        assertThat(widget.savedQueryId()).isNull();
    }

    @Test
    void rejectsLongWidgetTextMember() {
        assertRejected(dashboard(SQL_WIDGET.replace("{\"id\":\"w1\",", "{\"id\":\"w1\",\"title\":\"" + "t".repeat(1001) + "\",")),
                "'title'");
        assertRejected(dashboard(SQL_WIDGET.replace("{\"id\":\"w1\",", "{\"id\":\"w1\",\"value_column\":\"" + "v".repeat(1001)
                + "\",")), "'value_column'");
    }

    @Test
    void keepsAnyObjectAsSettingsOnTheDashboardAndOnEachWidget() {
        final String dashboardSettings = "{\"theme\":\"dark\",\"refresh\":{\"seconds\":60},\"pinned\":[1,\"a\",null],\"note\":null}";
        final String widgetSettings = "{\"value_format\":\"currency\",\"currency\":\"EUR\"}";
        final ValidatedDashboard dashboard = validate("{\"name\":\"Weekly\",\"visibility\":\"private\",\"settings\":"
                + dashboardSettings + ",\"widgets\":[" + withMembers("\"settings\":" + widgetSettings) + "]}");

        assertThat(dashboard.settings()).isEqualTo(MAPPER.readTree(dashboardSettings));
        assertThat(dashboard.widgets().getFirst().settings()).isEqualTo(MAPPER.readTree(widgetSettings));
    }

    @Test
    void keepsAnEmptySettingsObject() {
        final ValidatedDashboard dashboard = validate("{\"name\":\"Weekly\",\"visibility\":\"private\",\"settings\":{},"
                + "\"widgets\":[" + withMembers("\"settings\":{}") + "]}");
        assertThat(dashboard.settings().isObject()).isTrue();
        assertThat(dashboard.widgets().getFirst().settings().isEmpty()).isTrue();
    }

    @Test
    void treatsAbsentOrNullSettingsAsAbsent() {
        final ValidatedDashboard absent = validate(dashboard(SQL_WIDGET));
        assertThat(absent.settings()).isNull();
        assertThat(absent.widgets().getFirst().settings()).isNull();

        final ValidatedDashboard explicitNull = validate("{\"name\":\"Weekly\",\"visibility\":\"private\",\"settings\":null,"
                + "\"widgets\":[" + withMembers("\"settings\":null") + "]}");
        assertThat(explicitNull.settings()).isNull();
        assertThat(explicitNull.widgets().getFirst().settings()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[{}]", "\"dark\"", "1", "true"})
    void rejectsSettingsThatIsNotAnObject(String settings) {
        assertRejected("{\"name\":\"Weekly\",\"visibility\":\"private\",\"settings\":" + settings + ",\"widgets\":[]}",
                "'settings'");
        assertRejected(dashboard(withMembers("\"settings\":" + settings)), "widget w1: 'settings'");
    }

    @Test
    void rejectsSettingsOverTheMaximum() {
        final String atMaximum = settingsOfSize(DashboardValidator.MAX_SETTINGS_BYTES);
        final String overMaximum = settingsOfSize(DashboardValidator.MAX_SETTINGS_BYTES + 1);

        assertThatCode(() -> validate("{\"name\":\"Weekly\",\"visibility\":\"private\",\"settings\":" + atMaximum
                + ",\"widgets\":[" + withMembers("\"settings\":" + atMaximum) + "]}")).doesNotThrowAnyException();
        assertRejected("{\"name\":\"Weekly\",\"visibility\":\"private\",\"settings\":" + overMaximum + ",\"widgets\":[]}",
                "'settings'");
        assertRejected(dashboard(withMembers("\"settings\":" + overMaximum)), "widget w1: 'settings'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "1000"})
    void acceptsTheRowLimitBounds(String rowLimit) {
        assertThatCode(() -> validate(dashboard(SQL_WIDGET.replace("\"row_limit\":100", "\"row_limit\":" + rowLimit))))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1001", "2.5", "2.0", "\"5\"", "true", "null", "99999999999999999999"})
    void rejectsRowLimitThatIsNotWholeNumberInRange(String rowLimit) {
        assertRejected(dashboard(SQL_WIDGET.replace("\"row_limit\":100", "\"row_limit\":" + rowLimit)), "'row_limit'");
    }

    @Test
    void rejectsMissingRowLimit() {
        assertRejected(dashboard(SQL_WIDGET.replace("\"row_limit\":100,", "")), "'row_limit'");
    }

    @Test
    void acceptsWidgetOnTheRightEdge() {
        assertThatCode(() -> validate(dashboard(layout("{\"x\":6,\"y\":0,\"w\":6,\"h\":4}")))).doesNotThrowAnyException();
    }

    @Test
    void rejectsWidgetPastTheRightEdge() {
        assertRejected(dashboard(layout("{\"x\":8,\"y\":0,\"w\":6,\"h\":4}")), "is 14");
        assertRejected(dashboard(layout("{\"x\":7,\"y\":0,\"w\":6,\"h\":4}")), "is 13");
    }

    @Test
    void rejectsHugeCoordinateBeforeAddingIt() {
        assertRejected(dashboard(layout("{\"x\":2147483000,\"y\":0,\"w\":1000,\"h\":1}")), "'layout.x'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"x\":12,\"y\":0,\"w\":1,\"h\":1}", "{\"x\":-1,\"y\":0,\"w\":1,\"h\":1}",
            "{\"x\":0,\"y\":-1,\"w\":1,\"h\":1}", "{\"x\":0,\"y\":0,\"w\":0,\"h\":1}", "{\"x\":0,\"y\":0,\"w\":13,\"h\":1}",
            "{\"x\":0,\"y\":0,\"w\":1,\"h\":0}", "{\"x\":2.5,\"y\":0,\"w\":1,\"h\":1}", "{\"x\":\"1\",\"y\":0,\"w\":1,\"h\":1}",
            "{\"y\":0,\"w\":1,\"h\":1}", "{\"x\":0,\"y\":9223372036854775808,\"w\":1,\"h\":1}",
            "{\"x\":0,\"y\":0,\"w\":1,\"h\":100000000000000000000}"})
    void rejectsCoordinateOutOfRangeOrNotAnInteger(String layout) {
        assertRejected(dashboard(layout(layout)), "'layout.");
    }

    @Test
    void rejectsMissingLayout() {
        assertRejected(dashboard("{\"id\":\"w1\",\"saved_query_id\":\"sq_1\",\"row_limit\":1}"), "'layout'");
    }

    @Test
    void acceptsVeryTallWidgetFarDownTheGrid() {
        final DashboardWidget widget = validate(dashboard(layout("{\"x\":0,\"y\":5000000000,\"w\":1,\"h\":5000000000}")))
                .widgets().getFirst();
        assertThat(widget.layout().y()).isEqualTo(5_000_000_000L);
    }

    @Test
    void acceptsOverlappingWidgets() {
        assertThatCode(() -> validate(dashboard(SQL_WIDGET + "," + SQL_WIDGET.replace("\"w1\"", "\"w2\""))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAnInlineQueryWithoutName() {
        assertRejected(dashboard(SQL_WIDGET.replace("\"name\":\"Chats\",", "")), "widget w1: 'inline_query.name'");
    }

    @Test
    void rejectsAnInlineQueryWhoseNameIsBlankOnceTrimmed() {
        assertRejected(dashboard(SQL_WIDGET.replace("\"name\":\"Chats\"", "\"name\":\"\\u0001\"")),
                "widget w1: 'inline_query.name'");
    }

    @Test
    void rejectsAnInlineQueryWithTwoBodiesNamingTheWidget() {
        assertRejected(dashboard(SQL_WIDGET.replace("\"sql\":\"SELECT 1\"",
                        "\"sql\":\"SELECT 1\",\"query\":{\"entity\":\"dial_usage_log\",\"mode\":\"row\"}")),
                "widget w1: exactly one of 'query' or 'sql' must be set, not both");
    }

    @Test
    void treatsBlankInlineSqlAsAbsent() {
        final DashboardWidget widget = validate(dashboard(SQL_WIDGET.replace("\"sql\":\"SELECT 1\"",
                "\"sql\":\"  \",\"query\":{\"entity\":\"dial_usage_log\",\"mode\":\"row\"}"))).widgets().getFirst();
        assertThat(widget.inlineQuery().sql()).isNull();
        assertThat(widget.inlineQuery().query()).isNotNull();
    }

    @Test
    void rejectsAnInlineBodyOverTheSavedQueryMaximum() {
        assertRejected(dashboard(SQL_WIDGET.replace("SELECT 1", "SELECT '" + "x".repeat(70_000) + "'")), "widget w1: ");
    }

    @Test
    void rejectsWidgetsDocumentOverOneMebibyte() {
        // Every body is within the saved-query maximum; only their sum is too large.
        final String widgets = IntStream.range(0, 20)
                .mapToObj(i -> SQL_WIDGET.replace("\"w1\"", "\"w" + i + "\"").replace("SELECT 1", "SELECT '" + "x".repeat(60_000) + "'"))
                .collect(Collectors.joining(","));
        assertRejected(dashboard(widgets), "byte maximum for one dashboard");
    }

    @Test
    void holdsTheTimeIntentToTheSavedQueryRule() {
        assertRejected("{\"name\":\"n\",\"visibility\":\"private\",\"widgets\":[],\"time\":{\"mode\":\"relative\"}}",
                "'time.period'");
        assertRejected("{\"name\":\"n\",\"visibility\":\"private\",\"widgets\":[],\"time\":{\"mode\":\"weekly\"}}", "time.mode");
        assertThat(validate("{\"name\":\"n\",\"visibility\":\"private\",\"widgets\":[],"
                + "\"time\":{\"mode\":\"relative\",\"period\":\"last_7d\"}}").time().period()).isEqualTo("last_7d");
    }

    @Test
    void keepsAnExplicitOffComparison() {
        assertThat(validate("{\"name\":\"n\",\"visibility\":\"private\",\"widgets\":[],\"compare\":\"off\"}").compare())
                .isEqualTo(DashboardCompare.OFF);
        assertThat(validate("{\"name\":\"n\",\"visibility\":\"private\",\"widgets\":[]}").compare()).isNull();
    }

    // ---------------------------------------------------------------- helpers

    private ValidatedDashboard validate(String json) {
        return validator.validate(parse(json), Operation.CREATE);
    }

    private void assertRejected(String json, String message) {
        assertThatThrownBy(() -> validate(json))
                .isInstanceOf(DashboardValidationException.class)
                .hasMessageContaining(message);
    }

    private static DashboardRequestDto parse(String json) {
        return MAPPER.readValue(json, DashboardRequestDto.class);
    }

    private static String dashboard(String widgets) {
        return "{\"name\":\"Weekly\",\"visibility\":\"private\",\"widgets\":[" + widgets + "]}";
    }

    private static String widgets(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> SQL_WIDGET.replace("\"w1\"", "\"w" + i + "\""))
                .collect(Collectors.joining(","));
    }

    private static String withMembers(String members) {
        return SQL_WIDGET.replace("{\"id\":\"w1\",", "{\"id\":\"w1\"," + members + ",");
    }

    /** A settings object whose compact serialization is exactly {@code bytes} long. */
    private static String settingsOfSize(int bytes) {
        final String frame = "{\"k\":\"\"}";
        return "{\"k\":\"" + "x".repeat(bytes - frame.length()) + "\"}";
    }

    private static String layout(String layout) {
        return SQL_WIDGET.replace("{\"x\":0,\"y\":0,\"w\":6,\"h\":4}", layout);
    }

    private static DashboardValidator newValidator() {
        final SavedQueryBodyValidator bodyValidator = new SavedQueryBodyValidator(MAPPER);
        ReflectionTestUtils.setField(bodyValidator, "maxBodyBytes", 65536);
        return new DashboardValidator(bodyValidator, new DashboardJson(MAPPER));
    }
}
