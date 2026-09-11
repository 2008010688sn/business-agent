-- Demo seed for standalone Business Agent.
-- Tenant must match OpenAccessFilter (default). No IM connectors, no business MCP.

SET search_path TO agent, public;

INSERT INTO data_agent (
  id,
  tenant_id,
  agent_type,
  name,
  description,
  status,
  prompt,
  category,
  runtime_timeout_seconds,
  create_by,
  create_name,
  deleted
) VALUES (
  1,
  'default',
  'knowledge_base',
  'Demo Agent',
  'Published knowledge-base agent for local demo.',
  'published',
  'You are a helpful knowledge assistant. Use bound skills and uploaded documents when they are available.',
  'demo',
  180,
  'sn68',
  'Demo User',
  false
);

INSERT INTO data_agent_visibility_policy (
  id,
  tenant_id,
  agent_id,
  conversation_scope,
  catalog_scope,
  apply_mode,
  approval_mode,
  status,
  create_by,
  create_name,
  deleted
) VALUES (
  1,
  'default',
  1,
  'TENANT',
  'TENANT',
  'DISABLED',
  'LOCAL',
  'ENABLED',
  'sn68',
  'Demo User',
  false
);

INSERT INTO data_agent_skill (
  id,
  tenant_id,
  skill_code,
  skill_name,
  description,
  category,
  scope,
  skill_kind,
  execution_mode,
  status,
  display_order,
  create_by,
  create_name,
  deleted
) VALUES (
  1,
  'default',
  'knowledge-qa',
  'Knowledge QA',
  'Demo knowledge question-answering skill (QA + KNOWLEDGE).',
  'demo',
  'TENANT',
  'QA',
  'KNOWLEDGE',
  'PUBLISHED',
  0,
  'sn68',
  'Demo User',
  false
);

INSERT INTO data_agent_skill_version (
  id,
  tenant_id,
  skill_id,
  skill_name,
  description,
  category,
  display_order,
  skill_kind,
  execution_mode,
  version_no,
  status,
  skill_markdown,
  knowledge_config,
  published_at,
  published_by,
  create_by,
  create_name,
  deleted
) VALUES (
  1,
  'default',
  1,
  'Knowledge QA',
  'Demo knowledge question-answering skill (QA + KNOWLEDGE).',
  'demo',
  0,
  'QA',
  'KNOWLEDGE',
  1,
  'PUBLISHED',
  E'# Knowledge QA\n\nAnswer questions using the bound knowledge base. If no document is indexed, say so clearly.',
  '{"enabled": true, "topK": 8}'::jsonb,
  CURRENT_TIMESTAMP,
  'sn68',
  'sn68',
  'Demo User',
  false
);

UPDATE data_agent_skill
SET published_version_id = 1,
    last_modify_time = CURRENT_TIMESTAMP
WHERE id = 1;

INSERT INTO data_agent_skill_binding (
  id,
  tenant_id,
  agent_id,
  skill_id,
  pinned_skill_version_id,
  priority,
  enabled,
  create_by,
  create_name,
  deleted
) VALUES (
  1,
  'default',
  1,
  1,
  1,
  100,
  true,
  'sn68',
  'Demo User',
  false
);

INSERT INTO data_agent_route_profile (
  tenant_id,
  profile_name,
  status,
  lexical_auto_select_enabled,
  semantic_recall_enabled,
  semantic_auto_select_enabled,
  model_disambiguation_enabled,
  lexical_min_score,
  lexical_min_gap,
  vector_recall_threshold,
  vector_auto_select_threshold,
  vector_min_gap,
  model_confidence_threshold,
  build_status,
  create_by,
  create_name,
  deleted
)
SELECT
  'default',
  'demo-lexical-default',
  'ACTIVE',
  true,
  false,
  false,
  false,
  70,
  15,
  0.50,
  0.85,
  0.10,
  0.80,
  'SKIPPED',
  'sn68',
  'Demo User',
  false
WHERE NOT EXISTS (
  SELECT 1 FROM data_agent_route_profile
  WHERE tenant_id = 'default' AND status = 'ACTIVE' AND deleted = false
);

SELECT setval(pg_get_serial_sequence('data_agent', 'id'), GREATEST(1, (SELECT COALESCE(MAX(id), 1) FROM data_agent)));
SELECT setval(pg_get_serial_sequence('data_agent_visibility_policy', 'id'), GREATEST(1, (SELECT COALESCE(MAX(id), 1) FROM data_agent_visibility_policy)));
SELECT setval(pg_get_serial_sequence('data_agent_skill', 'id'), GREATEST(1, (SELECT COALESCE(MAX(id), 1) FROM data_agent_skill)));
SELECT setval(pg_get_serial_sequence('data_agent_skill_version', 'id'), GREATEST(1, (SELECT COALESCE(MAX(id), 1) FROM data_agent_skill_version)));
SELECT setval(pg_get_serial_sequence('data_agent_skill_binding', 'id'), GREATEST(1, (SELECT COALESCE(MAX(id), 1) FROM data_agent_skill_binding)));
