-- Remove every enrichment's get, read and sensitive lists and its row policies; openspec/specs/table-access-control/spec.md
UPDATE table_definition
   SET access = access - 'get' - 'read' - 'sensitive',
       row_policies = '{}'
 WHERE type = 'ENRICHMENT';
