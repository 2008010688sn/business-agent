# Contributing

## Layout

- Java: `backend/` + `framework-lite/`, Java 17, Spring Boot 3.5.8
- UI: `frontend/`, Vue 3, pnpm 9, Node 20
- Do not add Nacos, IAM, or business OMS modules

## Commands

```bash
mvn -f framework-lite/pom.xml -DskipTests install
mvn -f backend/pom.xml -DskipTests compile
cd frontend && pnpm install && pnpm build
```

## Rules

- Config keys under `spring.ai.agent.*` must match Java prefixes in the same change.
- HTTP: keep `/ai/stream/search` for chat SSE; do not nest it under `/data-agent`.
- Demo remains Open Access unless you add an explicit, documented auth module.
- New files: `@author sn68`, Apache-2.0 header. Keep upstream Apache/MIT notices in `NOTICE`.

See `docs/` for architecture and runtime flow.
