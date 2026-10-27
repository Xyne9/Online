<#
.SYNOPSIS
  启动 / 停止 / 查看 oj-cloud 的 6 个 Spring Boot 服务（宿主机 JVM 方式）。

.DESCRIPTION
  Day2 交付脚本。用最省内存的 JVM 参数（-Xmx256m）跑 5 个业务服务 + 1 个网关，
  合计常驻约 1.2 GB。日志与 pid 落在 logs/ 下（已 gitignore）。

  为什么不放容器里跑：Windows + Docker Desktop 下容器注册的是容器 IP，
  宿主机 curl 经网关访问 lb:// 目标会踩连通性坑；宿主机 java -jar 注册 127.0.0.1
  最直接。构建仍然全程走 Maven 容器，宿主机只用到已存在的 JDK 17。

.PARAMETER Action
  start | stop | status | restart

.PARAMETER Only
  只操作指定服务，逗号分隔，如 "gateway,teaching"。默认全部。

.EXAMPLE
  pwsh scripts/run-services.ps1 -Action start
  pwsh scripts/run-services.ps1 -Action status
  pwsh scripts/run-services.ps1 -Action stop
#>
[CmdletBinding()]
param(
  [ValidateSet('start', 'stop', 'status', 'restart')]
  [string]$Action = 'status',
  [string]$Only = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$backend = Join-Path $root 'backend'
$logDir = Join-Path $root 'logs'
$pidDir = Join-Path $logDir 'pids'

# 服务清单：短名 -> 模块目录 / jar 前缀 / 端口
$SERVICES = [ordered]@{
  gateway  = @{ module = 'oj-gateway';            jar = 'oj-gateway-1.0.0-SNAPSHOT.jar';            port = 8080; desc = '统一网关' }
  teaching = @{ module = 'oj-teaching-service';   jar = 'oj-teaching-service-1.0.0-SNAPSHOT.jar';   port = 8081; desc = '教务/认证/查重' }
  problem  = @{ module = 'oj-problem-service';    jar = 'oj-problem-service-1.0.0-SNAPSHOT.jar';    port = 8082; desc = '题库/测试点' }
  judge    = @{ module = 'oj-judge-service';      jar = 'oj-judge-service-1.0.0-SNAPSHOT.jar';      port = 8083; desc = '异步判题/沙箱' }
  ai       = @{ module = 'oj-ai-agent-service';   jar = 'oj-ai-agent-service-1.0.0-SNAPSHOT.jar';   port = 8084; desc = '启发式 AI Agent' }
  admin    = @{ module = 'oj-admin-service';      jar = 'oj-admin-service-1.0.0-SNAPSHOT.jar';      port = 8085; desc = '运维/审计/沙箱监控' }
}

# 内存预算：5 业务 + 网关全开约 1.2 GB（不配默认会吃掉 6~8 GB）
$JVM_OPTS = @(
  '-Xms128m', '-Xmx256m',
  '-XX:MetaspaceSize=64m', '-XX:MaxMetaspaceSize=128m',
  '-XX:+UseSerialGC',
  '-Dfile.encoding=UTF-8',
  '-Duser.timezone=Asia/Shanghai'
)

function Get-JavaExe {
  if ($env:JAVA_HOME) {
    $p = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path $p) { return $p }
  }
  $cmd = Get-Command java -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  throw 'JDK 未找到：请设置 JAVA_HOME 或把 java 放进 PATH'
}

function Resolve-Targets {
  if (-not $Only) { return @($SERVICES.Keys) }
  $want = $Only.Split(',') | ForEach-Object { $_.Trim().ToLower() }
  $bad = $want | Where-Object { -not $SERVICES.Contains($_) }
  if ($bad) { throw "未知服务名：$($bad -join ', ')；可选：$($SERVICES.Keys -join ', ')" }
  return $want
}

function Get-PidFile($name) { Join-Path $pidDir "$name.pid" }

function Get-RunningPid($name) {
  $f = Get-PidFile $name
  if (-not (Test-Path $f)) { return $null }
  $procId = [int](Get-Content $f -Raw).Trim()
  $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
  if ($p -and -not $p.HasExited) { return $procId }
  return $null
}

function Wait-Ports($names, $timeoutSec) {
  # 并行等所有端口：串行 6 个 × 90s 最坏要 9 分钟，实际服务是同时起来的。
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $pending = @{}
  foreach ($n in $names) { $pending[$n] = $SERVICES[$n].port }
  while ((Get-Date) -lt $deadline -and $pending.Count -gt 0) {
    foreach ($n in @($pending.Keys)) {
      $ok = Test-NetConnection 127.0.0.1 -Port $pending[$n] -InformationLevel Quiet -WarningAction SilentlyContinue
      if ($ok) { $pending.Remove($n) }
    }
    if ($pending.Count -gt 0) { Start-Sleep -Milliseconds 800 }
  }
  foreach ($n in $pending.Keys) {
    Write-Host ("  [warn] {0} 端口 {1} {2}s 内未监听，看 logs\{0}.out.log" -f $n, $SERVICES[$n].port, $timeoutSec) -ForegroundColor Yellow
  }
}

