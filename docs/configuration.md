# 配置说明

本仓库 **不使用 Nacos**。启动只读 classpath 与本地文件。

## 加载顺序

`backend/src/main/resources/application.yml`：

```yaml
spring:
  application.name: business-agent
  profiles.active: local
  config.import:
    - optional:classpath:config/security.yaml
    - optional:classpath:config/cloud.yaml
    - optional:classpath:config/datasource.yaml
    - optional:classpath:config/mybatis-plus.yaml
    - optional:classpath:config/storage.yaml
    - optional:classpath:config/agent.yaml
    - optional:file:./config/application-local.yaml
```

部署覆盖：在进程工作目录放 `config/application-local.yaml`，或导出环境变量。不要把真实密钥提交进 git。

Windows + WSL 本地开发已提供：

- `backend/config/application-local.yaml`（Maven 在 `backend/` 目录启动时加载）
- 仓库根 `config/application-local.yaml`（在仓库根执行 `mvn -f backend/pom.xml` 时加载）

内容指向 `127.0.0.1:5433`（WSL Postgres）和 `127.0.0.1:16379`（WSL Redis）。`dev-backend.ps1` 会设置同一套环境变量。

## 文件职责

| 文件 | 职责 |
|---|---|
| `config/cloud.yaml` | 端口 `10108`、`context-path=/ai`、Redis、multipart、actuator |
| `config/datasource.yaml` | JDBC；`search_path=agent,public` |
| `config/mybatis-plus.yaml` | mapper 位置、逻辑删除；租户插件默认不拦演示库 |
| `config/storage.yaml` | 本地上传目录与对外 URL 前缀 |
| `config/security.yaml` | 健康检查等；开源默认无 Sa-Token |
| `config/agent.yaml` | `spring.ai.agent.*` 与 `spring.ai.vectorstore.*` |

Java 绑定前缀是 **`spring.ai.agent`**（`Constant.PROJECT_PROPERTIES_PREFIX`）。改 YAML 键必须与 `@ConfigurationProperties` / `@Value` / `@ConditionalOnProperty` 一致，否则会静默用 Java 默认值。官方 Spring AI 前缀 `spring.ai.vectorstore.*` 不要改成 `spring.ai.agent`。

## 环境变量

见仓库根 `.env.example`。Compose 与本机共用这套名字：

| 变量 | 含义 |
|---|---|
| `POSTGRES_HOST` / `PORT` / `DB` / `USER` / `PASSWORD` | 数据库 |
| `REDIS_HOST` / `REDIS_PORT` | Redis |
| `AGENT_OPENAI_BASE_URL` | OpenAI 兼容网关，可空 |
| `AGENT_OPENAI_API_KEY` | 可空；空则控制台能进、对话提示未配模型 |
| `AGENT_OPENAI_CHAT_MODEL` | 对话模型名 |
| `AGENT_OPENAI_EMBEDDING_MODEL` | 向量模型；知识检索需要 |
| `AGENT_STORAGE_DIRECTORY` | 本地文件根目录 |
| `AGENT_STORAGE_PUBLIC_BASE_URL` | 上传后返回的绝对 URL 前缀，默认 `http://localhost:10108/ai` |

`DemoModelSeeder` 在 `spring.ai.agent.demo-model.auto-seed=true` 且 Key 非空时，向 `model_config` upsert 一条 CHAT 配置。

## 必须写进 yaml 的启动项

缺了会起不来或行为危险，已写入 `agent.yaml`：

- 编排线程池：core=4, max=8, queue=64, keep-alive=60s
- `spring.ai.agent.crypto.enabled=false`
- `spring.ai.vectorstore.type=pgvector`，`pgvector.schema-name=agent`（表在 `agent.vector_store`，不要用默认的 `public`）
- `spring.ai.agent.im.dingtalk.enabled=false`
- `spring.ai.agent.code-executor.code-pool-executor=ai_simulation`
- `spring.ai.agent.task.scheduler.enabled=false`

向量列 DDL 写死 **1024 维**。接入 embedding 模型必须是 1024 维，或同时改 schema 与 `EmbeddingDimensionGuard`。

## 前端环境

| 文件 | 用途 |
|---|---|
| `frontend/.env` | 标题、静态路由、`VITE_ROUTE_HOME=ai-agent_agents`、关闭诊断按钮权限 |
| `frontend/.env.development` | `VITE_HTTP_PROXY=Y`，`VITE_SERVICE_BASE_URL=http://localhost:10108` |
| `frontend/.env.production` | `VITE_SERVICE_BASE_URL=/api`（走 nginx） |

不要把管理 API（`/ai/data-agent/**`）和流式入口（`/ai/stream/search`）配成同一条错误前缀。

未从私有配置中心带出的键见 [config-gap.md](config-gap.md)。
