# 核心模块

包名 `com.sn68.agent`。运行时主体在 `dataagent` 下。模块职责、技术栈与安全边界的完整说明见 [backend.md](backend.md)。

## 后端模块

| 包 | 职责 |
|---|---|
| `dataagent.controller` | HTTP。管理接口多在 `/data-agent`、`/skills`、`/chat`；流式问答在 **`POST /stream/search`**（无 class-level 前缀，完整路径 `/ai/stream/search`） |
| `dataagent.agentscope` | 运行时内核：Harness、会话、记忆、工具适配（数据源 / SQL 守卫 / 知识 / 语义） |
| `dataagent.routing` | 混合路由：词面 + 向量，RRF 融合，选择 Skill |
| `dataagent.skill` | Skill 版本、绑定、发布；执行模式 Knowledge / React / Flow |
| `dataagent.flow` | 确定性流程引擎（收集槽位、确认、调用工具） |
| `dataagent.tool` + `mcp` | 工具目录、MCP client/server、曝光策略 |
| `dataagent.connector` | 多方言 JDBC、schema 探查、受控查询 |
| `dataagent.service` | 智能体 CRUD、知识库、模型配置、会话、可见性等应用服务 |
| `dataagent.runtime` | 可恢复运行、outbox、审批挂起 |
| `dataagent.config` | `OpenAccessFilter`、编排线程池、MCP Server |
| `dataagent.authorization` | PAP/PEP；开源默认 SHADOW，不拦截对话 |
| `dataagent.employee` / `im` / `task` / `evaluation` / `market` | 平台能力；钉钉/调度默认关闭 |

`framework-lite`（`com.sn68.agent.framework`）：

- `CheckedException`、`SuperEntity`、`Wraps` / `SuperMapper`
- `AuthenticationContext` + `UserInfoDetails`
- `RedisLockHelper`
- 全局响应包装、AccessLog（不打印 token）

## 前端模块

| 路径 | 职责 |
|---|---|
| `src/views/ai-agent` | 全部业务页：列表、创建、运行、技能、工具、数据源、模型 |
| `src/views/ai-agent/services` | API 客户端；`graph.ts` 负责 SSE |
| `src/views/ai-agent/services/http.ts` | `streamWithAuth`；与 axios 同一套 `/api` 或直连前缀 |
| `src/router/routes/index.ts` | **仅** ai-agent 静态路由（不要拷贝超大业务路由表） |
| `src/service/request` | axios 封装，成功码 `200` |
| `packages/*` | 精简 Soybean 工具包 |

默认首页 `VITE_ROUTE_HOME=ai-agent_agents`。`haveAuth()` 恒 true。

## 数据

- Schema：`backend/src/main/resources/sql/pg/schema.sql`，search_path `agent`
- 演示数据：`seed-demo.sql` — Demo Agent + knowledge-qa Skill + 租户内可见
- 向量表 `vector_store.embedding vector(1024)`
- 本地文件表 `agent_file`

## 演示闭环最小集合

要「打开就能问」，至少需要：

1. 已发布 Agent（seed 已给）
2. 已发布并绑定的 Skill（seed 已给 knowledge-qa）
3. 可选：`model_config` 中的 CHAT 模型（env 有 Key 时 seeder 写入）
4. Redis + Postgres 可用
