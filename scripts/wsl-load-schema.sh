#!/usr/bin/env bash
set -euo pipefail
ROOT="/mnt/d/workspace/business-agent"
export PGPASSWORD="${POSTGRES_PASSWORD:-agent}"
PGHOST="${POSTGRES_HOST:-127.0.0.1}"
PGPORT="${POSTGRES_PORT:-5433}"
PGUSER="${POSTGRES_USER:-agent}"
PGDATABASE="${POSTGRES_DB:-business_agent}"
SQL="$ROOT/backend/src/main/resources/sql/pg"
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 -f "$SQL/schema.sql"
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 -f "$SQL/seed-demo.sql"
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 -f "$SQL/demo-biz-schema.sql"
if [ -f "$ROOT/data/demo-biz-data.sql" ]; then
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 -f "$ROOT/data/demo-biz-data.sql"
fi
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 -f "$SQL/seed-order-bill-agents.sql"
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -c "SET search_path TO agent, public; SELECT id, name, status FROM data_agent;"
echo SCHEMA_SEED_OK
