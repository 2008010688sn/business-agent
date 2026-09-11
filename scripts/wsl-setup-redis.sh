#!/usr/bin/env bash
set -euo pipefail
# Native Redis on 0.0.0.0:16379 so Windows 127.0.0.1 can connect (WSL NAT has no localhost
# forward for Docker-published ports).
docker rm -f business-agent-redis >/dev/null 2>&1 || true
if ! command -v redis-server >/dev/null 2>&1; then
  sudo apt-get update -y
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y redis-server
fi
sudo mkdir -p /etc/redis
sudo tee /etc/redis/business-agent.conf >/dev/null <<'EOF'
bind 0.0.0.0
port 16379
daemonize yes
pidfile /run/redis-business-agent.pid
logfile /var/log/redis/business-agent.log
dir /var/lib/redis
protected-mode no
save ""
EOF
sudo mkdir -p /var/log/redis /var/lib/redis
sudo chown redis:redis /var/log/redis /var/lib/redis 2>/dev/null || true
if [ -f /run/redis-business-agent.pid ]; then
  sudo kill "$(cat /run/redis-business-agent.pid)" 2>/dev/null || true
  sleep 1
fi
sudo redis-server /etc/redis/business-agent.conf
sleep 1
redis-cli -p 16379 ping
ss -lntp | grep 16379 || true
