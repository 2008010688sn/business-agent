$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$env:PGPASSWORD = $(if ($env:POSTGRES_PASSWORD) { $env:POSTGRES_PASSWORD } else { 'agent' })
$hostName = $(if ($env:POSTGRES_HOST) { $env:POSTGRES_HOST } else { 'localhost' })
$port = $(if ($env:POSTGRES_PORT) { $env:POSTGRES_PORT } else { '5432' })
$db = $(if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { 'business_agent' })
$user = $(if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { 'agent' })
$sqlDir = Join-Path $Root 'backend\src\main\resources\sql\pg'
psql -h $hostName -p $port -U $user -d $db -f (Join-Path $sqlDir 'schema.sql')
psql -h $hostName -p $port -U $user -d $db -f (Join-Path $sqlDir 'seed-demo.sql')
psql -h $hostName -p $port -U $user -d $db -f (Join-Path $sqlDir 'demo-biz-schema.sql')
$bizData = Join-Path $Root 'data\demo-biz-data.sql'
if (Test-Path $bizData) {
  psql -h $hostName -p $port -U $user -d $db -f $bizData
}
psql -h $hostName -p $port -U $user -d $db -f (Join-Path $sqlDir 'seed-order-bill-agents.sql')
Write-Host "schema + seed applied to $db"
