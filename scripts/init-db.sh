#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${POSTGRES_HOST:=localhost}"
: "${POSTGRES_PORT:=5432}"
: "${POSTGRES_DB:=business_agent}"
: "${POSTGRES_USER:=agent}"
: "${PGPASSWORD:=${POSTGRES_PASSWORD:-agent}}"
export PGPASSWORD
SQL="$ROOT/backend/src/main/resources/sql/pg"
psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -f "$SQL/schema.sql"
psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -f "$SQL/seed-demo.sql"
psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -f "$SQL/demo-biz-schema.sql"
if [ -f "$ROOT/data/demo-biz-data.sql" ]; then
  psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    -f "$ROOT/data/demo-biz-data.sql"
fi
psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -f "$SQL/seed-order-bill-agents.sql"
echo "schema + seed applied to $POSTGRES_DB"
