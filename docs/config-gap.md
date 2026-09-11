# Config and schema gap list

Nacos dataIds (`security.yaml`, `cloud-default.yaml`, `db.properties`, `${spring.application.name}.properties`, `mybatis-plus-default.yaml`, `oss.yaml`) are **not in this repository**. Values below come from `local.properties` (recommended-value list, not runtime), Java `@ConfigurationProperties` defaults, and the split plan. Anything that only lived on Nacos is marked **unconfirmed**.

## Fail-fast keys written into `config/agent.yaml`

These are present so a local start does not depend on Nacos:

| Key | Local value | Notes |
|---|---|---|
| `spring.ai.agent.orchestration.executor.core-pool-size` | 4 | Java has no usable default; missing → start fails |
| `spring.ai.agent.orchestration.executor.max-pool-size` | 8 | same |
| `spring.ai.agent.orchestration.executor.queue-capacity` | 64 | same |
| `spring.ai.agent.orchestration.executor.keep-alive` | 60s | same |
| `spring.ai.agent.crypto.enabled` | false | Java default true; true + empty key → start fails |
| `spring.ai.vectorstore.type` | pgvector | keep official prefix |
| `spring.ai.vectorstore.pgvector.initialize-schema` | false | table comes from `schema.sql` |
| `spring.ai.agent.langfuse.enabled` | false | Java `OpenTelemetryConfig.enabled` default true |
| `spring.ai.agent.im.dingtalk.enabled` | false | Java `@Scheduled` still needs `@ConditionalOnProperty` (other agent) |
| `spring.ai.agent.demo-model.auto-seed` | true | seeder Java is owned by the backend agent |
| `spring.ai.agent.code-executor.code-pool-executor` | ai_simulation | local.properties recommended docker |
| `spring.ai.agent.memory.auto-context.enabled` | false | Java default true |
| `spring.ai.agent.runtime.max-prompt-tokens` | 5000000 | local.properties still shows 0; comments require 5000000 |
| `spring.ai.agent.runtime.routing.total-timeout` | 5000ms | Java default 1800ms |
| `spring.ai.agent.runtime.routing.model-timeout` | 3000ms | Java default 1100ms |
| `spring.ai.agent.runtime.routing.vector-timeout` | 1500ms | Java default 250ms (dangerous) |
| `spring.ai.agent.task.scheduler.enabled` | false | Java default true |
| `spring.ai.agent.runtime.stale-turn-scan-interval` | 60s | matches `@Scheduled` default |
| `spring.ai.agent.routing.pending-expiry-scan-interval` | 60s | matches `@Scheduled` default |

Private model `base-url` from local.properties was **not** copied. Use `AGENT_OPENAI_BASE_URL` / `AGENT_OPENAI_API_KEY` (see `.env.example`).

## Keys translated from local.properties

Written under `spring.ai.agent.*`: vector-store, embedding-batch, llm-service-type, chat-attachment, multimodal, suite-file, skills.local-path, max-sql-retry-count, security permission codes, agentscope.observability, memory.auto-context.*, runtime.* (including deterministic), orchestration.*, flow.*, long-term-memory.*, evaluation.executor.*, optimization.*, web-evidence.*, report.*, fusion-strategy (`rrf` Java default), model-cache (Java default enabled/64), datasource-runtime-cache (Java default 5m).

`agent.temporal.*` written (Java `@Value`, not `spring.ai.agent`).

`spring.ai.agent.routing.engine.mode=LEGACY` (NATIVE is start-fail).

## Unconfirmed (Nacos-only or not in local.properties)

No Nacos dump exists here. The following were **not** seen with production values:

- Exact Redis DB index, timeout, username, SSL, Redisson codec / cluster nodes
- Hikari pool sizing beyond `connection-init-sql`
- `spring.ai.vectorstore.pgvector` index-name（schema-name/table-name 已固定为 `agent.vector_store`）
- `spring.ai.mcp.server.*` (must stay enabled; `McpSyncServer` is required)
- `spring.ai.agent.observability.*` (authorization observability)
- `spring.ai.agent.authorization` production SHADOW/ENFORCE tenant whitelist (local is SHADOW + empty)
- `spring.ai.agent.runtime.routing.function-calling-enabled` production value
- `spring.ai.agent.agentscope.v2.*` Redis prefix/TTL beyond Java defaults
- `spring.ai.agent.tool-center.mcp.servers` (must not use `serviceName` discovery)
- `spring.ai.agent.digital-employee.auth-cache.*` production TTL
- Mail / OSS / RocketMQ / Snail Job / Feign / Nacos discovery (intentionally absent)
- Elasticsearch hosts (health check disabled)
- `spring.ai.agent.crypto.key` (crypto off; do not commit a key)
- Langfuse host/public-key/secret-key (disabled)
- Report CDN URLs actually used in production (local uses jsDelivr)
- `spring.ai.agent.skills.local-path` production directory (`local.properties` `./dataAgent-skills` vs Java `./agent-skills`; yaml uses `./agent-skills`)
- `extend.mybatis-plus.intercept.data-permission` vs `extend.mybatis-plus.data-permission`: Java reads the **latter** (`DatabaseProperties.dataPermission`, default enabled=true). Both keys are set false. Nacos path unconfirmed.
- Spring AI OpenAI/DashScope auto-config exclude list (Java-owned)
- Multipart file-size production cap (local 50MB/100MB)

