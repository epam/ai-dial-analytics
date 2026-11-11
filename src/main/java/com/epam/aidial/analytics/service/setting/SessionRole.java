package com.epam.aidial.analytics.service.setting;

import com.epam.aidial.analytics.web.dto.query.QueryFieldType;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Function;

/**
 * A session role of the sessions-source setting: its wire name, whether it must be bound, and the flat
 * field types a bound field may have. The type sets are deliberately strict — {@code enum} and {@code uuid}
 * read as strings on every query surface, but are not accepted for the string roles.
 */
public enum SessionRole {

    SESSION_ID("session_id", true, EnumSet.of(QueryFieldType.STRING), SessionsSourceRoles::sessionId),
    TIME("time", true, EnumSet.of(QueryFieldType.TIMESTAMP), SessionsSourceRoles::time),
    PROJECT("project", false, EnumSet.of(QueryFieldType.STRING), SessionsSourceRoles::project),
    TITLE("title", false, EnumSet.of(QueryFieldType.STRING), SessionsSourceRoles::title),
    COST("cost", false, EnumSet.of(QueryFieldType.DECIMAL, QueryFieldType.INTEGER, QueryFieldType.LONG), SessionsSourceRoles::cost),
    TURNS("turns", false, EnumSet.of(QueryFieldType.INTEGER, QueryFieldType.LONG), SessionsSourceRoles::turns);

    private final String wireName;
    private final boolean required;
    private final Set<QueryFieldType> accepted;
    private final Function<SessionsSourceRoles, String> binding;

    SessionRole(String wireName, boolean required, Set<QueryFieldType> accepted, Function<SessionsSourceRoles, String> binding) {
        this.wireName = wireName;
        this.required = required;
        this.accepted = Set.copyOf(accepted);
        this.binding = binding;
    }

    public String wireName() {
        return wireName;
    }

    public boolean required() {
        return required;
    }

    public Set<QueryFieldType> accepted() {
        return accepted;
    }

    /** The flat field name {@code roles} binds to this role, or {@code null} when unset. */
    public String boundIn(SessionsSourceRoles roles) {
        return binding.apply(roles);
    }
}