function Start-One($name) {
  $svc = $SERVICES[$name]
  $existing = Get-RunningPid $name
  if ($existing) { Write-Host ("  [skip] {0,-8} 已在运行 pid={1}" -f $name, $existing); return }

  $jar = Join-Path $backend (Join-Path $svc.module ('target\' + $svc.jar))
  if (-not (Test-Path $jar)) {
    Write-Host ("  [fail] {0,-8} 缺少 jar：{1}（先跑 scripts\build-backend.ps1）" -f $name, $jar) -ForegroundColor Red
    return
  }

  New-Item -ItemType Directory -Force -Path $logDir, $pidDir | Out-Null
  $outLog = Join-Path $logDir "$name.out.log"
  $errLog = Join-Path $logDir "$name.err.log"

  # 注意：Start-Process 的 -ArgumentList 是把数组用空格拼成一条命令行，
  # 不会自动加引号。工作目录含中文与空格（D:\毕设\agent oj），jar 路径必须
  # 自己裹上双引号，否则 java 只收到 "D:\毕设\agent"，报 Unable to access jarfile。
  $argList = @($JVM_OPTS) + @('-jar', "`"$jar`"")
  $proc = Start-Process -FilePath (Get-JavaExe) -ArgumentList $argList `
    -RedirectStandardOutput $outLog -RedirectStandardError $errLog `
    -WindowStyle Hidden -PassThru
  $proc.Id | Set-Content -Path (Get-PidFile $name) -Encoding ascii
  Write-Host ("  [start] {0,-8} pid={1} port={2}  {3}" -f $name, $proc.Id, $svc.port, $svc.desc)
}

function Stop-One($name) {
  $procId = Get-RunningPid $name
  if (-not $procId) { Write-Host ("  [skip] {0,-8} 未在运行" -f $name); return }
  try {
    Stop-Process -Id $procId -Force -ErrorAction Stop
    Write-Host ("  [stop] {0,-8} pid={1}" -f $name, $procId)
  } catch {
    Write-Host ("  [warn] {0,-8} 停止失败：{1}" -f $name, $_.Exception.Message) -ForegroundColor Yellow
  }
  Remove-Item (Get-PidFile $name) -Force -ErrorAction SilentlyContinue
}

function Show-Status {
  Write-Host ''
  Write-Host '  服务          端口   PID     端口监听  Nacos 健康' -ForegroundColor Cyan
  Write-Host '  ------------  ----   ------  --------  ----------'
  $nacosMap = @{}
  try {
    $list = Invoke-RestMethod 'http://127.0.0.1:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=50' -TimeoutSec 5
    foreach ($s in $list.doms) {
      try {
        $inst = Invoke-RestMethod "http://127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName=$s" -TimeoutSec 5
        $nacosMap[$s] = ($inst.hosts | Where-Object { $_.healthy } | Measure-Object).Count
      } catch { $nacosMap[$s] = 0 }
    }
  } catch { }

  foreach ($name in $SERVICES.Keys) {
    $svc = $SERVICES[$name]
    $procId = Get-RunningPid $name
    $listen = Test-NetConnection 127.0.0.1 -Port $svc.port -InformationLevel Quiet -WarningAction SilentlyContinue
    # Nacos 里的服务名不一定等于 'oj-' + 短名 + '-service'：
    # ai 的真实名是 oj-ai-agent-service（不是 oj-ai-service），
    # gateway 是 oj-gateway。必须显式映射，不能靠拼字符串猜。
    $NACOS_NAME = @{
      gateway  = 'oj-gateway'
      teaching = 'oj-teaching-service'
      problem  = 'oj-problem-service'
      judge    = 'oj-judge-service'
      ai       = 'oj-ai-agent-service'
      admin    = 'oj-admin-service'
    }
    $svcName = $NACOS_NAME[$name]
    $healthy = if ($svcName -and $nacosMap.ContainsKey($svcName)) { $nacosMap[$svcName] } else { 0 }
    $pidText = if ($procId) { "$procId" } else { '-' }
    $listenText = if ($listen) { 'UP' } else { 'DOWN' }
    $nacosText = if ($healthy -gt 0) { "healthy($healthy)" } else { 'absent' }
    $color = if ($procId -and $listen -and $healthy -gt 0) { 'Green' } elseif ($procId) { 'Yellow' } else { 'Gray' }
    Write-Host ("  {0,-12}  {1}   {2,-6}  {3,-8}  {4}" -f $name, $svc.port, $pidText, $listenText, $nacosText) -ForegroundColor $color
  }
  Write-Host ''
}

$targets = Resolve-Targets

switch ($Action) {
  'start' {
    Write-Host ''
    Write-Host '  启动 oj-cloud 服务（宿主机 JVM，-Xmx256m）' -ForegroundColor Cyan
    foreach ($n in $targets) { Start-One $n }
    Write-Host ''
    Write-Host '  等待端口就绪与 Nacos 注册（最多 120s）...' -ForegroundColor DarkGray
    Wait-Ports $targets 120
    # Nacos 心跳 5s，再等一轮让注册表稳定
    Start-Sleep -Seconds 8
    Show-Status
  }
  'stop' {
    Write-Host ''
    Write-Host '  停止 oj-cloud 服务' -ForegroundColor Cyan
    foreach ($n in $targets) { Stop-One $n }
    Write-Host ''
  }
  'restart' {
    foreach ($n in $targets) { Stop-One $n }
    Start-Sleep -Seconds 2
    foreach ($n in $targets) { Start-One $n }
    Wait-Ports $targets 120
    Start-Sleep -Seconds 8
    Show-Status
  }
  'status' { Show-Status }
}
