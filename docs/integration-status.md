# Parallel extract status (2026-09-11)

Four agents wrote disjoint trees into `D:\workspace\business-agent` (new git repo, not xx-cloud history).

| Stream | Result |
|---|---|
| framework-lite | `mvn -DskipTests compile` SUCCESS; no Sa-Token/Nacos; ThreadLocal key `USER_INFO_KEY` |
| backend | `mvn -DskipTests compile` and `test-compile` SUCCESS; OpenAccessFilter + LocalFileService + DemoModelSeeder |
| frontend | `pnpm build` SUCCESS; 267-line ai-agent-only routes; default home `ai-agent_agents`; nginx SSE buffering off |
| config/compose | local yaml, schema 110 tables, seed Demo Agent, docker-compose |

## Closed-loop wiring checked by orchestrator

- ThreadLocal keys match (`USER_INFO_KEY` in filter, async bridge, AuthenticationContext).
- `AuthenticationContext.getContext()` does not call `StpUtil`.
- nginx `proxy_pass http://backend:10108/;` with `proxy_buffering off`.
- Stream path remains `/ai/stream/search`.
- Seed tenant `default` + published Demo Agent + knowledge-qa binding.
- `agent_file` table present.

## Remaining before GitHub push

- Company host strings in tests were rewritten to `example.com` / `10.0.0.1`; re-run a brand scan on the whole tree.
- `docker compose up --build` not yet run in this session (needs Docker + optional model key).
- Do not `git push` until secret/brand scan is clean and you approve commit.
- Frontend IAM/trove pickers are empty stubs; not required for v1 chat loop.
