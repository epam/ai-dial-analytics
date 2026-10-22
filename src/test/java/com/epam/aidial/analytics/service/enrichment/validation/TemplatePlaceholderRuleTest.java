package com.epam.aidial.analytics.service.enrichment.validation;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The assertion this rule had to be written against, not after.
 *
 * <p>{@link TemplatePlaceholderRule} is the highest-risk piece of this change, and its failure mode is not
 * "it lets something bad through" — it is <b>refusing a declaration that already works</b>. Because a patch
 * re-validates the <em>stored</em> declaration before arming, getting it wrong would refuse a routine
 * disable/enable of a live group pipeline. So the first tests here run templates read from the fixtures
 * rather than written inline, in the shape a group transform actually has: every placeholder a
 * {@code members_*} built-in, and not one of its declared vars mentioned anywhere.
 *
 * <p>The fixtures are <b>synthetic</b> — modelled on the shapes of a real registry, not copied from it (see
 * {@code fixtures/README.md}). That is a deliberate limit on what these tests prove: they establish that
 * the rule accepts a declaration <em>shaped like</em> real ones, not that it accepts real ones
 * themselves. Re-validating real stored declarations is a deployment-verification step, and the fixture
 * carries the structural property that makes the shape faithful.
 */
class TemplatePlaceholderRuleTest {

    /**
     * The vars a group pipeline declares — none of which appears in its template, which is the whole
     * point: at group grain a var is a field of each member object rendered inside {@code {{members}}}.
     */
    private static final Set<String> GROUP_VARS =
            new LinkedHashSet<>(List.of("request", "response", "handler", "parent_handler"));

    private static String fixtureTemplate(String fixture) {
        try (InputStream in = TemplatePlaceholderRuleTest.class
                .getResourceAsStream("/fixtures/transforms/" + fixture)) {
            final JsonNode transform = new JsonMapperConfiguration().jsonMapper().readTree(in);
            return transform.get("request_template").asString();
        } catch (Exception ex) {
            throw new IllegalStateException("cannot read fixture " + fixture, ex);
        }
    }

    // --- the group-grain shape, which must keep passing ----------------------------------------------

    @Test
    void groupTemplateMentioningNoneOfItsVarsIsAccepted() {
        // The shape a real group transform has: the template names built-ins and NOT ONE of its four
        // declared vars. The naive "every var must appear as a placeholder" rule refuses exactly this, and
        // refusing it would brick the re-arm of a live group pipeline.
        assertThatCode(() -> TemplatePlaceholderRule.validate(
                fixtureTemplate("widget-insights.json"), GROUP_VARS, TriggerKind.GROUP))
                .doesNotThrowAnyException();
    }

    @Test
    void secondGroupTemplateWithFewerVarsIsAlsoAccepted() {
        assertThatCode(() -> TemplatePlaceholderRule.validate(
                fixtureTemplate("widget-tags.json"),
                new LinkedHashSet<>(List.of("request", "handler")), TriggerKind.GROUP))
                .doesNotThrowAnyException();
    }

    @Test
    void groupFixtureTemplatesUseOnlyBuiltInsAndAlwaysMembers() {
        // Pins the structural property the fixtures exist to carry, so that if one is edited into a shape
        // a real registry does not have, this fails rather than the rule quietly testing nothing.
        for (final String fixture : List.of("widget-insights.json", "widget-tags.json")) {
            assertThat(TemplatePlaceholderRule.placeholdersOf(fixtureTemplate(fixture)))
                    .containsExactlyInAnyOrder("members_included", "members_notice", "members")
                    .allSatisfy(name -> assertThat(TemplatePlaceholderRule.MEMBER_BUILT_INS).contains(name));
        }
    }

    @Test
    void rowGrainTemplateIsAccepted() {
        // One var, referenced once as a top-level placeholder — the row-grain positive case.
        assertThatCode(() -> TemplatePlaceholderRule.validate(
                fixtureTemplate("widget-summary.json"), Set.of("request"), TriggerKind.SCHEDULE))
                .doesNotThrowAnyException();
    }

    // --- the built-in list is a wire contract --------------------------------------------------------

    @Test
    void theFiveBuiltInNamesArePinnedAsLiterals() {
        // Mirror of the runner's GroupInputAssembler constants — MEMBERS_VAR, TRUNCATED_VAR, INCLUDED_VAR,
        // OMITTED_VAR, NOTICE_VAR — every one of which its AssembledInput.asInputs() puts into the render
        // map. It cannot be imported (separate repository), so it is asserted here and the runner holds the
        // reciprocal assertion. A list short of all five refuses a template that renders correctly.
        assertThat(TemplatePlaceholderRule.MEMBER_BUILT_INS).containsExactly(
                "members", "members_truncated", "members_included", "members_omitted", "members_notice");
    }

