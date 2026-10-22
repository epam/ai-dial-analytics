package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.exception.TableValidationException;

import java.util.regex.Pattern;

/**
 * Validates and quotes ClickHouse identifiers (table and column names), plus the one
 * <em>non</em>-identifier name the catalog domain owns — a pipeline's. Three grammars are enforced:
 *
 * <ul>
 *   <li>{@link #requireUserIdentifier} — the spec grammar for <em>user-declared</em> table and column
 *       names: snake_case {@code ^[a-z][a-z0-9_]*$}, at most 64 chars, and no leading {@code _}
 *       (the {@code _}-prefix is reserved for system columns). Applied at the API/validation layer.</li>
 *   <li>{@link #require}/{@link #quote} — a slightly looser grammar that also admits a leading
 *       {@code _} so ADAS can quote its own system columns ({@code _updated_at}, {@code _ingested_at})
 *       and the backfilled {@code dial_usage_log} columns when rendering DDL. Still injection-safe:
 *       the catalog is the one place ADAS builds SQL from stored names rather than from a jOOQ model.</li>
 *   <li>{@link #requirePipelineName} — an <b>identity</b> grammar, not an identifier one:
 *       {@code ^[a-z][a-z0-9_-]{0,63}$}. A pipeline name never becomes a ClickHouse identifier, so what
 *       this grammar exists for is not injection safety but the properties out-of-process consumers
 *       depend on. See its own javadoc.</li>
 * </ul>
 */
public final class Identifiers {

    private static final int MAX_LENGTH = 64;
    /** User-declared names: snake_case, no leading underscore (reserved for system columns). */
    private static final Pattern USER = Pattern.compile("^[a-z][a-z0-9_]*$");
    /** Renderable names: as above but a leading underscore is allowed (system / backfilled columns). */
    private static final Pattern RENDERABLE = Pattern.compile("^[a-z_][a-z0-9_]*$");
    /** Pipeline identities: snake_case or kebab-case, no leading underscore, no upper case, no {@code #}. */
    private static final Pattern PIPELINE_NAME = Pattern.compile("^[a-z][a-z0-9_-]{0,63}$");

    private Identifiers() {
    }

    /**
     * Validates a user-declared table or column name against the spec grammar (snake_case, ≤64 chars,
     * no reserved {@code _}-prefix); returns it on success, else throws {@link TableValidationException}.
     */
    public static String requireUserIdentifier(String name) {
        if (name == null || name.length() > MAX_LENGTH || !USER.matcher(name).matches()) {
            throw new TableValidationException("invalid identifier '" + name
                    + "': must be snake_case (^[a-z][a-z0-9_]*$), at most " + MAX_LENGTH
                    + " chars, and must not start with '_' (reserved for system columns)");
        }
        return name;
    }

    /**
     * Validates a pipeline name against the <b>identity</b> grammar and returns it, else throws
     * {@link PipelineValidationException}.
     *
     * <p>Deliberately not {@link #requireUserIdentifier}. That is the grammar for names that become SQL,
     * and a pipeline name never does — the compiler quotes a pipeline's {@code target} and its measure and
     * column names, never its own name. Using the identifier grammar here would also throw the wrong
     * exception type, reporting a pipeline failure under the table validation code.
     *
     * <p>What the exclusions are actually for:
     *
     * <ul>
     *   <li><b>{@code #}</b> — the Enrichment Runner builds {@code #}-delimited work-item identifiers and
     *       counts a pipeline's in-flight work by its {@code name + "#"} prefix. The names {@code a#b} and
     *       {@code a} would share that prefix, so one pipeline's backpressure would count another's work.
     *       A UUID identity could not contain a {@code #}; a free-form name can, which is why this guard
     *       arrived with the fold.</li>
     *   <li><b>Upper case and a leading {@code _}</b> — so an identity is unambiguous in the paths, logs
     *       and metric tags that carry it.</li>
     * </ul>
     *
     * <p>Hyphens <b>are</b> admitted, and that is a deliberate widening rather than an oversight.
     * Kebab-case is the enrichment sub-domain's established convention (its pipeline names are
     * kebab-case), while snake_case is the catalog's <em>because</em> catalog names become SQL. A registry
     * holding both kinds admits both spellings rather than forcing one sub-domain onto the other's
     * convention.
     */
    public static String requirePipelineName(String name) {
        if (name == null || !PIPELINE_NAME.matcher(name).matches()) {
            throw new PipelineValidationException("invalid pipeline name '" + name
                    + "': must match ^[a-z][a-z0-9_-]{0,63}$ — a lower-case letter followed by up to 63"
                    + " lower-case letters, digits, underscores or hyphens");
        }
        return name;
    }

    /** Returns {@code name} if it is a renderable identifier, else throws {@link TableValidationException}. */
    public static String require(String name) {
        if (name == null || name.length() > MAX_LENGTH || !RENDERABLE.matcher(name).matches()) {
            throw new TableValidationException("invalid identifier: '" + name + "'");
        }
        return name;
    }

    /** Backtick-quotes a validated (renderable) identifier for inclusion in rendered ClickHouse DDL. */
    public static String quote(String name) {
        return "`" + require(name) + "`";
    }
}
