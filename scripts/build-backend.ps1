# 容器内编译 / 打包后端（宿主机没有 Maven，一律走容器）
#
#   pwsh scripts/build-backend.ps1              # clean compile
#   pwsh scripts/build-backend.ps1 -Package     # clean package（产出 6 个 fat jar）
#   pwsh scripts/build-backend.ps1 -SkipTests   # package 时跳过测试（默认就跳）
#
# 首次约 3 分钟（下 BOM 与全部 starter），之后有 ~/.m2 缓存约 30 秒。
# 依赖下载慢时按 agent.md §2.4 在 ~/.m2/settings.xml 配阿里云镜像。

[CmdletBinding()]
param(
  [switch]$Package,
  [switch]$NoClean
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$backend = Join-Path $root 'backend'
$m2 = Join-Path $env:USERPROFILE '.m2'

if (-not (Test-Path $backend)) { throw "找不到 backend 目录：$backend" }

$goal = if ($Package) { 'package' } else { 'compile' }
$args = @('mvn', '-B')
if (-not $NoClean) { $args += 'clean' }
$args += $goal
if ($Package) { $args += '-DskipTests' }

Write-Host ''
Write-Host "  编译后端：$($args -join ' ')" -ForegroundColor Cyan
Write-Host "  工作目录：$backend" -ForegroundColor DarkGray
Write-Host "  Maven 缓存：$m2" -ForegroundColor DarkGray
Write-Host ''

$image = 'maven:3.9-eclipse-temurin-17'
$haveImage = docker images -q $image 2>$null
if (-not $haveImage) {
  Write-Host "  本地无 $image，尝试从国内镜像拉取..." -ForegroundColor Yellow
  docker pull docker.m.daocloud.io/maven:3.9-eclipse-temurin-17
  if ($LASTEXITCODE -ne 0) { throw '镜像拉取失败，请手动 docker pull maven:3.9-eclipse-temurin-17' }
  docker tag docker.m.daocloud.io/maven:3.9-eclipse-temurin-17 $image
}

$sw = [System.Diagnostics.Stopwatch]::StartNew()
docker run --rm `
  -v "${backend}:/w" `
  -v "${m2}:/root/.m2" `
  -w /w $image @args
$code = $LASTEXITCODE
$sw.Stop()

Write-Host ''
if ($code -eq 0) {
  Write-Host ("  [OK] BUILD SUCCESS（{0:N0}s）" -f $sw.Elapsed.TotalSeconds) -ForegroundColor Green
  if ($Package) {
    $jars = Get-ChildItem (Join-Path $backend '*\target\*.jar') -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'sources|javadoc' }
    Write-Host ''
    Write-Host '  产出：' -ForegroundColor Cyan
    foreach ($j in $jars) {
      Write-Host ("    {0,-52} {1,7:N1} MB" -f $j.Name, ($j.Length / 1MB)) -ForegroundColor DarkGray
    }
  }
} else {
  Write-Host "  [FAIL] 编译失败，退出码 $code" -ForegroundColor Red
}
Write-Host ''
exit $code
