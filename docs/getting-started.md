# 启动指南

开源演示默认 **Open Access**：无登录。后端给每个请求注入演示用户 `sn68`、租户 `default`。

有三条路：

1. **git clone 后在 IDE 里点运行**（日常开发，见下面「零」和 [ide-setup.md](ide-setup.md)）
2. **脚本启动**（Windows 无 Docker：WSL 的 Postgres + Redis，Windows 跑前后端）
3. **Docker Compose**（本机有 `docker`）

---

## 环境

| 组件 | 版本 | 本地启动时谁提供 |
|---|---|---|
| JDK | 17 | Windows |
| Maven | 3.9+（PATH 或 IntelliJ 自带） | Windows |
| Node.js | 20+ | Windows |
| pnpm | 9+ | Windows |
| PostgreSQL 16 + **pgvector** | `vector(1024)` | WSL Ubuntu 16 集群，端口 **5433** |
| Redis | 7 | WSL Docker `--network host`，端口 **16379** |

仓库路径按 `D:\workspace\business-agent` 写；WSL 里对应 `/mnt/d/workspace/business-agent`。换盘符就改路径。

前端开发端口固定 **6868**（`frontend/vite.config.ts` + `pnpm dev`）。被占用会直接失败，不会改到别的端口。

---

## 零、git clone 后在 IDE 中启动

```bash
git clone https://github.com/2008010688sn/business-agent.git
cd business-agent
```

本仓库**没有根 pom.xml**。后端是两个 Maven 模块：先把 `framework-lite` install 进本地 `.m2`，再运行 `backend` 的 `com.sn68.agent.AgentApplication`。前端是 `frontend/` 的 pnpm 工程。

### 0.1 一次性准备

1. 安装 JDK 17、Maven 3.9+（IntelliJ 自带即可）、Node.js 20+、pnpm 9（`corepack enable` 后 `corepack prepare pnpm@9.15.5 --activate`）。
2. 准备 PostgreSQL 16 + pgvector、Redis。Windows 无 Docker 时用 WSL：Postgres **5433**、Redis **16379**，见下一节「一次性：建库灌数」和 Redis 保活。
3. 灌库（只需一次）：

```powershell
wsl -d Ubuntu-24.04 -- bash /mnt/d/workspace/business-agent/scripts/wsl-setup-db.sh
wsl -d Ubuntu-24.04 -- bash /mnt/d/workspace/business-agent/scripts/wsl-load-schema.sh
```

会写入 Demo Agent、**订单智能体**、**账单分析智能体** 及其技能（`waybill-query` / `bill-query` / `profit-query`），以及 `demo_biz` 业务表结构。本月业务行在本地库里；行快照文件不进 git。

4. 每次开发先开 Redis（不要关）：

```powershell
wsl -d Ubuntu-24.04 -- bash /mnt/d/workspace/business-agent/scripts/wsl-keep-redis.sh
```

### 0.2 IntelliJ IDEA 启动后端

1. File → Open → 选仓库根目录 `business-agent`。
2. Maven 工具窗口右上角 **+**，依次导入 `framework-lite/pom.xml`、`backend/pom.xml`。
3. Settings → Build, Execution, Deployment → Compiler → Annotation Processors → **Enable annotation processing**（Lombok）。
4. File → Project Structure → Project SDK = **17**。
5. Maven 面板：`framework-lite` → Lifecycle → `install`，勾选 Skip Tests（或参数 `-DskipTests`）。
6. 打开 `backend/src/main/java/com/sn68/agent/AgentApplication.java`，点类左侧绿色三角 Run。
7. Run → Edit Configurations → AgentApplication，确认：

| 项 | 值 |
|---|---|
| Working directory | `backend` 目录（会读 `backend/config/application-local.yaml`）。若工作目录是仓库根，则读 `config/application-local.yaml`，两份内容相同。 |
| Active profiles | `local` |
| JRE | 17 |
| Environment variables | `POSTGRES_HOST=127.0.0.1;POSTGRES_PORT=5433;POSTGRES_DB=business_agent;POSTGRES_USER=agent;POSTGRES_PASSWORD=agent;REDIS_HOST=127.0.0.1;REDIS_PORT=16379` |

Linux / macOS 把端口改成 5432 / 6379，并改 `application-local.yaml`。

