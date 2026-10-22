package com.epam.aidial.analytics.service.enrichment.validation;

import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rule relating an {@code llm} transform's {@code request_template} placeholders to the pipeline's
 * {@code vars} — <b>dispatched on the trigger's grain</b>, because what a var <em>is</em> differs by grain.
 *
 * <p><b>The obvious rule is wrong, and would brick every working group pipeline.</b> "Every
 * {@code {{...}}} must be a {@code vars} key and every {@code vars} key must appear as a placeholder"
 * holds only at row grain. For a group trigger the render inputs are <em>only</em> the {@code members_*}
 * variables: the runner's group-input assembler maps each member row through its input mapper and hands
 * the transform a five-entry map, so each {@code vars} key becomes a <b>field of each member object inside
 * {@code {{members}}}</b> and never a top-level placeholder, so a working group template typically mentions
 * none of its vars. And because a patch re-validates the <em>stored</em> declaration before arming, the naive
 * rule would not merely refuse a new registration — it would refuse a routine disable/enable of a stored
 * group pipeline.
 *
 * <p>A separate class, and a <b>pure</b> one, deliberately: this is the highest-risk rule in the change
 * (the failure mode is refusing a declaration that works today), and keeping it free of repositories is
 * what lets it be tested directly against the real live templates rather than only through a container.
 *
 * <h2>The built-ins are five, not three</h2>
 *
 * <p>{@link #MEMBER_BUILT_INS} mirrors the runner's {@code GroupInputAssembler} constants —
 * {@code MEMBERS_VAR}, {@code TRUNCATED_VAR}, {@code INCLUDED_VAR}, {@code OMITTED_VAR} and
 * {@code NOTICE_VAR} — all five of which its {@code AssembledInput.asInputs()} puts into the render map.
 * ADAS owns this list as its own wire contract and asserts the names as literals, so a sixth built-in is a
 * two-repo change by construction rather than a valid template that fails registration. A list short of
 * all five would refuse a template that renders correctly.
 */
public final class TemplatePlaceholderRule {

    /** The member variable that carries the group's rows. Mandatory in a group template. */
    public static final String MEMBERS = "members";

    /**
     * Every variable the group-input assembly supplies, in the runner's own declaration order.
     *
     * <p>Mirror of the runner's {@code GroupInputAssembler} constants. It cannot be imported — separate
     * repository — so it is asserted as literals in {@code TemplatePlaceholderRuleTest}, with the runner
     * holding the reciprocal assertion against the spec.
     */
    public static final List<String> MEMBER_BUILT_INS =
            List.of(MEMBERS, "members_truncated", "members_included", "members_omitted", "members_notice");

    /**
     * A {@code {{name}}} placeholder. Whitespace inside the braces is tolerated and the name is the
     * trimmed token, so a template written {@code {{ members }}} is read as the same variable rather than
     * as an unknown one — the renderer substitutes on the key, and refusing a spacing variant here would
     * refuse a template that renders correctly.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    private TemplatePlaceholderRule() {
    }

    /**
     * Validates one declaration's template against its {@code vars}.
     *
     * @param template the transform's {@code request_template}. A blank one returns without a verdict
     *     because it is not a placeholder problem — it is a missing prompt, and
     *     {@code EnrichSpecValidator.requireValidLlmShape} owns it, refusing a blank template on every
     *     write. That ownership is what makes the early return safe: without it this return would be a
     *     hole through which a template-less declaration reached the model, taking the
     *     every-input-appears direction and the mandatory {@code {{members}}} check with it.
     * @param varNames the declaration's {@code vars} keys
     * @param triggerKind the pipeline's trigger, which selects the rule
     * @throws PipelineValidationException naming the offending variable
     */
    public static void validate(String template, Set<String> varNames, TriggerKind triggerKind) {
        if (StringUtils.isBlank(template)) {
            return;
        }
        final Set<String> placeholders = placeholdersOf(template);
        if (triggerKind == TriggerKind.GROUP) {
            validateGroupGrain(placeholders);
            return;
        }
        validateRowGrain(placeholders, varNames);
    }

    /**
     * Group grain: the placeholders are exactly the built-ins, {@code {{members}}} is mandatory, and
     * {@code vars} are <b>not</b> checked in either direction — they are consumed as member fields inside
     * {@code {{members}}}, and a valid group template names none of them.
     *
     * <p>{@code {{members}}} being mandatory is the strongest check in the whole set: without it the model
     * receives a prompt with no data in it at all.
     */
    private static void validateGroupGrain(Set<String> placeholders) {
        for (final String placeholder : placeholders) {
            if (!MEMBER_BUILT_INS.contains(placeholder)) {
                throw new PipelineValidationException("request_template references '{{" + placeholder
                        + "}}', which a group-triggered pipeline does not supply: at group grain the render"
                        + " inputs are only " + MEMBER_BUILT_INS + ", and a declared var is a field of each"
                        + " member object inside {{members}} rather than a top-level placeholder");
            }
        }
        if (!placeholders.contains(MEMBERS)) {
            throw new PipelineValidationException("request_template must reference {{" + MEMBERS + "}} for a"
                    + " group-triggered pipeline; without it the rendered prompt carries no group data at all");
        }
    }

    /**
     * Row grain: every placeholder is a {@code vars} key and every {@code vars} key is a placeholder, with
     * <b>no</b> built-ins — the row-grain render supplies only the declared bindings.
     *
     * <p>The placeholder direction matters for a sharper reason than a typo rendering blank: the runner's
     * renderer replaces only keys <em>present in the inputs map</em>, so an unmatched {@code {{requeest}}}
     * is left in the prompt <b>literally</b>. (Empty is what a bound var with a null value renders as.)
     * The reverse direction catches a var that projects a column — possibly a heavy one — for nothing.
     *
     * <p>A declared var <b>wins over the built-in name</b>, and the order of the two checks below is what
     * says so. At row grain nothing supplies the {@code members_*} variables, so their names carry no
     * reserved meaning here — a pipeline that binds a var called {@code members} renders exactly as it
     * reads. Checking the built-in first would refuse that declaration while the refusal's own message
     * told the author to bind a var of that name, which is advice the check would then reject.
     */
    private static void validateRowGrain(Set<String> placeholders, Set<String> varNames) {
        for (final String placeholder : placeholders) {
            if (varNames.contains(placeholder)) {
                continue;
            }
            if (MEMBER_BUILT_INS.contains(placeholder)) {
                throw new PipelineValidationException("request_template references the group variable '{{"
                        + placeholder + "}}', but this pipeline fires at row grain, where the render supplies"
                        + " only the declared vars; use a group trigger, or bind a var of that name");
            }
            throw new PipelineValidationException("request_template references '{{" + placeholder
                    + "}}', which no var declares; an unmatched placeholder is left in the prompt"
                    + " literally rather than rendered empty, so declare the var or fix the spelling");
        }
        for (final String var : varNames) {
            if (!placeholders.contains(var)) {
                throw new PipelineValidationException("var '" + var + "' appears in no {{placeholder}} of"
                        + " request_template, so it is projected from the source and never used; remove it"
                        + " or reference it");
            }
        }
    }

    /** The distinct placeholder names a template references, in first-appearance order. */
    public static Set<String> placeholdersOf(String template) {
        final Set<String> names = new LinkedHashSet<>();
        final Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