    @Test
    void groupTemplateUsingAllFiveBuiltInsIsAccepted() {
        assertThatCode(() -> TemplatePlaceholderRule.validate(
                "{{members}} {{members_truncated}} {{members_included}} {{members_omitted}}"
                        + " {{members_notice}}", GROUP_VARS, TriggerKind.GROUP))
                .doesNotThrowAnyException();
    }

    // --- group grain refusals ------------------------------------------------------------------------

    @Test
    void groupTemplateWithNoMembersPlaceholderIsRefused() {
        // The strongest check of the set: without it the model receives a prompt with no data in it.
        assertThatThrownBy(() -> TemplatePlaceholderRule.validate(
                "Session ({{members_included}} included). {{members_notice}}",
                GROUP_VARS, TriggerKind.GROUP))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("must reference {{members}}")
                .hasMessageContaining("carries no group data");
    }

    @Test
    void groupTemplateReferencingVarDirectlyIsRefused() {
        // At group grain a var is a field of each member object inside {{members}}, so a top-level
        // {{request}} is supplied by nothing and would render literally.
        assertThatThrownBy(() -> TemplatePlaceholderRule.validate(
                "{{members}} and {{request}}", GROUP_VARS, TriggerKind.GROUP))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("{{request}}")
                .hasMessageContaining("field of each member object");
    }

    // --- row grain refusals --------------------------------------------------------------------------

    @Test
    void rowGrainPlaceholderWithNoVarIsRefused() {
        assertThatThrownBy(() -> TemplatePlaceholderRule.validate(
                "Body: {{requeest}}", Set.of("request"), TriggerKind.SCHEDULE))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("{{requeest}}")
                // The reason it matters: the renderer replaces only keys present in the inputs map, so an
                // unmatched placeholder survives into the prompt verbatim rather than rendering empty.
                .hasMessageContaining("left in the prompt");
    }

    @Test
    void rowGrainVarWithNoPlaceholderIsRefused() {
        assertThatThrownBy(() -> TemplatePlaceholderRule.validate(
                "Body: {{request}}", new LinkedHashSet<>(List.of("request", "response")),
                TriggerKind.SCHEDULE))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("'response'")
                .hasMessageContaining("never used");
    }

    @Test
    void builtInAtRowGrainIsRefused() {
        assertThatThrownBy(() -> TemplatePlaceholderRule.validate(
                "{{members}}", Set.of(), TriggerKind.SCHEDULE))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("fires at row grain");
    }

    /**
     * A declared var <b>wins over the built-in name</b> at row grain, where nothing supplies the
     * {@code members_*} variables and so their names carry no reserved meaning. The refusal above tells
     * the author to bind a var of that name; this is the test that the advice works.
     */
    @Test
    void builtInNameBoundAsRowGrainVarIsAccepted() {
        assertThatCode(() -> TemplatePlaceholderRule.validate(
                "Members: {{members}}", Set.of("members"), TriggerKind.SCHEDULE))
                .doesNotThrowAnyException();
    }

    @Test
    void scheduleTriggerFollowsTheRowGrainRule() {
        // `schedule` is row grain too — only `group` changes what a var is.
        assertThatCode(() -> TemplatePlaceholderRule.validate(
                "Body: {{request}}", Set.of("request"), TriggerKind.SCHEDULE))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> TemplatePlaceholderRule.validate(
                "{{members}}", Set.of(), TriggerKind.SCHEDULE))
                .isInstanceOf(PipelineValidationException.class);
    }

    // --- extraction ----------------------------------------------------------------------------------

    @Test
    void spacingInsideBracesNamesTheSameVariable() {
        // The renderer substitutes on the key, so refusing a spacing variant would refuse a template that
        // renders correctly.
        assertThat(TemplatePlaceholderRule.placeholdersOf("{{ members }}")).containsExactly("members");
    }

    @Test
    void blankTemplateIsLeftToTheShapeCheckThatOwnsIt() {
        assertThatCode(() -> TemplatePlaceholderRule.validate(null, Set.of("request"), TriggerKind.SCHEDULE))
                .doesNotThrowAnyException();
        assertThatCode(() -> TemplatePlaceholderRule.validate("  ", Set.of("request"), TriggerKind.SCHEDULE))
                .doesNotThrowAnyException();
    }
}