就绪：日志出现 `Application 'business-agent' is running`。检查 http://127.0.0.1:10108/ai/actuator/health 为 `UP`。

### 0.3 IDE 启动前端（IntelliJ / WebStorm / VS Code）

1. 终端进入 `frontend/`，执行 `pnpm install`（第一次）。
2. IntelliJ / WebStorm：Run → Edit Configurations → + → npm。package.json 选 `frontend/package.json`，Command = `run`，Scripts = `dev`。
3. VS Code / Cursor：在 `frontend` 目录终端执行 `pnpm dev`。
4. 打开 **http://localhost:6868** 。浏览器请求 `/api/ai/*` 由 Vite 转到 `http://localhost:10108/ai/*`。

更细的点击步骤见 [ide-setup.md](ide-setup.md)。

---

## 一、本地启动（Windows + WSL，用脚本、不用 IDE 时）

需要 **三个终端**，且 Redis 那个不要关。

### 1. 一次性：建库灌数（只需做一次）

PowerShell：

```powershell
wsl -d Ubuntu-24.04 -- bash /mnt/d/workspace/business-agent/scripts/wsl-setup-db.sh
wsl -d Ubuntu-24.04 -- bash /mnt/d/workspace/business-agent/scripts/wsl-load-schema.sh
```

会：

- 使用 WSL 已有 PostgreSQL 16（监听 **5433**，不是 5432）
- 创建用户 `agent` / 库 `business_agent` / 扩展 `vector`
- 执行 `schema.sql` + `seed-demo.sql` + 订单/账单智能体技能 + `demo_biz` 表结构

### 2. 终端 A：Redis 保活（每次开发都要开着）

```powershell
wsl -d Ubuntu-24.04 -- bash /mnt/d/workspace/business-agent/scripts/wsl-keep-redis.sh
```

看到 `REDIS_UP` 后不要关这个窗口。Windows 直连 WSL `docker -p` 端口不稳定，脚本用 `--network host` 把 Redis 绑在 `0.0.0.0:16379` 并循环 `docker start`。

自检：

```powershell
python -c "import socket; s=socket.create_connection(('127.0.0.1',16379),2); s.sendall(b'PING\r\n'); print(s.recv(16))"
```

应打印 `b'+PONG\r\n'`。

### 3. 终端 B：后端

```powershell
cd D:\workspace\business-agent
.\scripts\dev-backend.ps1
```

脚本默认环境变量：

| 变量 | 默认 |
|---|---|
| `POSTGRES_HOST` | `127.0.0.1` |
| `POSTGRES_PORT` | `5433` |
| `POSTGRES_DB` | `business_agent` |
| `POSTGRES_USER` / `PASSWORD` | `agent` / `agent` |
| `REDIS_HOST` | `127.0.0.1` |
| `REDIS_PORT` | `16379` |

同时读取 `backend/config/application-local.yaml`（同样指向 5433 / 16379）。`config/cloud.yaml` 已开 `spring.main.allow-circular-references=true`。

就绪标志：日志里有 `Application 'business-agent' is running`。

| 检查 | URL |
|---|---|
| 健康检查 | http://127.0.0.1:10108/ai/actuator/health |
| API 文档 | http://127.0.0.1:10108/ai/doc.html |
| Agent 列表 | `POST http://127.0.0.1:10108/ai/data-agent/query`  body `{}` |

列表应返回 **Demo Agent**。

等价手敲：

```powershell
cd D:\workspace\business-agent
$env:POSTGRES_HOST='127.0.0.1'; $env:POSTGRES_PORT='5433'
$env:POSTGRES_DB='business_agent'; $env:POSTGRES_USER='agent'; $env:POSTGRES_PASSWORD='agent'
$env:REDIS_HOST='127.0.0.1'; $env:REDIS_PORT='16379'
mvn -f framework-lite/pom.xml "-DskipTests" install
mvn -f backend/pom.xml "-DskipTests" spring-boot:run
```

`mvn` 不在 PATH 时，用 IntelliJ 自带：

`C:\Program Files\JetBrains\IntelliJ IDEA 2026.1\plugins\maven\lib\maven3\bin\mvn.cmd`

（`dev-backend.ps1` 会自动找这个路径。）

