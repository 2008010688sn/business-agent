# Business Agent

用自然语言问自己的业务数据，而不是先写 SQL、先搭一套 OMS。

这是一套**可独立运行的 Agent 控制台 + 运行时**：建智能体、绑技能和数据源、选模型，然后在页面里对话。演示模式**不用登录**。不含进销存 / 仓储 / 结算等业务中台。

<p align="center">
  <img src="docs/images/chat.png" alt="订单智能体流式问答" width="920">
</p>
<p align="center"><sub>运行页：对订单智能体提问「这个月有多少需求单」，SSE 流式回答。</sub></p>

## 它解决什么问题

业务系统里数据在库里，问一句「这个月有多少需求单 / 账单多少钱」通常要：找人写 SQL、等报表、或做一个垂直机器人。Business Agent 把这件事收成统一平台：

| 痛点 | 做法 |
|---|---|
| 每次问数都写 SQL | 技能 + 表白名单 + NL2SQL，模型只打允许的表 |
| 机器人散落、无法复用 | 智能体 / 技能 / 工具拆开配置，一份技能可绑多个 Agent |
| 不知道问句该走知识库还是查库 | 混合路由（词法，可选向量）选出 Skill |
| 多轮填单容易跑偏 | 确定性 FLOW：填槽 → 确认 → 提交 |
| 接模型、接库、接 MCP 各搞一套 | 一个控制台：模型中心、数据中心、工具 / MCP |

**适合：** 想在自己的库上跑「能对话的数据助手」，又不想绑死某一家 SaaS 业务系统。  
**不适合：** 当完整 ERP/OMS 用；本仓库不实现订单履约、库存扣减、结算。

## 包含的能力

打开控制台就能看到这些模块（侧栏分组：智能体 / 数据与模型 / 数字员工 / 运行 / 评估 / 集成）。

| 能力 | 说明 |
|---|---|
| **智能体管理** | 创建、发布、绑定技能与模型；知识库 / 数据分析 / 客服 / 编排等类型 |
| **流式问答** | `POST /ai/stream/search`，SSE 推 `message` / `complete` / `error` |
| **技能中心** | Knowledge 检索生成、React 工具循环、Flow 确定性流程；可发布版本 |
| **NL2SQL / 运营与账单查询** | 技能绑数据源表白名单、业务术语、语义列；演示含订单 / 账单技能 |
| **数据中心** | 登记 PostgreSQL 等库，探 schema，Agent 只能看见勾选的表 |
| **模型中心** | OpenAI 兼容 CHAT / Embedding / ASR / TTS / 实时语音 |
| **混合路由** | 按问句选技能；租户需一条 ACTIVE 路由档案 |
| **RAG** | pgvector，1024 维，表 `agent.vector_store` |
| **工具 / MCP** | 工具目录；本进程可当 MCP Server；MCP Client 默认关 |
| **数字员工 / 技能市场 / 权限** | 页面可用；外部审批、钉钉默认关 |
| **运行与排障** | 运行记录、会话排障、Token 用量、评估与自优化 |

演示身份：每个请求注入用户 `sn68`、租户 `default`、角色平台管理员。授权 PEP 默认 **SHADOW**（旁路评估，不拦对话）。

## 界面一览

<p align="center">
  <img src="docs/images/agents.png" alt="智能体管理" width="920">
</p>
<p align="center"><sub>智能体管理：已发布的订单智能体、账单分析智能体。</sub></p>

<p align="center">
  <img src="docs/images/skills.png" alt="技能中心" width="920">
</p>
<p align="center"><sub>技能中心：知识问答、账单数据、核算到主体、运营/运单查询。</sub></p>

<p align="center">
  <img src="docs/images/datasources.png" alt="数据中心" width="920">
</p>
<p align="center"><sub>数据中心：技能只能看见登记并勾选的表，不能扫全库。</sub></p>

