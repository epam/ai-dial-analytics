package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.security.JwtTestTokens;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A change to a role's access entry is an {@code Update} of the table, and its before/after — the revision read at
 * that revision and the one before — must show what changed to a caller who may read grants, and nothing of the
 * grants to one who may not. A write that changes no role set and no policy records no activity at all.
 *
 * <p>The access case runs on an active enrichment, the shape of the reported bug: two {@code Update / Table}
 * rows from two access saves, each of whose detail pages showed no difference because the revision read did
 * not carry the entries.
 */
class TableGrantRevisionIntegrationTest extends AbstractOidcAuthIntegrationTest {

    private static final String BASE = "/v1/tables";
    private static final String SOURCE = "grant_rev_source";
    private static final String ENRICHMENT = "grant_rev_enrichment";
    /** A provider role holding {@code TABLE_GET} globally, so it reaches a deleted table's revisions. */
    private static final String DRAFTER = "drafter";
    private static final String USAGE = "dial_usage_log";
    // Roles no other class names: every test class shares the catalog, and several write dial_usage_log's access.
    private static final String SYS_EARLIER = "sysrev-earlier";
    private static final String SYS_LATER = "sysrev-later";
    private static final String SYS_CONFINED = "sysrev-confined";

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void createTables() throws Exception {
        perform(post(BASE), fullAdmin(), "{\"name\":\"" + SOURCE + "\",\"type\":\"source\"}")
                .andExpect(status().isCreated());
        perform(post(BASE + "/" + SOURCE + "/schema"), fullAdmin(),
                "{\"columns\":[{\"source_name\":\"k\",\"name\":\"k\",\"type\":\"string\"},"
                        + "{\"source_name\":\"project\",\"name\":\"project\",\"type\":\"string\"}],"
                        + "\"ordering_key\":[\"k\"]}")
                .andExpect(status().isOk());
        perform(post(BASE), fullAdmin(),
                "{\"name\":\"" + ENRICHMENT + "\",\"type\":\"enrichment\",\"source_table\":\"" + SOURCE + "\"}")
                .andExpect(status().isCreated());
        perform(post(BASE + "/" + ENRICHMENT + "/schema"), fullAdmin(),
                "{\"grain_key\":\"k\",\"columns\":[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    @AfterEach
    void dropTables() throws Exception {
        mockMvc.perform(delete(BASE + "/" + ENRICHMENT).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())));
        mockMvc.perform(delete(BASE + "/" + SOURCE).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())));
    }

    @Test
    void anAccessChangeIsVisibleInTheRevisionDiff() throws Exception {
        final int before = tableUpdates(ENRICHMENT).size();
        putEntry(ENRICHMENT, "team-a", "\"privileges\":[\"write\"]").andExpect(status().isOk());
        putEntry(ENRICHMENT, "team-b", "\"privileges\":[\"write\"]").andExpect(status().isOk());

        final List<Integer> updates = tableUpdates(ENRICHMENT);
        assertThat(updates).hasSize(before + 2);
        final int revision = updates.getFirst();

        snapshot(ENRICHMENT, revision - 1, fullAdmin())
                .andExpect(jsonPath("$.roles['team-a'].privileges[0]").value("write"))
                .andExpect(jsonPath("$.roles['team-b']").doesNotExist());
        snapshot(ENRICHMENT, revision, fullAdmin())
                .andExpect(jsonPath("$.roles['team-a'].privileges[0]").value("write"))
                .andExpect(jsonPath("$.roles['team-b'].privileges[0]").value("write"))
                .andExpect(jsonPath("$.roles['team-b'].rows").value(nullValue()))
                .andExpect(jsonPath("$.access").doesNotExist());
    }

    @Test
    void rowPolicyChangeIsVisibleInTheRevisionDiff() throws Exception {
        putEntry(SOURCE, "team-x", "\"rows\":" + projectIn("P1")).andExpect(status().isOk());
        final int created = tableUpdates(SOURCE).getFirst();
        perform(delete(BASE + "/" + SOURCE + "/access?role=team-x"), fullAdmin(), "")
                .andExpect(status().isNoContent());
        final int removed = tableUpdates(SOURCE).getFirst();
        assertThat(removed).isGreaterThan(created);

        snapshot(SOURCE, created - 1, fullAdmin()).andExpect(jsonPath("$.roles").isEmpty());
        snapshot(SOURCE, created, fullAdmin())
                .andExpect(jsonPath("$.roles['team-x'].rows.filter.op").value("in"))
                .andExpect(jsonPath("$.roles['team-x'].rows.filter.args[0].name").value("project"))
                .andExpect(jsonPath("$.row_policies").doesNotExist());
        snapshot(SOURCE, removed, fullAdmin()).andExpect(jsonPath("$.roles").isEmpty());
    }

    @Test
    void callerWithoutTheGrantPrivilegeReadsNoGrantMembers() throws Exception {
        putEntry(ENRICHMENT, "team-a", "\"privileges\":[\"write\"]").andExpect(status().isOk());
        final int revision = tableUpdates(ENRICHMENT).getFirst();

        snapshot(ENRICHMENT, revision, viewer())
                .andExpect(jsonPath("$.name").value(ENRICHMENT))
                .andExpect(jsonPath("$.roles").doesNotExist());
        // The live reads carry no entries for anyone, the grant holder included.
        mockMvc.perform(get(BASE + "/" + ENRICHMENT).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").doesNotExist());
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name == '" + ENRICHMENT + "')].roles").isEmpty());
    }

    @Test
    void reorderingPrivilegesRecordsNoActivity() throws Exception {
        putEntry(ENRICHMENT, "team-a", "\"privileges\":[\"write\",\"modify\"]").andExpect(status().isOk());
        final int before = tableUpdates(ENRICHMENT).size();

        putEntry(ENRICHMENT, "team-a", "\"privileges\":[\"modify\",\"write\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.privileges[0]").value("write"))
                .andExpect(jsonPath("$.privileges[1]").value("modify"));

        assertThat(tableUpdates(ENRICHMENT)).hasSize(before);
        mockMvc.perform(get(BASE + "/" + ENRICHMENT + "/access?role=team-a").header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(jsonPath("$.privileges[0]").value("write"))
                .andExpect(jsonPath("$.privileges[1]").value("modify"));
    }

    // --- an enrichment's read side is its source's -----------------------------------------------------

    @Test
    void enrichmentSnapshotReportsReadSideFromItsSourcesCurrentLists() throws Exception {
        putEntry(ENRICHMENT, "team-a", "\"privileges\":[\"write\"]").andExpect(status().isOk());
        final int revision = tableUpdates(ENRICHMENT).getFirst();
        putEntry(SOURCE, "team-r", "\"privileges\":[\"read\"]").andExpect(status().isOk());

        snapshot(ENRICHMENT, revision, caller("team-r"))
                .andExpect(jsonPath("$.permissions.get").value(true))
                .andExpect(jsonPath("$.permissions.read").value(true))
                .andExpect(jsonPath("$.permissions.write").value(false));
    }

    @Test
    void listsAndPoliciesLeftOnAnEnrichmentWidenNothing() throws Exception {
        leaveOnEnrichment();

        perform(post("/v1/queries/execute"), caller("team-r"), "{\"entity\":\"" + SOURCE + "\",\"mode\":\"row\","
                + "\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"k\"}}],\"page\":{\"type\":\"offset\",\"limit\":1}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(SOURCE)));
        mockMvc.perform(get(BASE + "/" + ENRICHMENT + "/access").header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles['team-r'].privileges", hasItem("read")))
                .andExpect(jsonPath("$.roles['team-x'].rows.filter.op").value("in"));
        perform(delete(BASE + "/" + ENRICHMENT + "/access?role=team-x"), fullAdmin(), "")
                .andExpect(status().isNoContent());
    }

    @Test
    void theCatalogMigrationClearsAnEnrichmentsReadSideAndPoliciesAndTheSnapshotLags() throws Exception {
        putEntry(SOURCE, "team-s", "\"privileges\":[\"read\",\"modify\"],\"rows\":" + projectIn("P2")).andExpect(status().isOk());
        leaveOnEnrichment();
        final int latest = jdbc.queryForObject("SELECT max(rev) FROM table_definition_aud WHERE name = ?", Integer.class, ENRICHMENT);

        jdbc.execute(new String(new org.springframework.core.io.ClassPathResource(
                "db/migration/postgres/V56__enrichment_access_follows_source.sql").getContentAsByteArray(),
                java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(get(BASE + "/" + ENRICHMENT + "/access").header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(jsonPath("$.roles['team-r']").doesNotExist())
                .andExpect(jsonPath("$.roles['team-x']").doesNotExist())
                .andExpect(jsonPath("$.roles['team-w'].privileges[0]").value("write"))
                .andExpect(jsonPath("$.roles['team-m'].privileges[0]").value("modify"))
                .andExpect(jsonPath("$.roles[*].rows", everyItem(nullValue())));
        mockMvc.perform(get(BASE + "/" + SOURCE + "/access").header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(jsonPath("$.roles['team-s'].privileges[0]").value("read"))
                .andExpect(jsonPath("$.roles['team-s'].privileges[1]").value("modify"))
                .andExpect(jsonPath("$.roles['team-s'].rows.filter.op").value("in"));

        // The migration writes no revision: the latest snapshot still shows what it removed, until the next change.
        snapshot(ENRICHMENT, latest, fullAdmin())
                .andExpect(jsonPath("$.roles['team-r'].privileges", hasItem("read")))
                .andExpect(jsonPath("$.roles['team-x'].rows.filter.op").value("in"));
        putEntry(ENRICHMENT, "team-w2", "\"privileges\":[\"write\"]").andExpect(status().isOk());
        snapshot(ENRICHMENT, tableUpdates(ENRICHMENT).getFirst(), fullAdmin())
                .andExpect(jsonPath("$.roles['team-r']").doesNotExist())
                .andExpect(jsonPath("$.roles['team-x']").doesNotExist())
                .andExpect(jsonPath("$.roles['team-w2'].privileges[0]").value("write"));
    }

    /**
     * Stores, as an earlier build could, read-side lists and a row policy on the enrichment, in the catalog row and in
     * its latest audit row. Raw SQL: the API refuses both, and the clean-up migration has already run.
     */
    private void leaveOnEnrichment() throws Exception {
        putEntry(ENRICHMENT, "team-w", "\"privileges\":[\"write\"]").andExpect(status().isOk());
        putEntry(ENRICHMENT, "team-m", "\"privileges\":[\"modify\"]").andExpect(status().isOk());
        final String access = "{\"get\":[\"team-r\"],\"read\":[\"team-r\"],\"sensitive\":[\"team-r\"],"
                + "\"write\":[\"team-w\"],\"modify\":[\"team-m\"]}";
        final String policies = "{\"team-x\":" + projectIn("P1").replace("\"project\"", "\"label\"") + "}";
        jdbc.update("UPDATE table_definition SET access = ?::jsonb, row_policies = ?::jsonb WHERE name = ?", access, policies, ENRICHMENT);
        jdbc.update("UPDATE table_definition_aud SET access = ?::jsonb, row_policies = ?::jsonb WHERE name = ?"
                + " AND rev = (SELECT max(rev) FROM table_definition_aud WHERE name = ?)", access, policies, ENRICHMENT, ENRICHMENT);
    }

    @Test
    void repeatingTheSameRowPolicyPutRecordsOneActivity() throws Exception {
        final int before = tableUpdates(SOURCE).size();

        final long first = ((Number) JsonPath.read(putEntry(SOURCE, "team-x", "\"rows\":" + projectIn("P1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.generation")).longValue();
        // An unchanged entry neither moves the generation nor records a revision.
        putEntry(SOURCE, "team-x", "\"rows\":" + projectIn("P1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(first))
                .andExpect(jsonPath("$.rows.filter.op").value("in"));

        assertThat(tableUpdates(SOURCE)).hasSize(before + 1);
    }

    // --- column policies in snapshots -----------------------------------------------------------------

    @Test
    void snapshotHidesColumnsByTheRevisionsOwnColumnPolicies() throws Exception {
        putEntry(SOURCE, "team-r", "\"privileges\":[\"read\"],\"columns\":[\"k\"]").andExpect(status().isOk());
        final int restricted = tableUpdates(SOURCE).getFirst();
        putEntry(SOURCE, "team-r", "\"privileges\":[\"read\"]").andExpect(status().isOk());
        final int lifted = tableUpdates(SOURCE).getFirst();

        snapshot(SOURCE, restricted, fullAdmin()).andExpect(jsonPath("$.roles['team-r'].columns[0]").value("k"));
        snapshot(SOURCE, restricted, caller("team-r"))
                .andExpect(jsonPath("$.columns[*].name", hasItem("k")))
                .andExpect(jsonPath("$.columns[*].name", org.hamcrest.Matchers.not(hasItem("project"))))
                .andExpect(jsonPath("$.permissions.columns_restricted").value(true));
        snapshot(SOURCE, lifted, caller("team-r"))
                .andExpect(jsonPath("$.columns[*].name", hasItem("project")))
                .andExpect(jsonPath("$.permissions.columns_restricted").value(false));
        // The live read follows the live entry.
        mockMvc.perform(get(BASE + "/" + SOURCE).header(HttpHeaders.AUTHORIZATION, bearer(caller("team-r"))))
                .andExpect(jsonPath("$.columns[*].name", hasItem("project")));
    }

    @Test
    void revisionRecordedBeforeColumnPoliciesReadsNoneAndIsJudgedByTheCurrentPolicies() throws Exception {
        putEntry(SOURCE, "team-r", "\"privileges\":[\"read\"],\"columns\":[\"k\"]").andExpect(status().isOk());
        final int revision = tableUpdates(SOURCE).getFirst();
        // What a revision written before V57 (or by an older replica) holds: no column_policies at all.
        jdbc.update("UPDATE table_definition_aud SET column_policies = NULL WHERE name = ? AND rev = ?", SOURCE, revision);

        snapshot(SOURCE, revision, fullAdmin())
                .andExpect(jsonPath("$.roles['team-r'].privileges[0]").value("read"))
                .andExpect(jsonPath("$.roles['team-r'].columns").value(nullValue()));
        // The current entry still restricts team-r, so the column stays hidden in the old revision too.
        snapshot(SOURCE, revision, caller("team-r"))
                .andExpect(jsonPath("$.columns[*].name", org.hamcrest.Matchers.not(hasItem("project"))))
                .andExpect(jsonPath("$.permissions.columns_restricted").value(true));
        putEntry(SOURCE, "team-r", "\"privileges\":[\"read\"]").andExpect(status().isOk());
        snapshot(SOURCE, revision, caller("team-r")).andExpect(jsonPath("$.columns[*].name", hasItem("project")));
    }

    @Test
    void revisionOlderThanPolicyHidesTheColumnFromTheCallerItNowRestricts() throws Exception {
        putEntry(SOURCE, "team-r", "\"privileges\":[\"read\"]").andExpect(status().isOk());
        final int unrestricted = tableUpdates(SOURCE).getFirst();
        putEntry(SOURCE, "team-r", "\"privileges\":[\"read\"],\"columns\":[\"k\"]").andExpect(status().isOk());

        snapshot(SOURCE, unrestricted, fullAdmin()).andExpect(jsonPath("$.roles['team-r'].columns").value(nullValue()));
        snapshot(SOURCE, unrestricted, caller("team-r"))
                .andExpect(jsonPath("$.columns[*].name", hasItem("k")))
                .andExpect(jsonPath("$.columns[*].name", org.hamcrest.Matchers.not(hasItem("project"))))
                .andExpect(jsonPath("$.permissions.columns_restricted").value(true));
        snapshot(SOURCE, unrestricted, fullAdmin()).andExpect(jsonPath("$.columns[*].name", hasItem("project")));

        // An old name today's allowlist does not list stays hidden: there is no rename mapping.
        putEntry(SOURCE, "team-r", "\"privileges\":[\"read\"],\"columns\":[\"k\",\"project\"]").andExpect(status().isOk());
        perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(BASE + "/" + SOURCE + "/schema"), fullAdmin(),
                "{\"rename\":[{\"from\":\"project\",\"to\":\"proj\"}]}").andExpect(status().isOk());
        snapshot(SOURCE, unrestricted, caller("team-r"))
                .andExpect(jsonPath("$.columns[*].name", org.hamcrest.Matchers.not(hasItem("project"))));
        live(SOURCE, caller("team-r")).andExpect(jsonPath("$.columns[*].name", hasItem("proj")));
    }

    @Test
    void enrichmentSnapshotHidesColumnsByItsSourcesCurrentPolicies() throws Exception {
        putEntry(ENRICHMENT, "team-w", "\"privileges\":[\"write\"]").andExpect(status().isOk());
        final int revision = tableUpdates(ENRICHMENT).getFirst();
        putEntry(SOURCE, "team-r", "\"privileges\":[\"read\"],\"columns\":[\"k\"]").andExpect(status().isOk());

        snapshot(ENRICHMENT, revision, caller("team-r"))
                .andExpect(jsonPath("$.columns[*].name", org.hamcrest.Matchers.not(hasItem("label"))))
                .andExpect(jsonPath("$.permissions.columns_restricted").value(true));
        live(ENRICHMENT, caller("team-r"))
                .andExpect(jsonPath("$.columns[*].name", org.hamcrest.Matchers.not(hasItem("label"))))
                .andExpect(jsonPath("$.permissions.columns_restricted").value(true));
        // Every column of the enrichment, its system columns included.
        final List<String> every = JsonPath.read(mockMvc.perform(get(BASE + "/" + ENRICHMENT).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andReturn().getResponse().getContentAsString(), "$.columns[*].name");
        putEntry(SOURCE, "team-r", "\"privileges\":[\"read\"],\"columns\":[\"k\"" + every.stream().map(name -> ",\"" + ENRICHMENT + "." + name + "\"")
                .collect(java.util.stream.Collectors.joining()) + "]").andExpect(status().isOk());
        snapshot(ENRICHMENT, revision, caller("team-r"))
                .andExpect(jsonPath("$.columns[*].name", hasItem("label")))
                .andExpect(jsonPath("$.permissions.columns_restricted").value(false));
        live(ENRICHMENT, caller("team-r")).andExpect(jsonPath("$.permissions.columns_restricted").value(false));
    }

    /** A snapshot's flag judges the revision's own columns, not the columns the table has today. */
    @Test
    void snapshotColumnsRestrictedJudgesTheRevisionsColumns() throws Exception {
        // Names every column at this revision: nothing is hidden then, though a column added later is.
        putEntry(SOURCE, "team-r", "\"privileges\":[\"read\"],\"columns\":[\"k\",\"project\"]").andExpect(status().isOk());
        final int complete = tableUpdates(SOURCE).getFirst();
        perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(BASE + "/" + SOURCE + "/schema"), fullAdmin(),
                "{\"add\":[{\"source_name\":\"extra\",\"name\":\"extra\",\"type\":\"string\"}]}").andExpect(status().isOk());
        live(SOURCE, caller("team-r")).andExpect(jsonPath("$.permissions.columns_restricted").value(true));
        snapshot(SOURCE, complete, caller("team-r"))
                .andExpect(jsonPath("$.columns[*].name", org.hamcrest.Matchers.containsInAnyOrder("k", "project")))
                .andExpect(jsonPath("$.permissions.columns_restricted").value(false));

        // Hides `extra` at this revision; once `extra` is dropped, today's columns are all listed, but the revision's are not.
        putEntry(SOURCE, "team-r", "\"privileges\":[\"read\"],\"columns\":[\"k\",\"project\"],\"rows\":" + projectIn("P1")).andExpect(status().isOk());
        final int hiding = tableUpdates(SOURCE).getFirst();
        perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(BASE + "/" + SOURCE + "/schema"), fullAdmin(),
                "{\"drop\":[\"extra\"]}").andExpect(status().isOk());
        live(SOURCE, caller("team-r")).andExpect(jsonPath("$.permissions.columns_restricted").value(false));
        snapshot(SOURCE, hiding, caller("team-r"))
                .andExpect(jsonPath("$.columns[*].name", org.hamcrest.Matchers.not(hasItem("extra"))))
                .andExpect(jsonPath("$.permissions.columns_restricted").value(true));
    }

    // --- a revision is judged by the life of the table that contains it ------------------------------

    @Test
    void deletedTablesRevisionIsJudgedByThePoliciesItLastHad() throws Exception {
        final String table = "life_rev_deleted";
        createSource(table, "k", "project");
        putEntry(table, DRAFTER, "\"privileges\":[\"read\"]").andExpect(status().isOk());
        final int revision = tableUpdates(table).getFirst();
        jdbc.update("UPDATE table_definition_aud SET column_policies = NULL WHERE name = ? AND rev = ?", table, revision);
        putEntry(table, DRAFTER, "\"privileges\":[\"read\"],\"columns\":[\"k\"]").andExpect(status().isOk());
        drop(table);

        snapshot(table, revision, caller(DRAFTER))
                .andExpect(jsonPath("$.columns[*].name", org.hamcrest.Matchers.contains("k")))
                .andExpect(jsonPath("$.permissions.columns_restricted").value(true));
    }

    @Test
    void tableCreatedAgainDoesNotJudgeAnEarlierLifesRevisions() throws Exception {
        final String table = "life_rev_recreated";
        createSource(table, "k", "project");
        putEntry(table, DRAFTER, "\"privileges\":[\"read\"]").andExpect(status().isOk());
        final int earlier = tableUpdates(table).getFirst();
        putEntry(table, DRAFTER, "\"privileges\":[\"read\"],\"columns\":[\"k\"]").andExpect(status().isOk());
        drop(table);
        createSource(table, "k", "m");
        try {
            snapshot(table, earlier, caller(DRAFTER)).andExpect(jsonPath("$.columns[*].name", org.hamcrest.Matchers.contains("k")));

            putEntry(table, DRAFTER, "\"privileges\":[\"read\"],\"columns\":[\"m\"]").andExpect(status().isOk());
            snapshot(table, tableUpdates(table).getFirst(), caller(DRAFTER))
                    .andExpect(jsonPath("$.columns[*].name", org.hamcrest.Matchers.contains("m")));
            snapshot(table, earlier, caller(DRAFTER)).andExpect(jsonPath("$.columns[*].name", org.hamcrest.Matchers.contains("k")));
        } finally {
            drop(table);
        }
    }

    @Test
    void enrichmentSnapshotIsJudgedByItsDeletedSourcesLastPolicies() throws Exception {
        putEntry(ENRICHMENT, "team-w", "\"privileges\":[\"write\"]").andExpect(status().isOk());
        final int revision = tableUpdates(ENRICHMENT).getFirst();
        putEntry(SOURCE, DRAFTER, "\"privileges\":[\"read\"],\"columns\":[\"k\",\"project\"]").andExpect(status().isOk());
        drop(ENRICHMENT);
        drop(SOURCE);

        snapshot(ENRICHMENT, revision, caller(DRAFTER)).andExpect(jsonPath("$.columns").isEmpty());

        // Read with no columns: every column shows, so the state comes from the trail, not from the fail-closed fallback.
        final String source = "life_rev_open_source";
        final String enrichment = "life_rev_open_enrichment";
        createSource(source, "k", "project");
        createEnrichment(enrichment, source);
        putEntry(enrichment, "team-w", "\"privileges\":[\"write\"]").andExpect(status().isOk());
        final int open = tableUpdates(enrichment).getFirst();
        putEntry(source, DRAFTER, "\"privileges\":[\"read\"]").andExpect(status().isOk());
        drop(enrichment);
        drop(source);

        snapshot(enrichment, open, caller(DRAFTER))
                .andExpect(jsonPath("$.columns[*].name", hasItem("label")))
                .andExpect(jsonPath("$.permissions.columns_restricted").value(false));
    }

    @Test
    void enrichmentCreatedAgainDoesNotJudgeAnEarlierLifesRevisions() throws Exception {
        putEntry(SOURCE, DRAFTER, "\"privileges\":[\"read\"],\"columns\":[\"k\"]").andExpect(status().isOk());
        putEntry(ENRICHMENT, "team-w", "\"privileges\":[\"write\"]").andExpect(status().isOk());
        final int revision = tableUpdates(ENRICHMENT).getFirst();
        drop(ENRICHMENT);
        createEnrichment(ENRICHMENT, SOURCE);
        putEntry(SOURCE, DRAFTER, "\"privileges\":[\"read\"],\"columns\":[\"k\",\"" + ENRICHMENT + ".label\"]").andExpect(status().isOk());

        live(ENRICHMENT, caller(DRAFTER)).andExpect(jsonPath("$.columns[*].name", hasItem("label")));
        snapshot(ENRICHMENT, revision, caller(DRAFTER))
                .andExpect(jsonPath("$.columns[*].name", org.hamcrest.Matchers.not(hasItem("label"))));
    }

    @Test
    void eachLifeOfTableRecreatedTwiceIsJudgedByItsOwnDeletion() throws Exception {
        final String table = "life_rev_thrice";
        createSource(table, "k", "m");
        putEntry(table, DRAFTER, "\"privileges\":[\"read\"]").andExpect(status().isOk());
        final int first = tableUpdates(table).getFirst();
        putEntry(table, DRAFTER, "\"privileges\":[\"read\"],\"columns\":[\"k\"]").andExpect(status().isOk());
        drop(table);
        createSource(table, "k", "m");
        putEntry(table, DRAFTER, "\"privileges\":[\"read\"]").andExpect(status().isOk());
        final int second = tableUpdates(table).getFirst();
        putEntry(table, DRAFTER, "\"privileges\":[\"read\"],\"columns\":[\"m\"]").andExpect(status().isOk());
        drop(table);
        createSource(table, "k", "m");
        try {
            snapshot(table, first, caller(DRAFTER)).andExpect(jsonPath("$.columns[*].name", org.hamcrest.Matchers.contains("k")));
            snapshot(table, second, caller(DRAFTER)).andExpect(jsonPath("$.columns[*].name", org.hamcrest.Matchers.contains("m")));
        } finally {
            drop(table);
        }
    }

    // --- a system table's revisions: its creation is not in the trail, its columns are not tracked ---

    @Test
    void systemTableAccessChangesAreReadableAtTheirRevisions() throws Exception {
        try {
            putEntry(USAGE, SYS_EARLIER, "\"privileges\":[\"read\"]").andExpect(status().isOk());
            final int earlier = tableUpdates(USAGE).getFirst();
            putEntry(USAGE, SYS_LATER, "\"privileges\":[\"read\"],\"rows\":" + projectIn("P1").replace("\"project\"", "\"project_id\"")).andExpect(status().isOk());
            final int later = tableUpdates(USAGE).getFirst();

            snapshot(USAGE, earlier, fullAdmin())
                    .andExpect(jsonPath("$.system").value(true))
                    .andExpect(jsonPath("$.roles['" + SYS_EARLIER + "'].privileges[0]").value("read"))
                    .andExpect(jsonPath("$.roles['" + SYS_LATER + "']").doesNotExist());
            snapshot(USAGE, later, fullAdmin())
                    .andExpect(jsonPath("$.system").value(true))
                    .andExpect(jsonPath("$.roles['" + SYS_LATER + "'].privileges[0]").value("read"))
                    .andExpect(jsonPath("$.roles['" + SYS_LATER + "'].rows.filter.args[0].name").value("project_id"))
                    .andExpect(jsonPath("$.columns").doesNotExist());
            snapshot(USAGE, later, viewer())
                    .andExpect(jsonPath("$.roles").doesNotExist())
                    .andExpect(jsonPath("$.columns").doesNotExist());
        } finally {
            removeEntry(USAGE, SYS_LATER);
            removeEntry(USAGE, SYS_EARLIER);
        }
    }

    @Test
    void systemTableSnapshotJudgesColumnsRestrictedWithoutColumns() throws Exception {
        try {
            putEntry(USAGE, SYS_CONFINED, "\"privileges\":[\"read\"],\"columns\":[\"event_id\"]").andExpect(status().isOk());
            final int revision = tableUpdates(USAGE).getFirst();

            snapshot(USAGE, revision, caller(SYS_CONFINED))
                    .andExpect(jsonPath("$.columns").doesNotExist())
                    .andExpect(jsonPath("$.permissions.columns_restricted").value(true));
            snapshot(USAGE, revision, fullAdmin()).andExpect(jsonPath("$.permissions.columns_restricted").value(false));
        } finally {
            removeEntry(USAGE, SYS_CONFINED);
        }
    }

    /**
     * "An older revision lists a masked column": {@code grev-ra} reads the rows of {@code P1} with every column, and
     * {@code grev-rb}, added later, those of {@code P2} with {@code k}, so {@code project} is masked now and listed in the
     * revision recorded before {@code grev-rb}'s column policy existed.
     */
    @Test
    void olderRevisionListsMaskedColumn() throws Exception {
        putEntry(SOURCE, "grev-ra", "\"privileges\":[\"read\"],\"rows\":" + projectIn("P1")).andExpect(status().isOk());
        final int revision = tableUpdates(SOURCE).getFirst();
        putEntry(SOURCE, "grev-rb", "\"privileges\":[\"read\"],\"rows\":" + projectIn("P2") + ",\"columns\":[\"k\"]").andExpect(status().isOk());

        snapshot(SOURCE, revision, callerWith("grev-ra", "grev-rb"))
                .andExpect(jsonPath("$.columns[*].name", hasItem("project")))
                .andExpect(jsonPath("$.permissions.columns_restricted").value(true));
        live(SOURCE, callerWith("grev-ra", "grev-rb")).andExpect(jsonPath("$.columns[*].name", hasItem("project")));
    }

    @Test
    void systemTableSnapshotReportsColumnsRestrictedForMaskedOnlyCaller() throws Exception {
        try {
            putEntry(USAGE, "sysrev-mask-a", "\"privileges\":[\"read\"],\"rows\":{\"where\":\"project_id = 'P1'\"}").andExpect(status().isOk());
            putEntry(USAGE, "sysrev-mask-b", "\"privileges\":[\"read\"],\"rows\":{\"where\":\"project_id = 'P2'\"},\"columns\":[\"event_id\"]")
                    .andExpect(status().isOk());
            final int revision = tableUpdates(USAGE).getFirst();

            snapshot(USAGE, revision, callerWith("sysrev-mask-a", "sysrev-mask-b"))
                    .andExpect(jsonPath("$.columns").doesNotExist())
                    .andExpect(jsonPath("$.permissions.columns_restricted").value(true));
        } finally {
            removeEntry(USAGE, "sysrev-mask-a");
            removeEntry(USAGE, "sysrev-mask-b");
        }
    }

    @Test
    void systemTableSnapshotIsTheRecordedStateAndNotFoundBeforeIt() throws Exception {
        final String table = "sysrev_seeded";
        // Seeded as a migration seeds a system table: a live row and no audit row, so its first recorded state is known.
        jdbc.update("INSERT INTO table_definition (name, type, status, write_mode, system, ordering_key) "
                + "VALUES (?, 'SOURCE', 'ACTIVE', 'APPEND', true, '{k}')", table);
        try {
            putEntry(SOURCE, SYS_EARLIER, "\"privileges\":[\"read\"]").andExpect(status().isOk());
            final int before = tableUpdates(SOURCE).getFirst();
            putEntry(table, SYS_EARLIER, "\"privileges\":[\"read\"]").andExpect(status().isOk());
            final int first = tableUpdates(table).getFirst();

            // No definition row exists at `before`, so this 404 holds with or without the system exemption; the 200s below need it.
            mockMvc.perform(get(BASE + "/" + table + "/revision/" + before).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("revision_not_found"));
            snapshot(table, first, fullAdmin())
                    .andExpect(jsonPath("$.roles['" + SYS_EARLIER + "'].privileges[0]").value("read"))
                    .andExpect(jsonPath("$.columns").doesNotExist())
                    .andExpect(jsonPath("$.ordering_key", org.hamcrest.Matchers.contains("k")));

            // A migration's change to the live row, outside the trail: the snapshot keeps the recorded state until the next change.
            jdbc.update("UPDATE table_definition SET ordering_key = '{m}' WHERE name = ?", table);
            snapshot(table, first, fullAdmin()).andExpect(jsonPath("$.ordering_key", org.hamcrest.Matchers.contains("k")));
            putEntry(table, SYS_LATER, "\"privileges\":[\"read\"]").andExpect(status().isOk());
            snapshot(table, tableUpdates(table).getFirst(), fullAdmin())
                    .andExpect(jsonPath("$.ordering_key", org.hamcrest.Matchers.contains("m")));
        } finally {
            jdbc.update("DELETE FROM audit_activity WHERE resource_id = ?", table);
            jdbc.update("DELETE FROM table_definition_aud WHERE name = ?", table);
            jdbc.update("DELETE FROM table_definition WHERE name = ?", table);
        }
    }

    /** Best effort: the entry is absent when the test failed before writing it. */
    private void removeEntry(String table, String role) throws Exception {
        mockMvc.perform(delete(BASE + "/" + table + "/access?role=" + role).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())));
    }

    private void createEnrichment(String enrichment, String source) throws Exception {
        perform(post(BASE), fullAdmin(), "{\"name\":\"" + enrichment + "\",\"type\":\"enrichment\",\"source_table\":\"" + source + "\"}")
                .andExpect(status().isCreated());
        perform(post(BASE + "/" + enrichment + "/schema"), fullAdmin(),
                "{\"grain_key\":\"k\",\"columns\":[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\"}]}")
                .andExpect(status().isOk());
    }

    private void createSource(String table, String... columns) throws Exception {
        perform(post(BASE), fullAdmin(), "{\"name\":\"" + table + "\",\"type\":\"source\"}").andExpect(status().isCreated());
        perform(post(BASE + "/" + table + "/schema"), fullAdmin(), "{\"columns\":[" + java.util.Arrays.stream(columns)
                .map(name -> "{\"source_name\":\"" + name + "\",\"name\":\"" + name + "\",\"type\":\"string\"}")
                .collect(java.util.stream.Collectors.joining(",")) + "],\"ordering_key\":[\"" + columns[0] + "\"]}")
                .andExpect(status().isOk());
    }

    private void drop(String table) throws Exception {
        mockMvc.perform(delete(BASE + "/" + table).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(status().isNoContent());
    }

    private ResultActions live(String table, String token) throws Exception {
        return mockMvc.perform(get(BASE + "/" + table).header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk());
    }

    // --- helpers --------------------------------------------------------------------------------------

    /** PUTs {@code role}'s whole entry on {@code table}: {@code members} plus the table's current generation. */
    private ResultActions putEntry(String table, String role, String members) throws Exception {
        final String access = mockMvc.perform(get(BASE + "/" + table + "/access").header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return perform(put(BASE + "/" + table + "/access?role=" + role), fullAdmin(),
                "{\"generation\":" + ((Number) JsonPath.read(access, "$.generation")).longValue() + "," + members + "}");
    }

    /** The revisions of the table's {@code Update / Table} activities, newest first. */
    private List<Integer> tableUpdates(String table) throws Exception {
        final String feed = mockMvc.perform(post("/v1/activities")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageSize\":1000,\"filters\":["
                                + "{\"column\":\"resourceId\",\"operator\":\"eq\",\"value\":\"" + table + "\"},"
                                + "{\"column\":\"resourceType\",\"operator\":\"eq\",\"value\":\"Table\"},"
                                + "{\"column\":\"activityType\",\"operator\":\"eq\",\"value\":\"Update\"}],"
                                + "\"sorts\":[{\"column\":\"revision\",\"direction\":\"DESC\"}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(feed, "$.data[*].revision");
    }

    private ResultActions snapshot(String table, int revision, String token) throws Exception {
        return mockMvc.perform(get(BASE + "/" + table + "/revision/" + revision)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        return mockMvc.perform(request
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String projectIn(String project) {
        return "{\"filter\":{\"op\":\"in\",\"args\":[{\"type\":\"field\",\"name\":\"project\"},"
                + "{\"type\":\"array\",\"items\":[{\"type\":\"value\",\"value_type\":\"string\",\"value\":\""
                + project + "\"}]}]}}";
    }

    /** A caller carrying only the unmapped provider role {@code role}: whatever it holds, a grant gave it. */
    private static String caller(String role) {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", role + "-user", "roles", List.of(role), "email", role + "@test"));
    }

    /** A caller carrying the unmapped provider roles {@code roles}, together. */
    private static String callerWith(String... roles) {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", String.join("-", roles) + "-user", "roles", List.of(roles), "email", "multi@test"));
    }

    private static String fullAdmin() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", "full-user", "roles", List.of("admin"), "email", "full@test"));
    }

    /** {@code READ_ONLY_ADMIN}: may read every table and the audit feed, but not grants. */
    private static String viewer() {
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE,
                Map.of("sub", "viewer-user", "roles", List.of("viewer"), "email", "viewer@test"));
    }
}
