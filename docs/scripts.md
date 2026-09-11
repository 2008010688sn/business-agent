# 脚本

仓库里的脚本分两类：**抽取一次性** 与 **日常开发**。开源使用者只需要日常脚本。

## 日常（建议使用）

Windows + WSL 本地启动顺序见 [getting-started.md](getting-started.md)。

| 脚本 | 平台 | 作用 |
|---|---|---|
| `scripts/wsl-setup-db.sh` | 在 WSL 里跑 | 创建 `agent` 用户、`business_agent` 库、`vector` 扩展 |
| `scripts/wsl-load-schema.sh` | 在 WSL 里跑 | 灌 `schema.sql` + `seed-demo.sql`（一次性） |
| `scripts/wsl-keep-redis.sh` | 在 WSL 里跑 | Redis `--network host` 监听 **16379**，循环保活，**开发时不要关** |
| `scripts/wsl-start-redis.sh` | 在 WSL 里跑 | 只拉起一次 Redis，不保活 |
| `scripts/dev-backend.ps1` | Windows | 默认连 `127.0.0.1:5433` / `16379`，install `framework-lite` 后 `spring-boot:run` |
| `scripts/dev-frontend.ps1` | Windows | `pnpm dev`（端口 **6868**） |
| `scripts/dev-backend.sh` | Linux/macOS | 本机 5432/6379 时用，需自己 export 端口 |
| `scripts/dev-frontend.sh` | Linux/macOS | `pnpm dev`（端口 **6868**） |
| `scripts/export_order_bill_demo.py` | 维护者 | 从源库拷本月订单/账单行 + 技能配置；需要 `SRC_PG_*` 环境变量，不要把密码写进仓库 |
| `scripts/init-db.sh` / `init-db.ps1` | 有本机 `psql` 时 | 灌库；WSL 场景请用 `wsl-load-schema.sh` |
| `scripts/smoke-stream.py` | 任意 | 创建会话并打 `POST /ai/stream/search` |
| `scripts/brand-scan.py` | 任意 | 扫描公司域名、Nacos、Sa-Token、业务 MCP 残留 |

不要用 `wsl-redis-native.sh`（Alpine 二进制在 Ubuntu 上无法执行）。

`brand-scan.py` 在推送 GitHub 前应退出码 0。NOTICE 里的上游 Apache/MIT 声明不算违规。

## 抽取一次性（维护者）

从私有单体拷出时用过，**日常启动不要跑**：

| 脚本 | 作用 |
|---|---|
| `scripts/rewrite_packages.py` | `com.xx.cloud.ai` → `com.sn68.agent`，配置前缀 `spring.ai.xx.data-agent` → `spring.ai.agent` |
| `scripts/sanitize_backend.py` | 后端去 Feign / 权限注解等机械清洗 |

再跑会把已改名的代码改坏。

## Docker

仓库根 `docker-compose.yml` + `backend/Dockerfile` + `frontend/Dockerfile`。见 [getting-started.md](getting-started.md)。
