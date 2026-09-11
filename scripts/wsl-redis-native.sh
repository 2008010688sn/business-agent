#!/usr/bin/env bash
set -euo pipefail
# Copy redis-server out of the alpine image so it stays up like native Postgres
# (WSL Docker published ports flap on Windows 127.0.0.1).
BIN=/usr/local/bin/redis-server-ba
if [ ! -x "$BIN" ]; then
  cid="$(docker ps -aq --filter name=business-agent-redis | head -1)"
  if [ -z "$cid" ]; then
    cid="$(docker create redis:7-alpine)"
    created=1
  fi
  docker cp "$cid:/usr/local/bin/redis-server" "$BIN"
  if [ "${created:-0}" = 1 ]; then
    docker rm "$cid" >/dev/null
  fi
  chmod +x "$BIN"
fi
docker rm -f business-agent-redis >/dev/null 2>&1 || true
if ss -lnt | grep -q ':16379'; then
  echo "port 16379 already listening"
else
  "$BIN" --port 16379 --bind 0.0.0.0 --protected-mode no --daemonize yes --save "" --logfile /tmp/redis-ba.log
fi
sleep 0.5
ss -lntp | grep 16379 || true
echo REDIS_NATIVE_OK