<p align="center">
  <img src="docs/images/models.png" alt="模型中心" width="920">
</p>
<p align="center"><sub>模型中心：对话 / 嵌入 / 语音等 OpenAI 兼容端点。</sub></p>

<p align="center">
  <img src="docs/images/employees.png" alt="数字员工" width="920">
</p>
<p align="center"><sub>数字员工：可复用的「数字同事」工作台（技能市场、权限中心同组）。</sub></p>

## 基于什么语言和技术

| 层 | 技术 |
|---|---|
| 后端 | **Java 17**、**Spring Boot 3.5.8**、MyBatis-Plus 3.5、HikariCP |
| Agent 运行时 | **AgentScope Java 2.0.1**（HarnessAgent） |
| 模型 | **Spring AI 1.1**，OpenAI 兼容（可接 DeepSeek / 通义 / StepFun 等） |
| 数据 | **PostgreSQL 16 + pgvector**（schema `agent`）、演示业务表 `demo_biz` |
| 缓存 / 锁 | **Redis + Redisson** |
| 前端 | **Vue 3 + Vite + TypeScript + Element Plus + pnpm** |
| 本仓基础库 | `framework-lite`（`com.sn68.agent.framework`：异常、实体、Wrapper、ThreadLocal、Redis 锁） |

```
浏览器  --/api/ai/*-->  Vite/nginx  --/ai/*-->  backend:10108
                                              |-- PostgreSQL
                                              |-- Redis
                                              |-- 可选大模型
```

更细的分层、问答时序、SQL 守卫见 **[docs/backend.md](docs/backend.md)**。

## 仓库结构

```
business-agent/
  backend/           Spring Boot    com.sn68.agent
  frontend/          Vue 3 控制台
  framework-lite/    基础能力
  docker-compose.yml Postgres + Redis + 前后端
  scripts/           本地启动与灌库
  docs/              说明与截图
```

## 最快启动

完整步骤（含 **git clone 后 IDE 启动**）见 **[docs/getting-started.md](docs/getting-started.md)**、**[docs/ide-setup.md](docs/ide-setup.md)**。

### Windows 无 Docker（WSL 里的库和 Redis）

```powershell
wsl -d Ubuntu-24.04 -- bash /mnt/d/workspace/business-agent/scripts/wsl-setup-db.sh
wsl -d Ubuntu-24.04 -- bash /mnt/d/workspace/business-agent/scripts/wsl-load-schema.sh

# 三个终端：Redis 不要关
wsl -d Ubuntu-24.04 -- bash /mnt/d/workspace/business-agent/scripts/wsl-keep-redis.sh
.\scripts\dev-backend.ps1
.\scripts\dev-frontend.ps1
```

浏览器 **http://localhost:6868** 。WSL Postgres **5433**、Redis **16379**。

### 有 Docker 时

```bash
cp .env.example .env
docker compose up --build
```

浏览器 http://localhost:8080 。

未配模型 Key 时控制台仍可打开；发消息走 SSE 错误事件，不是连不上库。

## 文档

| 文档 | 说明 |
|---|---|
| [docs/backend.md](docs/backend.md) | 后端架构、模块、问答主链路、安全 |
| [docs/getting-started.md](docs/getting-started.md) | 启动 |
| [docs/ide-setup.md](docs/ide-setup.md) | IDE 启动 |
| [docs/configuration.md](docs/configuration.md) | 配置 |
| [docs/architecture.md](docs/architecture.md) | 架构摘要 |
| [docs/modules.md](docs/modules.md) | 模块 |
| [docs/runtime-flow.md](docs/runtime-flow.md) | HTTP 与对话链路 |
| [docs/scripts.md](docs/scripts.md) | 脚本 |
| [docs/README.md](docs/README.md) | 索引 |

## 许可

Apache-2.0。上游 DataAgent（Apache-2.0）与 Soybean 相关前端（MIT）见 [NOTICE](NOTICE)。
