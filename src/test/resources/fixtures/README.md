# Registry fixtures

**Synthetic. Modelled on the shapes of a real registry, not copied from it.** Every name, prompt and
domain here is invented; what is reproduced is the *structure* — the specific shapes that make this
change's assertions meaningful. Real declarations are deliberately not used as test resources: their
prompts carry organisation-specific policy, they would go stale while looking authoritative, and a test
should state the property it depends on rather than inherit it by accident.

The rationale for these shapes is in
`openspec/changes/archive/2026-09-10-simplify-pipeline-authoring/design.md`. If a structural fact below
stops being true of real registries, the fixture is still a valid test of the rule — but the fact should
be re-checked before it is relied on in an argument.

**Every file here is read by a test.** A fixture nothing loads asserts nothing, and a README describing
its properties would then be a second copy of a fact with nothing cross-checking it — which is the
defect this change exists to remove. Three earlier files (`enrichment-targets.json` and the two `sql`
transforms) were removed for exactly that reason: `ResponseSchemaComposer` and
`TemplatePlaceholderRule` are both `llm`-only, so no unit test could ever consume them, and the shapes
they described are covered by `EnrichmentTypeMappingTest` and `Class1MaterializeIntegrationTest`
against their own inline declarations. Add a fixture here together with the test that reads it.

## Structural properties each fixture encodes

Each file is a `transform` block reduced to the members `TemplatePlaceholderRuleTest` reads — `type`,
`preset`, `model`, `params` and the `request_template` it validates.

### `transforms/widget-insights.json` — `llm`, group grain

- A template whose placeholders are only the `members_*` built-ins and `{{members}}`, naming none of the
  four vars the test declares: at group grain a var is a field of each member object inside
  `{{members}}`, so a valid group template names none of them. This is the property that makes the
  naive template rule wrong.

### `transforms/widget-tags.json` — `llm`, group grain

- A second group template, declared with fewer vars than `widget-insights`, so the group rule is not
  proved against a single shape.

### `transforms/widget-summary.json` — `llm`, **row grain**

- One var, referenced once as a top-level `{{placeholder}}` — the row-grain rule's positive case.
