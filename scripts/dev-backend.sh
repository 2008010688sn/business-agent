#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mvn -f framework-lite/pom.xml -DskipTests install
mvn -f backend/pom.xml -DskipTests spring-boot:run
