# clone 后在 IDE 中启动

适用：已经 `git clone` 到本地，用 IntelliJ 跑后端、用 IntelliJ / WebStorm / VS Code 跑前端。

演示模式无登录。后端给每个请求注入用户 `sn68`、租户 `default`。

前端地址固定 **http://localhost:6868** 。后端 **http://127.0.0.1:10108/ai** 。

## 1. 克隆

```bash
git clone https://github.com/2008010688sn/business-agent.git
cd business-agent
```

本仓库没有根 `pom.xml`，不要按「单模块 Spring Boot」只打开 `backend` 却忘了先 install `framework-lite`。

## 2. 本机软件

| 软件 | 版本 |
|---|---|
| JDK | 17（IntelliJ Project SDK） |
| Maven | 3.9+（可用 IntelliJ 自带） |
| Node.js | 20+ |
| pnpm | 9（`corepack enable` 后 `corepack prepare pnpm@9.15.5 --activate`） |
| PostgreSQL 16 + pgvector | Windows 用 WSL，端口 **5433** |
| Redis | Windows 用 WSL，端口 **16379** |

## 3. 库和 Redis（IDE 点运行之前）

Windows 示例（仓库在 `D:\workspace\business-agent` 时，WSL 路径是 `/mnt/d/workspace/business-agent`）：

```powershell
wsl -d Ubuntu-24.04 -- bash /mnt/d/workspace/business-agent/scripts/wsl-setup-db.sh
wsl -d Ubuntu-24.04 -- bash /mnt/d/workspace/business-agent/scripts/wsl-load-schema.sh
```

另开一个终端，开发期间不要关：

```powershell
wsl -d Ubuntu-24.04 -- bash /mnt/d/workspace/business-agent/scripts/wsl-keep-redis.sh
```

自检 Redis：

```powershell
python -c "import socket; s=socket.create_connection(('127.0.0.1',16379),2); s.sendall(b'PING\r\n'); print(s.recv(16))"
```

应打印 `b'+PONG\r\n'`。

Linux / macOS：本机 Postgres 5432、Redis 6379，改 `backend/config/application-local.yaml` 后执行 `./scripts/init-db.sh`。

## 4. IntelliJ 启动后端

1. **File → Open**，选择仓库根 `business-agent`。
2. 打开右侧 **Maven** 工具窗口。点 **+**，导入：
   - `framework-lite/pom.xml`
   - `backend/pom.xml`
3. **Settings → Build, Execution, Deployment → Compiler → Annotation Processors**，勾选 **Enable annotation processing**。
4. **File → Project Structure → Project**：**SDK = 17**，Language level 17。
5. Maven 面板展开 `framework-lite` → **Lifecycle** → `install`。勾选 Skip Tests，或在 Runner 里加 `-DskipTests`。必须先成功，否则 `backend` 解析不到 `com.sn68.agent:framework-lite:1.0.0`。
6. 打开 `backend/src/main/java/com/sn68/agent/AgentApplication.java`，点类声明左侧绿色三角 → **Run 'AgentApplication'**。
7. **Run → Edit Configurations**，选中刚生成的 `AgentApplication`：

| 项 | 值 |
|---|---|
| Main class | `com.sn68.agent.AgentApplication` |
| Working directory | `$MODULE_DIR$`（即 `backend/`）。这样会加载 `backend/config/application-local.yaml` |
| Active profiles | `local` |
| JRE | 17 |
| Environment variables | 见下表 |

Windows + WSL 环境变量（用分号拼接进 IntelliJ 的 Environment variables）：

```
POSTGRES_HOST=127.0.0.1
POSTGRES_PORT=5433
POSTGRES_DB=business_agent
POSTGRES_USER=agent
POSTGRES_PASSWORD=agent
REDIS_HOST=127.0.0.1
REDIS_PORT=16379
```

工作目录如果是仓库根，会读 `config/application-local.yaml`，与 `backend/config/application-local.yaml` 内容一致。

就绪标志：

- 控制台：`Application 'business-agent' is running`
- 浏览器：http://127.0.0.1:10108/ai/actuator/health → `{"status":"UP"}`
- API 文档：http://127.0.0.1:10108/ai/doc.html

常见失败：

| 现象 | 处理 |
|---|---|
| Could not find artifact framework-lite | 先 Maven install `framework-lite` |
| Redis Connection refused :16379 | 没开 `wsl-keep-redis.sh` |
| 连 5432 失败 | WSL 集群是 **5433** |
| Port 10108 already in use | 已有一个后端进程，不要重复启动 |
| Lombok / log 方法找不到 | 打开 Annotation Processing，用 JDK 17 |

不要在 IDE 里再跑一遍 `.\scripts\dev-backend.ps1`，否则会抢 10108。

## 5. IDE 启动前端

端口写死 **6868**（`frontend/vite.config.ts` 的 `server.port` + `package.json` 的 `dev` 脚本 + `strictPort: true`）。

### IntelliJ / WebStorm

1. 内置 Terminal：

```powershell
cd frontend
pnpm install
```

2. **Run → Edit Configurations → + → npm**
   - package.json：`frontend/package.json`
   - Command：`run`
   - Scripts：`dev`
3. Run。控制台出现 `Local: http://localhost:6868/`。

### VS Code / Cursor

1. File → Open Folder，打开仓库根或 `frontend`。
2. 终端：

```powershell
cd frontend
pnpm install
pnpm dev
```

3. 打开 http://localhost:6868

代理：`.env.development` 里 `VITE_HTTP_PROXY=Y`，`VITE_SERVICE_BASE_URL=http://localhost:10108`。浏览器访问 `/api/ai/...`，Vite 去掉 `/api` 转到后端 `/ai/...`。

## 6. 打开控制台

浏览器 **http://localhost:6868**（不要用 9527）。

- 无登录。
- 智能体列表应有 Demo Agent、订单智能体、账单分析智能体。
- 未配 `AGENT_OPENAI_API_KEY` 时发消息走 SSE `event:error`，不是连不上库。

对话接口：`POST /api/ai/stream/search`（后端 `/ai/stream/search`）。

## 7. 可选：配模型

在启动后端的 Run Configuration 里加：

```
AGENT_OPENAI_BASE_URL=https://api.openai.com/v1
AGENT_OPENAI_API_KEY=sk-...
AGENT_OPENAI_CHAT_MODEL=gpt-4o-mini
```

`spring.ai.agent.demo-model.auto-seed=true` 时，启动会写入 `model_config`。改完重启后端配置。
