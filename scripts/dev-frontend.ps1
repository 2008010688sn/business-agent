$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location (Join-Path $Root 'frontend')
if (-not (Test-Path 'node_modules')) {
  pnpm install
}
pnpm dev -- --port 6868 --host 0.0.0.0
