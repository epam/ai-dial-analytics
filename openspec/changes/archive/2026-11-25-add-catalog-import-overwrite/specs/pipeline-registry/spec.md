## MODIFIED Requirements

### Requirement: Imported pipelines are registered disabled through the registration path

A catalog import SHALL register each pipeline through the same registration step as `POST /v1/pipelines`, from its `view=source`
projection, and SHALL always register it disabled, as defined by [catalog-export-import](../catalog-export-import/spec.md) ("Pipelines
are imported disabled"). Arming an imported pipeline is the ordinary `PATCH enabled=true` and runs the declaration gate described
above. An import SHALL change an existing pipeline only under `resolution_policy=OVERWRITE`, and only through the same declaration
patch as `PATCH /v1/pipelines/{name}`, with every rule of that path in force. In particular:

- the patch bumps `generation` and resets the execution state ADAS owns for the kind;
- an `enrich` pipeline is re-declared in place, its `enabled` state kept, and passes the arm-time gate when it stays enabled;
- an enabled `aggregate` is disabled by an `enabled: false` patch before it is re-declared, and is left disabled.

Its `name`, `kind` and `target` are never changed by an import. A pipeline name that has a delete revision in the audit trail and
was once enabled is refused unless the import acknowledges it, because the Enrichment Runner's state is keyed by name ("Re-used
pipeline name guard").

#### Scenario: An imported pipeline is armed separately

- **WHEN** a pipeline is created by a catalog import
- **THEN** it is stored with `enabled: false` at generation 1, and only a later `PATCH enabled=true` arms it

#### Scenario: An import re-declares through the patch path

- **WHEN** an `OVERWRITE` import re-declares a disabled `aggregate` pipeline at generation 2
- **THEN** it is stored at generation 3 with its cursor reset, still disabled, and the generation-change log line gives the cause
  `patch(re-declare)`
