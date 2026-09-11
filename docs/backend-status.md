# Backend sanitization status

Date: 2026-09-11

## Compile

- `framework-lite` was already present; `mvn -f framework-lite/pom.xml -DskipTests install` **BUILD SUCCESS**.
- `mvn -f backend/pom.xml -DskipTests compile` **BUILD SUCCESS**.
- `mvn -f backend/pom.xml -DskipTests test-compile` **BUILD SUCCESS**.
- Maven used: IntelliJ bundled `C:\Program Files\JetBrains\IntelliJ IDEA 2026.1\plugins\maven\lib\maven3\bin\mvn.cmd` (`mvn` is not on PATH).

No compile errors remaining.

## What changed

### Build / entry

- Wrote independent `backend/pom.xml`: `com.sn68.agent:business-agent-backend:1.0.0`, Java 17, Spring Boot 3.5.8 parent, depends on `com.sn68.agent:framework-lite:1.0.0`.
- Kept web/websocket/validation/redis/postgresql/mybatis-plus 3.5.12/spring-ai-openai/pgvector/agentscope-harness 2.0.1 (same exclusions as xx-cloud-ai)/lombok/hutool.
- Excluded nacos, openfeign, iam/suite/workflow APIs, mq, snail-job, ai-spring-boot-starter, security-spring-boot-starter, aliyun OSS.
- Elasticsearch vector starter kept **only so hybrid retrieval compiles**; YAML must set `spring.ai.vectorstore.type=pgvector` to avoid dual VectorStore.
- `skipTests=false`; mainClass `com.sn68.agent.AgentApplication`; Java `**/*.xml` copied as resources.
- Replaced `AiApplication` with `AgentApplication`: no OAuth2/Snail/Discovery/Feign; `@MapperScan("com.sn68.agent.**.repository")`; scan `com.sn68.agent`; still excludes Mongo.
- Wrote `application.yml` that only imports `classpath:config/*.yaml` and optional `./config/application-local.yaml`.

### Open access / identity

- Deleted all `@SaCheckPermission` and `cn.dev33.satoken` usage in `src/main/java`.
- `OpenAccessFilter` writes demo `UserInfoDetails` (`userId=1`, `username=sn68`, `nickName=Demo User`, `tenantId/tenantCode=default`, `type=PLATFORM_ADMIN`) into `ThreadLocalHolder` key `USER_INFO_KEY`.
- `DataAgentAsyncContextBridge` copies ThreadLocal demo user (no Sa-Token). Delegated tokens hydrate from `LocalPrincipalStore`.
- Visibility default policy: `conversationScope=TENANT`, `catalogScope=TENANT`, `applyMode=DISABLED`.
- PEP remains `SHADOW`.

### Feign / IAM / Suite / Workflow / MQ / Job

- Deleted `WorkflowAgentVisibilityApprovalAdapter`, `AgentVisibilityWorkflowListener`, `AgentTaskEventListener`, Feign interceptor, Snail Job registrar/OpenAPI.
- Added `NoOpTaskRuntimeKick`.
- Local principal store + local EmployeePrincipal/provisioning/execution-context (开通即成功 when rollout enabled; default rollout still off).
- `DelegatedAuthContextService.issue` returns `standalone-no-iam`. Flow approval resume logs and continues with current demo user.
- User nickname lookups no longer call IAM (`getIfAvailable` path removed; nickname left empty/userId).
- Removed `@Remote` / `@RemoteResult` from `DataAgent` / `list()`.

### Files

- `LocalFileService` replaces SuiteFileService (same public methods).
- `POST /files/upload`, `GET /files/{id}`; path `{publicBase}/files/{id}` default `http://localhost:10108/ai`.
- `findByPaths` parses id from URL; `download` reads local disk.
- Rejects `data:` and `file:` only (does not reject `/files/`).

### Model / IM

- `DemoModelSeeder` when `spring.ai.agent.demo-model.auto-seed=true`, env `AGENT_OPENAI_BASE_URL` / `AGENT_OPENAI_API_KEY` / `AGENT_OPENAI_CHAT_MODEL` (optional embedding).
- Missing CHAT model: `CheckedException.badRequest("未配置模型，请在模型配置页添加")`.
- `DingTalkStreamLifecycleService` `@ConditionalOnProperty(prefix=spring.ai.agent.im.dingtalk, name=enabled, havingValue=true, matchIfMissing=false)`.
- `Constant.PROJECT_PROPERTIES_PREFIX` is `spring.ai.agent`.

### Tests / leftovers

- Deleted empty `com/xx` trees, LangChain `AiModelProperties`, `db/ai_agent.sql`.
- Rewrote zeus/trove/`demandCreate` fixtures to `demo.echo`.
- Permission baseline tests now assert open-access (no Sa-Token).

## Leftover grep hits

`src/main/java` is **0** for: `nacos`, `SaCheckPermission`, `cn.dev33.satoken`, `com.xx.cloud.iam`, `com.xx.cloud.suite`, `com.xx.cloud.workflow`, `demandCreate`, `xx-cloud-zeus`.

Outside main/java:

| Location | Hit | Note |
|---|---|---|
| `backend/src/test/.../ControllerAuthorizationBaselineTest.java` | string `"@SaCheckPermission"` / `"cn.dev33.satoken"` | Guard that main sources stay clean |
| `backend/src/test/.../ImApprovalCommandServiceTest.java` | javadoc mentions `@SaCheckPermission` | comment only |
| `backend/src/main/resources/dataagent/local.properties` | historical comments (Nacos wording reduced) | file is not loaded at runtime |
| `backend/src/test/.../DataDataAgentConfigurationTest.java` | comment previously mentioned nacos import | rewritten to classpath yaml |

## `agent_file` DDL (for config agent)

Add to `sql/pg/schema.sql` if not already present:

```sql
CREATE TABLE IF NOT EXISTS agent_file (
    id                bigint PRIMARY KEY,
    tenant_id         varchar(64),
    original_name     varchar(512),
    content_type      varchar(128),
    size              bigint,
    storage_path      varchar(1024) NOT NULL,
    create_time       timestamptz,
    create_by         varchar(64),
    create_name       varchar(128),
    last_modify_time  timestamptz,
    last_modify_by    varchar(64),
    last_modify_name  varchar(128),
    deleted           boolean NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS idx_agent_file_tenant ON agent_file (tenant_id);
```
