$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
function Resolve-Mvn {
  $cmd = Get-Command mvn -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  $idea = 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.1\plugins\maven\lib\maven3\bin\mvn.cmd'
  if (Test-Path $idea) { return $idea }
  throw 'mvn not found. Install Maven 3.9+ or add it to PATH.'
}
if (-not $env:POSTGRES_HOST) { $env:POSTGRES_HOST = '127.0.0.1' }
if (-not $env:POSTGRES_PORT) { $env:POSTGRES_PORT = '5433' }
if (-not $env:POSTGRES_DB) { $env:POSTGRES_DB = 'business_agent' }
if (-not $env:POSTGRES_USER) { $env:POSTGRES_USER = 'agent' }
if (-not $env:POSTGRES_PASSWORD) { $env:POSTGRES_PASSWORD = 'agent' }
if (-not $env:REDIS_HOST) { $env:REDIS_HOST = '127.0.0.1' }
if (-not $env:REDIS_PORT) { $env:REDIS_PORT = '16379' }
$mvn = Resolve-Mvn
Set-Location $Root
Write-Host "Postgres=$($env:POSTGRES_HOST):$($env:POSTGRES_PORT) Redis=$($env:REDIS_HOST):$($env:REDIS_PORT)"
& $mvn -f framework-lite/pom.xml "-DskipTests" install
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $mvn -f backend/pom.xml "-DskipTests" spring-boot:run
exit $LASTEXITCODE
