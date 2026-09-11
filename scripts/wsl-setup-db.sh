#!/usr/bin/env bash
set -euo pipefail
PGPORT="${PGPORT:-5433}"
echo "postgres port ${PGPORT}"
sudo -u postgres psql -p "${PGPORT}" -c 'SELECT version();'
if ! sudo -u postgres psql -p "${PGPORT}" -tAc "SELECT 1 FROM pg_roles WHERE rolname='agent'" | grep -q 1; then
  sudo -u postgres psql -p "${PGPORT}" -c "CREATE USER agent WITH PASSWORD 'agent' SUPERUSER"
fi
if ! sudo -u postgres psql -p "${PGPORT}" -tAc "SELECT 1 FROM pg_database WHERE datname='business_agent'" | grep -q 1; then
  sudo -u postgres psql -p "${PGPORT}" -c "CREATE DATABASE business_agent OWNER agent"
fi
sudo -u postgres psql -p "${PGPORT}" -d business_agent -c "CREATE EXTENSION IF NOT EXISTS vector"
echo "DB_OK"

if docker inspect business-agent-redis >/dev/null 2>&1; then
  docker start business-agent-redis >/dev/null
else
  docker run -d --name business-agent-redis --restart unless-stopped -p 0.0.0.0:16379:6379 redis:7-alpine
fi
docker ps --filter name=business-agent-redis --format '{{.Names}} {{.Status}} {{.Ports}}'
