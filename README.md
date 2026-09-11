# Business Agent

**企业级 AI 智能体平台**（开源、可私有化部署）。把「问数、办事、数字员工、评测进化」收在一套控制台里，而不是每个部门再做一个聊天机器人。

定位不是 ERP/OMS，也不是现成的商业 SaaS 套餐。它是企业做内部 Agent 平台、或再包一层登录/计费做成 SaaS 时用的**完整运行时 + 治理台**。演示默认无登录（Open Access）。

## 核心亮点

1. **问自己的业务数据**：NL2SQL + 表白名单，模型只能打你勾选的表，不是把库敞给大模型。
2. **技能可治理、可复用**：智能体 / 技能 / 工具拆开；一份技能绑多个 Agent，混合路由按问句选技能。
3. **数字员工**：把可复用的「数字同事」当资产管——身份、技能市场上下架、权限策略（PAP），而不是散落的会话窗口。
4. **自评估、自进化**：用评估集打智能体和数字员工；自优化做实验对比，发布可回滚，避免改 Prompt 靠感觉上线。
5. **企业闸门**：租户字段、SQL 守卫、数据源 SSRF 黑名单、PEP（演示默认 SHADOW 旁路）。接上你们的登录即可当生产平台内核。

## 解决什么问题

| 企业里常见的情况 | 这里怎么收 |
|---|---|
| 问一句数要等报表或找人写 SQL | 对话里直接查；技能约束表和口径 |
| 每个业务线一个 Bot，技能复制不了 | 技能中心 + 技能市场，审核后装到数字员工 |
| 上线后不知道回答稳不稳 | 评估策略 / 用例集 / 运行记录，对象含智能体和数字员工 |
| 改提示词或模型不敢发 | 自优化实验 → 对比基线 → 发布 / 回滚 |
| 模型乱查库、乱调写接口 | 表白名单、SQL 守卫、权限中心策略 |
| 人走了机器人也没人接 | 数字员工带身份和记忆，任务可派发 |

**适合：** 企业私有化 Agent 平台、数据助手、数字员工试点；或作为 SaaS 的引擎层。  
**不适合：** 当进销存/仓储/结算系统用。本仓库不实现履约和财务过账。

## 能力全景

侧栏六组：**智能体 · 数据与模型 · 数字员工 · 运行 · 评估 · 集成**。

| 模块 | 做什么 |
|---|---|
| **智能体** | 创建/发布/绑定技能与模型；流式问答（SSE）；Knowledge / React / Flow 三种执行 |
| **数据与模型** | 数据中心登记库并勾选可见表；模型中心接 OpenAI 兼容 CHAT / Embedding / 语音 |
| **数字员工** | 数字同事工作台；技能市场上架审核后安装；权限中心管 PAP 策略与绑定 |
| **运行** | 运行记录（成功/失败/可恢复）、任务中心、会话排障、Token 用量 |
| **评估** | 评估策略/对象/用例集，覆盖智能体与数字员工候选/发布；一键跑评估 |
| **自优化** | 实验、基线对比、候选发布、回滚记录——Prompt/配置可进化、可撤回 |
| **集成** | MCP、事件触发、IM 连接器、通知（钉钉等默认关，按需打开） |

演示用户 `sn68` / 租户 `default`。PEP 默认 SHADOW，不拦对话。评估与自优化页面已接通，用例要自己建才会有数据。

## 界面一览

### 智能体

智能体管理、详情配置、运行问答是主路径。技能中心 / 工具中心挂在同一组菜单下。

<p align="center">
  <img src="docs/images/agents.png" alt="智能体管理" width="920">
</p>
<p align="center"><sub>智能体管理：创建、发布、按类型筛选。</sub></p>

<p align="center">
  <img src="docs/images/agent-detail.png" alt="智能体详情" width="920">
</p>
<p align="center"><sub>智能体详情：基本信息、模型、Skill 绑定、预设问题、记忆、可见性。</sub></p>

<p align="center">
  <img src="docs/images/chat.png" alt="智能体问答" width="920">
</p>
<p align="center"><sub>智能体问答：SSE 流式对话，可切换模型、看分析报告。</sub></p>

<table>
  <tr>
    <td width="50%"><img src="docs/images/skills.png" alt="技能中心"><br><sub>技能中心</sub></td>
    <td width="50%"><img src="docs/images/tools.png" alt="工具中心"><br><sub>工具中心</sub></td>
  </tr>
</table>

### 数据与模型

<table>
  <tr>
    <td width="50%"><img src="docs/images/datasources.png" alt="数据中心"><br><sub>数据中心：登记库，技能只看见勾选的表</sub></td>
    <td width="50%"><img src="docs/images/models.png" alt="模型中心"><br><sub>模型中心：对话 / 嵌入 / 语音</sub></td>
  </tr>
</table>

### 数字员工

<table>
  <tr>
    <td width="50%"><img src="docs/images/employees.png" alt="数字员工管理"><br><sub>数字员工：当资产管的数字同事</sub></td>
    <td width="50%"><img src="docs/images/skill-market.png" alt="技能市场"><br><sub>技能市场：上架审核后装到员工</sub></td>
  </tr>
  <tr>
    <td colspan="2"><img src="docs/images/authorizations.png" alt="权限中心"><br><sub>权限中心：PAP 模板与策略</sub></td>
  </tr>
</table>

### 运行

<table>
  <tr>
    <td width="50%"><img src="docs/images/runtime-runs.png" alt="运行记录"><br><sub>运行记录</sub></td>
    <td width="50%"><img src="docs/images/tasks.png" alt="任务中心"><br><sub>任务中心</sub></td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/images/diagnostics.png" alt="会话排障"><br><sub>会话排障</sub></td>
    <td width="50%"><img src="docs/images/token-usage.png" alt="Token 用量"><br><sub>Token 用量</sub></td>
  </tr>
</table>

### 评估

<table>
  <tr>
    <td width="50%"><img src="docs/images/evaluations.png" alt="智能体评估"><br><sub>评估：策略 / 用例 / 对象含数字员工</sub></td>
    <td width="50%"><img src="docs/images/optimizations.png" alt="智能体自优化"><br><sub>自优化：实验对比，发布可回滚</sub></td>
  </tr>
</table>

### 集成

<table>
  <tr>
    <td width="50%"><img src="docs/images/mcp.png" alt="MCP 中心"><br><sub>MCP 中心</sub></td>
    <td width="50%"><img src="docs/images/runtime-hooks.png" alt="事件触发中心"><br><sub>事件触发中心</sub></td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/images/im-connector.png" alt="IM 连接器"><br><sub>IM 连接器</sub></td>
    <td width="50%"><img src="docs/images/notifications.png" alt="通知中心"><br><sub>通知中心</sub></td>
  </tr>
</table>

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
