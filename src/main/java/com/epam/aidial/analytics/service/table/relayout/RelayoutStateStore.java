package com.epam.aidial.analytics.service.table.relayout;

import com.epam.aidial.analytics.exception.TableRelayoutInProgressException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The persisted progress of each automatic relayout ({@code enrichment_relayout}), and the writer hold every write
 * path consults. Plain JDBC rather than an entity: the row is operational state of a background job, not catalog
 * state, so it has no place in the audit trail or the catalog's transactions.
 */
@Component
@RequiredArgsConstructor
public class RelayoutStateStore {

    /**
     * How long a hold stands without its row being saved again. The relayout saves the row at every step and
     * refreshes it while it waits inside the hold, so only a hold whose instance died outlives this, and writers are
     * refused for at most this long after such a crash.
     */
    static final Duration HOLD_EXPIRY = Duration.ofMinutes(10);

    private final JdbcTemplate jdbc;

    /**
     * One table's relayout progress. Marks are {@code _updated_at} values, as epoch milliseconds; {@code copiedThrough} is
     * the last grain key, as text, that a completed slice of the history copy copied through.
     */
    public record State(String table, RelayoutStep step, String copyTable, String copiedThrough, Long copyMark,
            Long catchUpMark, boolean hold, String error) {

        State withStep(RelayoutStep next) {
            return new State(table, next, copyTable, copiedThrough, copyMark, catchUpMark, hold, error);
        }
    }

    public Optional<State> find(String table) {
        final List<State> rows = jdbc.query("SELECT * FROM enrichment_relayout WHERE table_name = ?", RelayoutStateStore::map, table);
        return rows.stream().findFirst();
    }

    public List<State> all() {
        return jdbc.query("SELECT * FROM enrichment_relayout ORDER BY table_name", RelayoutStateStore::map);
    }

    /** Records that {@code table}'s relayout has started, or that it was skipped ({@link RelayoutStep#UNSUPPORTED}). */
    public void start(String table, RelayoutStep step, String copyTable, String error) {
        jdbc.update("INSERT INTO enrichment_relayout (table_name, step, copy_table, error, started_at, updated_at) "
                + "VALUES (?, ?, ?, ?, now(), now()) ON CONFLICT (table_name) DO UPDATE SET step = EXCLUDED.step, "
                + "copy_table = EXCLUDED.copy_table, error = EXCLUDED.error, copied_through = NULL, copy_mark = NULL, "
                + "catch_up_mark = NULL, hold = FALSE, started_at = EXCLUDED.started_at, updated_at = EXCLUDED.updated_at",
                table, step.name(), copyTable, error);
    }

    /** Persists every progress field of {@code state}, and refreshes the row's {@code updated_at} (see {@link #HOLD_EXPIRY}). */
    public void save(State state) {
        jdbc.update("UPDATE enrichment_relayout SET step = ?, copied_through = ?, copy_mark = ?, catch_up_mark = ?, "
                + "hold = ?, error = ?, updated_at = now() WHERE table_name = ?",
                state.step().name(), state.copiedThrough(), state.copyMark(), state.catchUpMark(), state.hold(),
                state.error(), state.table());
    }

    /** Releases {@code table}'s writer hold, leaving every progress field as it is. */
    public void release(String table) {
        jdbc.update("UPDATE enrichment_relayout SET hold = FALSE, updated_at = now() WHERE table_name = ?", table);
    }

    /**
     * Refuses a write to {@code table} while its relayout holds its writers. Read on every write, from Postgres, so
     * the hold one instance sets stops every instance's writers. A hold not saved for {@link #HOLD_EXPIRY} — its
     * instance gone mid-hold — no longer stands; judged by the database's clock, the one clock every instance shares.
     *
     * @throws TableRelayoutInProgressException (503) while the hold stands
     */
    public void requireNotHeld(String table) {
        final List<Boolean> held = jdbc.queryForList("SELECT hold FROM enrichment_relayout WHERE table_name = ? "
                + "AND updated_at > now() - make_interval(secs => ?::double precision)", Boolean.class, table, HOLD_EXPIRY.toSeconds());
        if (!held.isEmpty() && Boolean.TRUE.equals(held.getFirst())) {
            throw new TableRelayoutInProgressException("table '" + table + "' is being relaid out by ADAS and refuses "
                    + "writes for a few minutes; retry the same write shortly");
        }
    }

    private static State map(ResultSet rs, int row) throws SQLException {
        return new State(rs.getString("table_name"), RelayoutStep.valueOf(rs.getString("step")),
                rs.getString("copy_table"), rs.getString("copied_through"), (Long) rs.getObject("copy_mark"),
                (Long) rs.getObject("catch_up_mark"), rs.getBoolean("hold"), rs.getString("error"));
    }
}
