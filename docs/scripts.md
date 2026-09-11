# 脚本

开源仓库只保留日常启动与验收脚本。从私有单体拆分时用过的 Python 清洗/导出脚本已删除，不要再跑。

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
| `scripts/init-db.sh` / `init-db.ps1` | 有本机 `psql` 时 | 灌库；WSL 场景请用 `wsl-load-schema.sh` |
| `scripts/smoke-stream.py` | 任意 | 创建会话并打 `POST /ai/stream/search`，确认后端问答通 |

不要用 `wsl-redis-native.sh`（Alpine 二进制在 Ubuntu 上无法执行）。

模型 Key 只允许在控制台或环境变量 `AGENT_OPENAI_API_KEY` 写入本地库，不要进 git。

## Docker

仓库根 `docker-compose.yml` + `backend/Dockerfile` + `frontend/Dockerfile`。见 [getting-started.md](getting-started.md)。