## Schema merge

`schema.sql` = schema-v2 (`v4_ai` → `agent`, `vector` in `public`) + sanitized Group A + `agent_file`.

**Unique tables: 110.** `CREATE TABLE` statements: 117 (IM / memory tables are dropped and rebuilt by Group A).

Included Group A (IAM SQL stripped, `SET LOCAL search_path` kept only inside `BEGIN`, `CREATE INDEX CONCURRENTLY` → `CREATE INDEX`):

- `数字员工方案-DDL/{02-runtime,04-task-im,05-memory-market}.sql`
- `20260817_digital_employee.sql`
- `20260820_digital_employee_clean_architecture.sql` (**table only**; `v4_iam.sys_resource` omitted)
- `20260818_authorization_{pap,pep_audit}.sql`
- `20260818_memory_owner_ttl.sql`
- `20260818_pr6_task_forbid_concurrency_slot.sql` (skipped `UPDATE` that reads missing `agent_task_definition.employee_release_id`)
- `20260821_digital_employee_spec_hash_nullable.sql` + `fix_spec_hash_not_null_constraint.sql`
- `20260824_drop_chat_owner_agent_fk.sql`
- `20260825_{employee_run_feedback_digest,task_im_idempotency_tenant,runtime_budget_report_idx}.sql`
- `20260901_ai_tenant_isolation.sql` and `_add_missing_columns.sql` (**ADD COLUMN tenant_id + runtime BIGINT→VARCHAR only**; skipped PLATFORM/`scope` unique-key rewrite because schema-v2 / `AgentExecutionResource` have no `scope`)
- `20260903_skill_analysis_config.sql`
- `20260907_chat_runtime_thread_active.sql`

**Skipped (documented, not executed):**

- `20260901_ai_tenant_isolation_continue.sql` (depends on `agent_execution_resource.scope` / platform unique key)
- Production backfill `tenant_id='1'`
- `数字员工方案-DDL/03-flow.sql` (not Group A; flow tables already in schema-v2)
- Workspace DDL / `20260818_drop_workspace.sql` (Group B)
- All IAM menu/permission SQL (`06-iam-permissions`, `08-iam-menus`, `20260811_iam_*`, `20260819_menu_*`, `20260820` sys_resource)
- Demand-create (`20260813_restore_demand_create_latest.sql`, `20260902_demand_create_*`)
- Zeus/Trove MCP seeds and `data-v2.sql`
- Group B/C/D rollbacks, prechecks, eval oracle, opt-experiment owner, snailjob notes

`vector_store.embedding` remains `vector(1024)`.

## Seed (`seed-demo.sql`)

Tenant `default` only:

1. `data_agent` id=1, name=`Demo Agent`, `agent_type=knowledge_base`, `status=published`
2. `data_agent_visibility_policy` `TENANT` / `TENANT` / `DISABLED`
3. `data_agent_skill` `knowledge-qa`, `scope=TENANT`, `QA` + `KNOWLEDGE`, `PUBLISHED`
4. `data_agent_skill_version` `PUBLISHED` with `knowledge_config`
5. `data_agent_skill_binding` enabled, pinned to that version

No IM connectors, no notification connectors, no business MCP, no model API keys (DemoModelSeeder / console).

## Compose / Docker notes

- Postgres demo user/password is `agent`/`agent` only.
- Backend image context is **repo root** (`dockerfile: backend/Dockerfile`) so the image can `mvn install` `framework-lite` then package `backend`. `build: ./backend` cannot see `framework-lite`.
- `backend/pom.xml` and `framework-lite/pom.xml` are owned by other agents; image build waits on those files.
- `frontend/Dockerfile` / `nginx.conf` are owned by the frontend agent; compose still declares `build: ./frontend` and SSE proxy requirements from the split plan.
- Optional `file:./config/application-local.yaml` is not bind-mounted (missing file would fail compose). Create it on the host if you need overrides.