### 4. 终端 C：前端

```powershell
cd D:\workspace\business-agent
.\scripts\dev-frontend.ps1
```

默认 **http://localhost:6868**（写死，占用则启动失败）。

`.env.development`：`VITE_HTTP_PROXY=Y`，`VITE_SERVICE_BASE_URL=http://localhost:10108`。浏览器请求 `/api/ai/...`，Vite 去掉 `/api` 转到 `http://localhost:10108/ai/...`。

### 5. 打开控制台

浏览器打开 **http://localhost:6868**：

1. 不登录，侧栏有「智能体管理」
2. 列表有 Demo Agent、订单智能体、账单分析智能体
3. 进入运行页发「你好」
   - 未配模型：SSE `event:error`（不是连不上库）
   - 配了 Key：有模型回复

对话 URL 必须是 `POST /api/ai/stream/search`（代理后后端为 `/ai/stream/search`）。

### 6. 配模型（可选）

仓库根 `.env` 或启动后端的终端里：

```powershell
$env:AGENT_OPENAI_BASE_URL='https://api.openai.com/v1'
$env:AGENT_OPENAI_API_KEY='sk-...'
$env:AGENT_OPENAI_CHAT_MODEL='gpt-4o-mini'
```

`spring.ai.agent.demo-model.auto-seed=true` 时，启动会往 `model_config` 写入 CHAT 配置。改完重启终端 B。

### 日常再开（库已灌过）

1. 终端 A：`wsl-keep-redis.sh`（必须一直开）
2. 终端 B：`.\scripts\dev-backend.ps1`
3. 终端 C：`.\scripts\dev-frontend.ps1`

不要重复跑 `wsl-load-schema.sh`，除非你要重建演示数据。

---

## 二、Docker Compose

本机有 Docker 引擎时：

```bash
cp .env.example .env
docker compose up --build
```

| 服务 | 地址 |
|---|---|
| 控制台 | http://localhost:8080 |
| 后端 | http://localhost:10108/ai |
| Postgres | localhost:**5432**（compose 映射，与 WSL 的 5433 不同） |
| Redis | localhost:**6379** |

Windows 报 `无法将 docker 项识别为 cmdlet` 时不要用这一节，用上面「本地启动」。

---

## 三、纯 Linux / macOS（不用 WSL）

自备 Postgres（含 pgvector，可用 5432）和 Redis（6379），然后：

```bash
export POSTGRES_HOST=127.0.0.1 POSTGRES_PORT=5432
export POSTGRES_DB=business_agent POSTGRES_USER=agent POSTGRES_PASSWORD=agent
export REDIS_HOST=127.0.0.1 REDIS_PORT=6379
./scripts/init-db.sh
./scripts/dev-backend.sh
# 另一终端
./scripts/dev-frontend.sh
```

并改 `backend/config/application-local.yaml` 里的端口，或只靠环境变量（`config/datasource.yaml` / `cloud.yaml` 已用 `${POSTGRES_PORT:5432}`、`${REDIS_PORT:6379}`）。

---

## 验收

| 项 | 期望 |
|---|---|
| `GET /ai/actuator/health` | `{"status":"UP"}` |
| `POST /ai/data-agent/query` `{}` | Demo Agent、订单智能体、账单分析智能体，`status=published` |
| 控制台 | 无登录、有侧栏 |
| 对话 SSE | `POST /ai/stream/search`，`text/event-stream` |

常见失败：

| 现象 | 原因 |
|---|---|
| `docker` 不是 cmdlet | 走「本地启动」，不要 compose |
| Redis `Connection refused :16379` | 没开 `wsl-keep-redis.sh`，或窗口被关 |
| 连上 5432 失败 | WSL 集群是 **5433** |
| 全部 404 | 漏了 `/ai`，或 nginx `proxy_pass` 无尾斜杠 |
| 对话一直转圈 | 反代缓冲了 SSE |
| 列表空 / Tenant context is required | 未灌 seed，或后端未读到演示用户 |
| 编排线程池启动失败 | `config/agent.yaml` 四项缺失 |
| `vector` 类型错误 | 没有 pgvector |
| 前端 6868 打不开 | 端口被占用，或没在 `frontend/` 执行 `pnpm dev` |
