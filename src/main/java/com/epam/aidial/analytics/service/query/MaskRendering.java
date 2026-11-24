package com.epam.aidial.analytics.service.query;

import org.jooq.Context;
import org.jooq.Field;
import org.jooq.QueryPart;
import org.jooq.SelectQuery;
import org.jooq.VisitContext;
import org.jooq.VisitListener;
import org.jooq.impl.CustomField;
import org.jooq.impl.DSL;
import org.jooq.impl.QOM;
import org.jooq.impl.SQLDataType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Renders each {@link ColumnMask} of a statement once per SELECT scope: the first reference outside every lambda body
 * renders {@code (ifNull(<or>, 0) AS __adas_mcond_<i>)} and every other reference in the scope, lambda bodies
 * included, the bare alias. ClickHouse resolves an alias anywhere in its SELECT scope — before its definition, from
 * {@code WHERE}, {@code GROUP BY}, {@code HAVING}, {@code ORDER BY}, from an aggregate's argument and from a lambda body —
 * while an alias defined inside a lambda body is visible only there and in the bodies nested in it. A scope whose every
 * reference sits in lambda bodies therefore defines the alias at its first reference in each body entered before any
 * enclosing body defined it.
 *
 * <p>Which reference defines is decided while each scope renders, not when a condition is built, because one translated
 * condition can render in more than one scope (the key subquery receives the outer filter). {@link #render} therefore
 * renders the statement twice: the first pass records, per scope, which masks are referenced outside lambda bodies and
 * every name the statement declares (aliases, lambda parameters); the second emits, with each alias chosen apart from
 * every declared name, so no name a caller writes ever captures or shadows one. Scopes are numbered in visit order, which
 * the two passes share, through the {@link #scopeListener() listener} the render context carries. A reference rendered
 * outside {@link #render}, or by a context without the listener, inlines the whole condition, which is correct and only
 * longer.
 */
public final class MaskRendering {

    /** The prefix of a mask condition's alias; the suffix is advanced past every name the statement declares. */
    public static final String ALIAS_PREFIX = "__adas_mcond_";

    private static final ThreadLocal<State> STATE = new ThreadLocal<>();

    private static final VisitListener LISTENER = new VisitListener() {
        @Override
        public void visitStart(VisitContext context) {
            final State state = STATE.get();
            if (state == null || !state.renders(context.context())) {
                return;
            }
            final QueryPart part = context.queryPart();
            if (part instanceof SelectQuery<?>) {
                state.scopes.push(new Scope(state.nextScope++));
            } else if (!state.emitting && part instanceof QOM.FieldAlias<?> alias) {
                state.declared.add(alias.$alias().last());
            } else if (!state.emitting && part instanceof QOM.TableAlias<?> alias) {
                state.declared.add(alias.$alias().last());
            }
        }

        @Override
        public void visitEnd(VisitContext context) {
            final State state = STATE.get();
            if (state != null && state.renders(context.context()) && context.queryPart() instanceof SelectQuery<?>
                    && !state.scopes.isEmpty()) {
                state.scopes.pop();
            }
        }
    };

    private MaskRendering() {
    }

    /** The listener the render context carries, so the masks know which SELECT scope they render in. */
    public static VisitListener scopeListener() {
        return LISTENER;
    }

    /**
     * The SQL {@code render} produces, with every mask rendered once per scope. {@code render} runs twice and must
     * render the same statement both times.
     */
    public static String render(Supplier<String> render) {
        final State outer = STATE.get();
        final State state = new State();
        STATE.set(state);
        try {
            render.get();
            state.emit();
            return render.get();
        } finally {
            if (outer == null) {
                STATE.remove();
            } else {
                STATE.set(outer);
            }
        }
    }

    /**
     * A lambda body over parameter {@code param}: rendered as {@code body}, marking where a mask reference sits inside a
     * lambda and recording the parameter as a declared name.
     */
    public static Field<?> lambdaBody(String param, Field<?> body) {
        return new LambdaBody(param, body);
    }

    /** Renders one reference to {@code mask}, whose condition text is {@code definition}. */
    static void reference(ColumnMask mask, Field<Boolean> definition, Context<?> ctx) {
        final State state = STATE.get();
        // A render of a part on its own, nested in the statement's (jOOQ renders one to compare or print it), inlines.
        final Scope scope = state == null || !state.renders(ctx) ? null : state.scopes.peek();
        if (scope == null) {
            ctx.visit(definition);
            return;
        }
        if (!state.emitting) {
            state.seen.add(mask);
            if (scope.lambdas.isEmpty()) {
                state.outer.computeIfAbsent(scope.index, ignored -> new HashSet<>()).add(mask);
            }
            ctx.visit(definition);
            return;
        }
        final String alias = state.aliases.get(mask);
        if (alias == null) {
            ctx.visit(definition);
            return;
        }
        final boolean defines;
        if (scope.lambdas.isEmpty()) {
            defines = scope.defined.add(mask);
        } else if (state.outer.getOrDefault(scope.index, Set.of()).contains(mask)) {
            defines = false;
        } else {
            // Visible in this body and the bodies nested in it, never in the body enclosing it.
            defines = scope.lambdas.peek().add(mask);
        }
        if (defines) {
            ctx.sql('(').visit(definition).sql(" AS ").visit(DSL.name(alias)).sql(')');
        } else {
            ctx.visit(DSL.name(alias));
        }
    }

    /** One render's bookkeeping, across its two passes. */
    private static final class State {
        private boolean emitting;
        private int nextScope;
        private final Deque<Scope> scopes = new ArrayDeque<>();
        /** By scope number, the masks referenced outside every lambda body there (first pass). */
        private final Map<Integer, Set<ColumnMask>> outer = new HashMap<>();
        /** Every mask referenced, in first-reference order (first pass). */
        private final Set<ColumnMask> seen = new LinkedHashSet<>();
        /** Every alias and lambda parameter the statement declares (first pass). */
        private final Set<String> declared = new HashSet<>();
        private final Map<ColumnMask, String> aliases = new HashMap<>();
        /** The render context of the statement in the current pass: the first one seen, which renders its outer SELECT. */
        private Context<?> statement;

        /** Whether {@code ctx} renders the statement, rather than a part of it on its own; claims the first context seen. */
        private boolean renders(Context<?> ctx) {
            if (statement == null) {
                statement = ctx;
            }
            return statement == ctx;
        }

        /** Ends the first pass: names each mask apart from every declared name. */
        private void emit() {
            emitting = true;
            nextScope = 0;
            scopes.clear();
            statement = null;
            int next = 0;
            for (final ColumnMask mask : seen) {
                String alias;
                do {
                    alias = ALIAS_PREFIX + next++;
                } while (declared.contains(alias));
                aliases.put(mask, alias);
            }
        }
    }

    /** One SELECT scope being rendered. */
    private static final class Scope {
        private final int index;
        private final Set<ColumnMask> defined = new HashSet<>();
        /** Per lambda body being rendered, innermost first: the masks defined in it or in a body enclosing it. */
        private final Deque<Set<ColumnMask>> lambdas = new ArrayDeque<>();

        private Scope(int index) {
            this.index = index;
        }
    }

    /** A lambda body, rendered as itself. */
    private static final class LambdaBody extends CustomField<Object> {

        private final String param;
        private final Field<?> body;

        private LambdaBody(String param, Field<?> body) {
            super(DSL.name("lambda"), SQLDataType.OTHER);
            this.param = param;
            this.body = body;
        }

        @Override
        public void accept(Context<?> ctx) {
            final State state = STATE.get();
            if (state != null && !state.emitting) {
                state.declared.add(param);
            }
            final Scope scope = state == null || !state.renders(ctx) ? null : state.scopes.peek();
            if (scope == null) {
                ctx.visit(body);
                return;
            }
            scope.lambdas.push(scope.lambdas.isEmpty() ? new HashSet<>() : new HashSet<>(scope.lambdas.peek()));
            try {
                ctx.visit(body);
            } finally {
                scope.lambdas.pop();
            }
        }
    }
}
