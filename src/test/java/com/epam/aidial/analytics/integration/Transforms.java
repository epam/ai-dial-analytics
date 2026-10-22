package com.epam.aidial.analytics.integration;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@code transform} member an {@code enrich} pipeline body carries, for the integration tests.
 *
 * <p>A builder rather than a handful of overloads because the block is type-dispatched and the tests vary
 * in exactly which members they need: an {@code llm} declaration carries a model, a request template,
 * inputs and prose outputs; a {@code sql} one carries only expressions; and a fixture standing in for a
 * <em>folded legacy</em> declaration carries {@code output_vars} and a stored {@code response_schema}
 * instead of {@code outputs}. Raw JSON strings rather than DTOs, matching the surrounding tests: they
 * assert the wire shape, so building it through the same records the server binds would assert less.
 *
 * <p>Member order is the order declared here, and for {@code outputs} that is load-bearing — it becomes
 * the composed response schema's property order, hence the model's generation order.
 */
final class Transforms {

    private Transforms() {
    }

    /** An {@code llm} transform: a model is required, and every output is prose. */
    static Builder llm() {
        return new Builder("llm").model("gpt-4o").preset("chat_completion").requestTemplate("{}");
    }

    /** A {@code sql} transform: no model, no preset, no template — every output is an expression. */
    static Builder sql() {
        return new Builder("sql");
    }

    static final class Builder {

        private final String type;
        private String model;
        private String preset;
        private String requestTemplate;
        private String params;
        private String responseSchema;
        private final List<String> inputs = new ArrayList<>();
        private final List<String> outputs = new ArrayList<>();
        private String outputVars;

        private Builder(String type) {
            this.type = type;
        }

        Builder model(String value) {
            this.model = value;
            return this;
        }

        Builder preset(String value) {
            this.preset = value;
            return this;
        }

        /** The request template, as the raw JSON string it is on the wire (escaping included). */
        Builder requestTemplate(String value) {
            this.requestTemplate = value;
            return this;
        }

        /** Preset parameters, as a raw JSON object. */
        Builder params(String json) {
            this.params = json;
            return this;
        }

        /** One {@code transform.inputs} entry in the bare-string form: a variable bound to a column. */
        Builder input(String var, String column) {
            inputs.add("\"" + var + "\":\"" + column + "\"");
            return this;
        }

        /**
         * One output for {@code column}, in the bare-string form — prose for {@code llm}, the expression
         * for {@code sql}. Which it means is selected by the transform's own {@code type}, exactly as on
         * the wire.
         */
        Builder output(String column, String value) {
            outputs.add("\"" + column + "\":\"" + value + "\"");
            return this;
        }

        /** One output for {@code column} in the object form, as raw JSON. */
        Builder outputObject(String column, String json) {
            outputs.add("\"" + column + "\":" + json);
            return this;
        }

        /** One prose output per column, with a fixed prose string — the common {@code llm} fixture. */
        Builder proseOutputs(String... columns) {
            for (final String column : columns) {
                output(column, "Prose the model is told about this field.");
            }
            return this;
        }

        /**
         * The <b>legacy</b> output shape, as a raw JSON array — for a fixture standing in for a
         * declaration folded from a definition that predates {@code outputs}. Refused together with
         * {@code outputs}, and refused outright on {@code POST}, so a test using it patches it in.
         */
        Builder outputVars(String json) {
            this.outputVars = json;
            return this;
        }

        /** A stored response schema, which wins over composition while it is present. */
        Builder responseSchema(String json) {
            this.responseSchema = json;
            return this;
        }

        /** The block itself: {@code {"type": …}}. */
        String json() {
            final List<String> members = new ArrayList<>();
            members.add("\"type\":\"" + type + "\"");
            if (model != null) {
                members.add("\"model\":\"" + model + "\"");
            }
            if (preset != null) {
                members.add("\"preset\":\"" + preset + "\"");
            }
            if (params != null) {
                members.add("\"params\":" + params);
            }
            if (requestTemplate != null) {
                members.add("\"request_template\":\"" + requestTemplate.replace("\"", "\\\"") + "\"");
            }
            if (!inputs.isEmpty()) {
                members.add("\"inputs\":{" + String.join(",", inputs) + "}");
            }
            if (!outputs.isEmpty()) {
                members.add("\"outputs\":{" + String.join(",", outputs) + "}");
            }
            if (outputVars != null) {
                members.add("\"output_vars\":" + outputVars);
            }
            if (responseSchema != null) {
                members.add("\"response_schema\":" + responseSchema);
            }
            return "{" + String.join(",", members) + "}";
        }

        /** The block as the member a pipeline body carries: {@code "transform": {…}}. */
        String member() {
            return "\"transform\":" + json();
        }

        @Override
        public String toString() {
            return member();
        }
    }
}
