# Business Agent

独立可运行的 Agent 平台：对话（SSE）、Skill、工具 / MCP、RAG、NL2SQL。演示模式无登录。

This is a standalone agent console and runtime. It does **not** include business OMS/WMS systems.

## 文档

| 文档 | 说明 |
|---|---|
| [docs/getting-started.md](docs/getting-started.md) | 怎么启动（Docker / 无 Docker） |
| [docs/configuration.md](docs/configuration.md) | 怎么配置 |
| [docs/backend.md](docs/backend.md) | **后端完整设计**（架构、模块、问答链路、安全） |
| [docs/architecture.md](docs/architecture.md) | 整体架构 |
| [docs/modules.md](docs/modules.md) | 核心模块 |
| [docs/runtime-flow.md](docs/runtime-flow.md) | 对话主链路 |
| [docs/scripts.md](docs/scripts.md) | 脚本 |
| [docs/README.md](docs/README.md) | 文档索引 |

## 仓库结构

```
business-agent/
  backend/           Spring Boot 服务  com.sn68.agent
  frontend/          Vue 3 控制台
  framework-lite/    基础能力（异常 / MP / Redis 锁），com.sn68.agent.framework
  docker-compose.yml Postgres(pgvector) + Redis + 前后端
  scripts/           启动与扫描脚本
  docs/              开源说明
```

## 最快启动

完整步骤（含 **git clone 后 IDE 启动**、WSL 端口、三个终端、验收）见 **[docs/getting-started.md](docs/getting-started.md)**。IDE 逐步说明见 **[docs/ide-setup.md](docs/ide-setup.md)**。

### Windows 无 Docker（用 WSL 的库和 Redis）

一次性灌库：

```powershell
wsl -d Ubuntu-24.04 -- bash /mnt/d/workspace/business-agent/scripts/wsl-setup-db.sh
wsl -d Ubuntu-24.04 -- bash /mnt/d/workspace/business-agent/scripts/wsl-load-schema.sh
```

每次开发开 **三个终端**（Redis 那个不要关）：

```powershell
# 终端 A — Redis 保活 127.0.0.1:16379
wsl -d Ubuntu-24.04 -- bash /mnt/d/workspace/business-agent/scripts/wsl-keep-redis.sh

# 终端 B — 后端 10108（默认连 WSL Postgres 5433 + Redis 16379）
cd D:\workspace\business-agent
.\scripts\dev-backend.ps1

# 终端 C — 前端
.\scripts\dev-frontend.ps1
```

浏览器打开 **http://localhost:6868** 。Postgres 是 **5433** 不是 5432。前端端口写死 6868，被占用会直接失败。

### 有 Docker 时

```bash
cp .env.example .env
docker compose up --build
```

浏览器 http://localhost:8080 。compose 里 Postgres 是 5432、Redis 是 6379。

未配置 `AGENT_OPENAI_API_KEY` 时控制台仍可打开；发消息走 SSE 错误事件，不是连不上库。

后端设计（分层、路由、SSE、SQL 守卫、演示身份）见 **[docs/backend.md](docs/backend.md)**。

## 架构一览

```
浏览器  --/api/ai/*-->  代理(Vite/nginx)  --/ai/*-->  backend:10108
                                              |-- PostgreSQL schema agent
                                              |-- Redis
                                              |-- 可选 OpenAI 兼容模型
```

- 后端 context-path 固定 `/ai`
- 对话：`POST /ai/stream/search`（SSE）
- 演示用户：过滤器注入 `sn68` / 租户 `default`

## 许可

Apache-2.0。上游 DataAgent（Apache-2.0）与 Soybean 相关前端（MIT）声明见 [NOTICE](NOTICE)。
