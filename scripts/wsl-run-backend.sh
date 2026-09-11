#!/usr/bin/env bash
set -euo pipefail
ROOT=/mnt/d/workspace/business-agent
export POSTGRES_HOST=127.0.0.1
export POSTGRES_PORT=5433
export POSTGRES_DB=business_agent
export POSTGRES_USER=agent
export POSTGRES_PASSWORD=agent
export REDIS_HOST=127.0.0.1
export REDIS_PORT=16379
export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
MVN="/mnt/c/Program Files/JetBrains/IntelliJ IDEA 2026.1/plugins/maven/lib/maven3/bin/mvn"
M2=/mnt/c/Users/3.0391/.m2/repository
bash "$ROOT/scripts/wsl-start-redis.sh"
cd "$ROOT"
exec "$MVN" -f backend/pom.xml -DskipTests "-Dmaven.repo.local=${M2}" spring-boot:run
