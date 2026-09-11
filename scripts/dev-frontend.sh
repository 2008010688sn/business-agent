#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/frontend"
if [ ! -d node_modules ]; then
  pnpm install
fi
pnpm dev -- --port 6868 --host 0.0.0.0
