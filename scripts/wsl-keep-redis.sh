#!/usr/bin/env bash
set -euo pipefail
ROOT=/mnt/d/workspace/business-agent
bash "$ROOT/scripts/wsl-start-redis.sh"
while true; do
  docker start business-agent-redis >/dev/null 2>&1 || bash "$ROOT/scripts/wsl-start-redis.sh" >/dev/null
  sleep 5
done
