<#
.SYNOPSIS
  启动 / 停止 / 查看 oj-cloud 的 6 个 Spring Boot 服务（宿主机 JVM 方式）。

.DESCRIPTION
  用最省内存的 JVM 参数（-Xmx256m）跑 5 个业务服务 + 1 个网关，
  合计常驻约 1.2 GB。日志与 pid 落在 logs/ 下（已 gitignore）。

  为什么不放容器里跑：Windows + Docker Desktop 下容器注册的是容器 IP，
  宿主机 curl 经网关访问 lb:// 目标会踩连通性坑；宿主机 java -jar 注册 127.0.0.1
  最直接。构建仍然全程走 Maven 容器，宿主机只用到已存在的 JDK 17。

  ── 为什么必须用 javaw.exe 而不是 java.exe（Day 4 修）───────────────
  java.exe 是 **控制台子系统** 程序。无论用 Start-Process 还是
  Win32_Process.Create 启动，Windows 都会给它分配一个控制台，
  于是每启一个服务就弹一个黑窗口（实测 4 个服务 = 4 个 Windows Terminal
  窗口 + 4 个 conhost 进程）。批量启停时满屏弹窗，还会抢焦点。

  javaw.exe 是同一个 JVM 的 **GUI 子系统** 版本：不分配控制台、不建窗口，
  但 JVM 行为、参数、类路径、退出码全部一致。这是 Java 官方给
  「后台跑 Java 应用且不想有控制台窗口」的标准答案。

  代价：javaw 没有控制台，stdout/stderr 无处可去。所以日志必须走
  **文件**（Spring Boot 的 --logging.file.name），不能靠 shell 重定向。
  这也顺带解决了另一个老问题：Win32_Process.Create 无法做重定向，
  以前 logs/*.out.log 是空的。

.PARAMETER Action
  start | stop | status | restart | logs

.PARAMETER Only
  只操作指定服务，逗号分隔，如 "gateway,teaching"。默认全部。

.PARAMETER Tail
  -Action logs 时跟随的行数，默认 40。配合 -Follow 持续输出。

.PARAMETER Follow
  -Action logs 时持续跟随（类似 tail -f）。Ctrl+C 退出。

.EXAMPLE
  pwsh scripts/run-services.ps1 -Action start
  pwsh scripts/run-services.ps1 -Action start -Only "gateway,teaching,problem,judge"
  pwsh scripts/run-services.ps1 -Action status
  pwsh scripts/run-services.ps1 -Action logs -Only judge -Tail 100 -Follow
  pwsh scripts/run-services.ps1 -Action stop
#>
[CmdletBinding()]
param(
  [ValidateSet('start', 'stop', 'status', 'restart', 'logs')]
  [string]$Action = 'status',
  [string]$Only = '',
  [int]$Tail = 40,
  [switch]$Follow
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
# 注意：-D 开头的系统属性必须**整体加引号**，否则 Windows 命令行解析会把
#       -Dfile.encoding=UTF-8 拆成 -D 和 file.encoding=UTF-8，java 报
#       "Could not find or load main class .encoding=UTF-8"。本机实测踩到过。
$JVM_OPTS = @(
  '-Xms128m', '-Xmx256m',
  '-XX:MetaspaceSize=64m', '-XX:MaxMetaspaceSize=128m',
  '-XX:+UseSerialGC',
  '-Dfile.encoding=UTF-8',
  '-Duser.timezone=Asia/Shanghai',
  # 强制 headless：javaw 已经是 GUI 子系统，这里再加一层保险，
  # 杜绝任何 AWT/Swing 相关组件尝试创建窗口
  '-Djava.awt.headless=true'
)

function Get-JavaExe {
  # 优先 javaw.exe（GUI 子系统，不建控制台窗口）
  $candidates = @()
  if ($env:JAVA_HOME) {
    $candidates += (Join-Path $env:JAVA_HOME 'bin\javaw.exe')
    $candidates += (Join-Path $env:JAVA_HOME 'bin\java.exe')
  }
  $cmd = Get-Command javaw -ErrorAction SilentlyContinue
  if ($cmd) { $candidates += $cmd.Source }
  $cmd = Get-Command java -ErrorAction SilentlyContinue
  if ($cmd) {
    # 从 java.exe 推导同目录的 javaw.exe
    $candidates += (Join-Path (Split-Path $cmd.Source -Parent) 'javaw.exe')
    $candidates += $cmd.Source
  }
  foreach ($p in $candidates) {
    if ($p -and (Test-Path $p)) { return $p }
  }
  throw 'JDK 未找到：请设置 JAVA_HOME 或把 java/javaw 放进 PATH'
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
    Write-Host ("  [warn] {0} 端口 {1} {2}s 内未监听，看 logs\{0}.log" -f $n, $SERVICES[$n].port, $timeoutSec) -ForegroundColor Yellow
  }
}

function Start-One($name) {
  $svc = $SERVICES[$name]
  $existing = Get-RunningPid $name
  if ($existing) { Write-Host ("  [skip] {0,-8} 已在运行 pid={1}" -f $name, $existing); return }

  $jar = Join-Path $backend (Join-Path $svc.module ('target\' + $svc.jar))
  if (-not (Test-Path $jar)) {
    Write-Host ("  [fail] {0,-8} 缺少 jar：{1}（先跑 scripts\build-backend.ps1 -Package）" -f $name, $jar) -ForegroundColor Red
    return
  }

  New-Item -ItemType Directory -Force -Path $logDir, $pidDir | Out-Null
  $appLog = Join-Path $logDir "$name.log"

  # ── 启动方式：Win32_Process.Create 直接起 javaw.exe，中间不夹任何 shell ──
  #
  # 踩过的三个坑（都实测复现过）：
  #   ① Start-Process 起子进程 → 子进程挂在当前 PowerShell 的 Job Object 上，
  #      启动它的 PowerShell（或被 harness 托管的后台任务）一退出/kill，
  #      Windows 会把整棵进程树收掉。日志里明明写着 "Started XxxApplication"，
  #      但端口瞬间消失。
  #   ② Win32_Process.Create 但外面包 cmd.exe /c → cmd 这层仍会被 job object
  #      收走，连带 java 一起死，且重定向文件是空的。
  #   ③ 用 java.exe（控制台子系统）→ 每个服务弹一个黑窗口。
  #
  # 正确做法：Win32_Process.Create 直接起 **javaw.exe**，不夹 shell。
  #   · 不挂 Job Object → 进程不会随 shell 退出而死
  #   · GUI 子系统 → 不建控制台、不弹窗
  #   · 日志走 Spring Boot 的 --logging.file.name（见下）
  $javaExe = Get-JavaExe
  $isJavaw = ([System.IO.Path]::GetFileName($javaExe)).ToLower() -eq 'javaw.exe'

  # 每个参数单独加引号：-D 参数不加引号会被命令行解析拆开（见 $JVM_OPTS 注释）
  $quoted = $JVM_OPTS | ForEach-Object { '"' + $_ + '"' }
  # --logging.file.name 是 **Spring Boot 应用参数**，必须放在 -jar <jar> 之后；
  # 放前面会被当成 JVM 参数，java 报 Unrecognized option。
  $argStr = ($quoted -join ' ') + ' -jar "' + $jar + '"' + ' "--logging.file.name=' + $appLog + '"'
  $cmdLine = '"' + $javaExe + '" ' + $argStr

  $r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create `
        -Arguments @{ CommandLine = $cmdLine; CurrentDirectory = $root }
  if ($r.ReturnValue -ne 0) {
    Write-Host ("  [fail] {0,-8} Win32_Process.Create 失败，ReturnValue={1}" -f $name, $r.ReturnValue) -ForegroundColor Red
    return
  }
  $javaPid = $r.ProcessId
  $javaPid | Set-Content -Path (Get-PidFile $name) -Encoding ascii
  $mode = if ($isJavaw) { 'javaw·无窗口' } else { 'java·有窗口' }
  Write-Host ("  [start] {0,-8} pid={1} port={2}  {3}  [{4}]" -f $name, $javaPid, $svc.port, $svc.desc, $mode)
  if (-not $isJavaw) {
    Write-Host ("  [warn] {0,-8} 未找到 javaw.exe，退回 java.exe —— 会弹出控制台窗口" -f $name) -ForegroundColor Yellow
  }
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
  Write-Host '  服务          端口   PID     端口监听  Nacos 健康  窗口' -ForegroundColor Cyan
  Write-Host '  ------------  ----   ------  --------  ----------  ----'
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
    # 窗口列：有主窗口句柄说明在弹窗（应为 none）
    $winText = '-'
    if ($procId) {
      $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
      $winText = if ($p -and $p.MainWindowHandle -ne 0) { 'POPUP!' } else { 'none' }
    }
    $color = if ($procId -and $listen -and $healthy -gt 0 -and $winText -ne 'POPUP!') { 'Green' }
             elseif ($procId) { 'Yellow' } else { 'Gray' }
    Write-Host ("  {0,-12}  {1}   {2,-6}  {3,-8}  {4,-10}  {5}" -f $name, $svc.port, $pidText, $listenText, $nacosText, $winText) -ForegroundColor $color
  }
  Write-Host ''
}

function Show-Logs($names, $lines, $follow) {
  foreach ($n in $names) {
    $f = Join-Path $logDir "$n.log"
    Write-Host ''
    Write-Host "  ── $n（$f）──" -ForegroundColor Cyan
    if (-not (Test-Path $f)) {
      Write-Host '  (日志文件不存在，服务可能还没启动过)' -ForegroundColor DarkGray
      continue
    }
    if ($follow) {
      # 阻塞跟随：Get-Content -Wait 会一直输出到 Ctrl+C
      Get-Content $f -Tail $lines -Wait
    } else {
      Get-Content $f -Tail $lines
    }
  }
}

$targets = Resolve-Targets

switch ($Action) {
  'start' {
    Write-Host ''
    Write-Host '  启动 oj-cloud 服务（宿主机 JVM · javaw 无窗口 · -Xmx256m）' -ForegroundColor Cyan
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
  'logs'   { Show-Logs $targets $Tail $Follow }
}
