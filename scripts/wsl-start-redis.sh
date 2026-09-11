#!/usr/bin/env bash
set -euo pipefail
docker rm -f business-agent-redis >/dev/null 2>&1 || true
docker run -d --name business-agent-redis --restart always --network host \
  redis:7-alpine redis-server --port 16379 --bind 0.0.0.0 --protected-mode no --save ''
sleep 1
ss -lntp | grep 16379
echo REDIS_UP
