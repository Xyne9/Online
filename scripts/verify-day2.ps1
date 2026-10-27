# Day 2-3 一键验收 —— 前端 Stage 0 底座 + 后端 Maven 骨架与 Nacos 注册
#
# 对应《从 0 到 1 的 6 步标准开发顺序与操作手册》第 2 步（第 2～3 天）。
#
#   pwsh scripts/verify-day2.ps1              # 全验（需后端已启动）
#   pwsh scripts/verify-day2.ps1 -SkipBackend # 只验前端
#   pwsh scripts/verify-day2.ps1 -SkipFrontend
#
# 退出码 0 = 全通过。

[CmdletBinding()]
param(
  [switch]$SkipFrontend,
  [switch]$SkipBackend,
  [switch]$SkipVisual   # 跳过截图比对（较慢，约 60s）
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$results = New-Object System.Collections.Generic.List[object]

function T($name, $ok, $detail) {
  $script:results.Add([pscustomobject]@{ Name = $name; Ok = [bool]$ok; Detail = $detail })
  $mark = if ($ok) { '[PASS]' } else { '[FAIL]' }
  $color = if ($ok) { 'Green' } else { 'Red' }
  Write-Host ("  {0} {1}" -f $mark, $name) -ForegroundColor $color
  if ($detail) { Write-Host ("         {0}" -f $detail) -ForegroundColor DarkGray }
}

function Section($t) {
  Write-Host ''
  Write-Host "  ── $t ──" -ForegroundColor Cyan
}

Write-Host ''
Write-Host '  ╔══════════════════════════════════════════════════════════╗' -ForegroundColor Cyan
Write-Host '  ║  Day 2-3 验收：前端 Stage 0 + 后端 Maven 骨架            ║' -ForegroundColor Cyan
Write-Host '  ╚══════════════════════════════════════════════════════════╝' -ForegroundColor Cyan

# ══════════════════════════════════════════════════════════════════════
# 一、前端 Stage 0
# ══════════════════════════════════════════════════════════════════════
if (-not $SkipFrontend) {
  Section '前端 Stage 0 · 文件齐备性'
  $app = Join-Path $root 'prototype\app'

  $needApi = @('http.js', 'sse.js', 'poll.js', 'mock.js')
  foreach ($f in $needApi) {
    $p = Join-Path $app "api\$f"
    T "api/$f 存在" (Test-Path $p) $(if (Test-Path $p) { "{0:N0} B" -f (Get-Item $p).Length } else { '缺失' })
  }

  $needAdapter = @('status.js', 'difficulty.js', 'language.js', 'time.js', 'unit.js', 'id.js', 'page.js', 'index.js')
  foreach ($f in $needAdapter) {
    $p = Join-Path $app "adapter\$f"
    T "adapter/$f 存在" (Test-Path $p) ''
  }

  T 'boot.js 存在' (Test-Path (Join-Path $app 'boot.js')) ''
  T 'login.html 存在（第 17 页）' (Test-Path (Join-Path $app 'login.html')) ''

  Section '前端 Stage 0 · 16 页脚本挂载'

  $pages = @(
    'a-audit', 'a-models', 's-analytics', 's-assignments', 's-exam', 's-problems',
    's-submissions', 's-workbench', 't-analytics', 't-assignments', 't-dashboard',
    't-monitor', 't-plagiarism', 't-problem-edit', 't-problems'
  )
  # login.html 单独算，index.html 是唯一无脚本的静态门户
  $allPages = $pages + 'login'

  $missing = @()
  $orderBad = @()
  foreach ($pg in $allPages) {
    $fp = Join-Path $app "$pg.html"
    if (-not (Test-Path $fp)) { $missing += "$pg.html 不存在"; continue }
    $html = Get-Content $fp -Raw
    foreach ($need in @('api/http.js', 'api/sse.js', 'api/poll.js', 'adapter/index.js', 'api/mock.js', 'boot.js')) {
      if ($html -notmatch [regex]::Escape($need)) { $missing += "$pg.html 缺 $need" }
    }
    # 顺序：http → sse → poll → adapter/index → mock → boot → alpine
    # 注意 s-workbench.html 是特例：它的 s-workbench.js 与 alpine.min.js 都在 <head> 带 defer，
    # 页尾的 Stage 0 脚本不带 defer（普通脚本解析期立即执行，早于 head 的 defer），
    # 所以对它是「mock → boot」在 head 的 alpine 之前 —— 时序仍正确，判定要排除 alpine 这一项。
    # 用 `<script src="X"` 定位，避免命中注释里提到的文件名
    $idx = @{}
    foreach ($k in @('api/http.js', 'api/sse.js', 'api/poll.js', 'adapter/index.js', 'api/mock.js', 'boot.js', 'vendor/alpine.min.js')) {
      $idx[$k] = $html.IndexOf('<script src="' + $k + '"')
    }
    $seq = @('api/http.js', 'api/sse.js', 'api/poll.js', 'adapter/index.js', 'api/mock.js', 'boot.js', 'vendor/alpine.min.js')
    if ($pg -eq 's-workbench') { $seq = @('api/http.js', 'api/sse.js', 'api/poll.js', 'adapter/index.js', 'api/mock.js', 'boot.js') }
    for ($i = 0; $i -lt $seq.Count - 1; $i++) {
      if ($idx[$seq[$i]] -lt 0 -or $idx[$seq[$i + 1]] -lt 0) { continue }
      if ($idx[$seq[$i]] -gt $idx[$seq[$i + 1]]) {
        $orderBad += "$pg.html: $($seq[$i]) 应在 $($seq[$i+1]) 之前"
      }
    }
  }
  T "15 个业务页 + login.html 全部挂载 api/adapter/boot" ($missing.Count -eq 0) `
    $(if ($missing.Count) { $missing -join '; ' } else { '16/16 页齐备' })
  T '脚本引用顺序正确（http→sse→poll→adapter→mock→boot→alpine）' ($orderBad.Count -eq 0) `
    $(if ($orderBad.Count) { $orderBad -join '; ' } else { '16/16 页顺序正确' })

  Section '前端 Stage 0 · PRD v2.0 五处微创修正'

  $idxHtml = Get-Content (Join-Path $app 'index.html') -Raw
  T '#1 index.html 文案已改' ($idxHtml -match '进入工作区需要登录') ''

  $sExam = Get-Content (Join-Path $app 's-exam.html') -Raw
  $tPE = Get-Content (Join-Path $app 't-problem-edit.html') -Raw
  T '#2a s-exam data-page 已修正' ($sExam -match 'data-page="s-exam"') ''
  T '#2b t-problem-edit data-page 已修正' ($tPE -match 'data-page="t-problem-edit"') ''

  $aModels = Get-Content (Join-Path $app 'a-models.html') -Raw
  $aAudit = Get-Content (Join-Path $app 'a-audit.html') -Raw
  T '#3a a-models :key 用 recordId' ($aModels -match ':key="c\.recordId"') ''
  T '#3b a-audit :key 用 recordId' ($aAudit -match ':key="c\.recordId"') ''

  $wbHtml = Get-Content (Join-Path $app 's-workbench.html') -Raw
  $wbJs = Get-Content (Join-Path $app 's-workbench.js') -Raw
  T '#4a workbench is-focus 判定源为 focusCase' `
    (($wbHtml -notmatch "is-focus':focusLine") -and ($wbHtml -match "is-focus':focusCase")) ''
  T '#4b CASE_LINE / LINE_CASE 已删' (($wbJs -notmatch 'const CASE_LINE') -and ($wbJs -notmatch 'const LINE_CASE')) ''
  T '#4c REPLIES 已删' ($wbJs -notmatch 'const REPLIES') ''
  # 配额：Day2 的约束是「前端不许自己减配额」。Day8 起改由 SSE done.remainingQuota
  # 写服务端权威值 —— 所以不能继续一刀切禁 `this.quotaLeft = Math.max(...)`：
  # 第 4 步的 workspace-init / quota 刷新用的正是这句，那是**采纳服务端值**，不是自减。
  # 现在真正要守的是「任何写入都必须来自服务端下发的字段」：
  #   允许：left / remainingQuota / 无限态 '不限' / 归零兜底 0
  #   禁止：写死的数字（如 = 2）、--、-= 1 —— 那是假数据。
  $wbCode = ($wbJs -split "`n" | Where-Object { $_ -notmatch '^\s*(/\*|\*|//)' }) -join "`n"
  $quotaWrites = [regex]::Matches($wbCode, 'this\.quotaLeft\s*=\s*(?<rhs>[^;\r\n]+)')
  $quotaBad = @($quotaWrites | Where-Object {
      $rhs = $_.Groups['rhs'].Value.Trim()
      -not ($rhs -match '^(left|remainingQuota)$' -or $rhs -match '^Math\.max\(0,\s*(?:\w+\.)?(left|remainingQuota)\)$' -or $rhs -eq "'不限'" -or $rhs -eq '0')
    } | ForEach-Object { $_.Value.Trim() })
  T '#4d quotaLeft 只接受服务端权威值（无写死数字/自减）' ($quotaBad.Count -eq 0) `
    $(if ($quotaBad.Count) { "可疑写入: $($quotaBad -join ' | ')" } else { "$($quotaWrites.Count) 处写入全部来自服务端字段" })
  # submit() 里不得再出现 this.quotaLeft = 2（字段声明 quotaLeft: 2 是合法初值，不算）
  $submitBody = ''
  $sm = [regex]::Match($wbCode, 'submit\(\)\s*\{(?<body>[\s\S]*?)\n    \},')
  if ($sm.Success) { $submitBody = $sm.Groups['body'].Value }
  T '#4e submit() 不再重置配额' ($submitBody -notmatch 'quotaLeft') `
    $(if ($submitBody -match 'quotaLeft') { 'submit() 内仍有 quotaLeft 赋值' } else { 'submit() 内 0 处 quotaLeft 引用' })

  Section '前端 Stage 0 · shell.js 常量已迁出'
  $shellJs = Get-Content (Join-Path $app 'shell.js') -Raw
  $consts = @('PROBLEMS', 'SUBMISSIONS', 'ASSIGNMENTS', 'STUDENTS', 'MODELS', 'AI_CALLS', 'SANDBOX', 'PLAGIARISM')
  $leftover = @()
  foreach ($c in $consts) {
    if ($shellJs -match "var\s+$c\s*=\s*\[") { $leftover += $c }
  }
  T 'shell.js 无残留硬编码数组' ($leftover.Count -eq 0) `
    $(if ($leftover.Count) { "残留: $($leftover -join ', ')" } else { '8 组全部迁至 api/mock.js' })
  T 'shell.js 的 OJ getter 指向 OJMock.fixtures' ($shellJs -match 'OJMock\.fixtures') ''

  Section '前端 Stage 0 · JS 语法'
  $jsFiles = @('api/http.js', 'api/sse.js', 'api/poll.js', 'api/mock.js', 'api/auth.js',
               'boot.js', 'shell.js', 's-workbench.js',
               'adapter/status.js', 'adapter/difficulty.js', 'adapter/language.js',
               'adapter/time.js', 'adapter/unit.js', 'adapter/id.js', 'adapter/page.js', 'adapter/index.js')
  $syntaxBad = @()
  foreach ($j in $jsFiles) {
    $fp = Join-Path $app $j
    if (-not (Test-Path $fp)) { continue }
    $out = & node --check $fp 2>&1
    if ($LASTEXITCODE -ne 0) { $syntaxBad += "$j : $out" }
  }
  T "全部 JS 语法检查（$($jsFiles.Count) 个文件）" ($syntaxBad.Count -eq 0) `
    $(if ($syntaxBad.Count) { $syntaxBad -join ' | ' } else { 'node --check 全部通过' })

  Section '前端 Stage 0 · 视觉零回归与守卫（Playwright）'
  if ($SkipVisual) {
    Write-Host '  [SKIP] 已按 -SkipVisual 跳过截图比对' -ForegroundColor Yellow
  } else {
    Push-Location $root
    try {
      # 不解析探针的 stdout（中文 + 颜色在不同终端编码下会乱），改读它落的 report.json。
      $reportPath = Join-Path $app '_shots\_day2_verify\report.json'
      if (Test-Path $reportPath) { Remove-Item $reportPath -Force }
      & node scripts/_probe_shots.mjs --out _shots/_day2_verify 2>&1 | Out-Null

      if (Test-Path $reportPath) {
        $rep = Get-Content $reportPath -Raw | ConvertFrom-Json
        $same = ($rep | Where-Object { $_.identical }).Count
        $diffNames = @($rep | Where-Object { -not $_.identical } | ForEach-Object { $_.name })
        $errPages = @($rep | Where-Object { $_.errors -and $_.errors.Count -gt 0 } | ForEach-Object { $_.name })

        # Day2 时 index.html 有一处 PRD 微创文案修正，所以当时是「15 相同 + index 不同」。
        # 该修正早已随 Day2 提交入库，基线也已在第五步按干净 HEAD 重建 ——
        # 现在 16 页应该**全部**逐字节相同。继续要求「必须正好 1 页不同」等于
        # 要求页面必须跟基线不一致，是写反了的判据。
        T '16 页视觉零回归（逐字节相同 16/16）' `
          ($same -eq $rep.Count -and $diffNames.Count -eq 0) `
          "逐字节相同 $same / 不同 $($diffNames.Count) / 共 $($rep.Count)$(if ($diffNames.Count) { '（' + ($diffNames -join ',') + '）' })"

        T '16 页 0 个 console/pageerror/请求失败' ($errPages.Count -eq 0) `
          $(if ($errPages.Count) { "错误页: $($errPages -join ', ')" } else { '0 错误页' })
      } else {
        T '16 页视觉零回归（除 index 文案修正）' $false '探针未产出 report.json'
        T '16 页 0 个 console/pageerror/请求失败' $false '探针未产出 report.json'
      }

      $guardOut = & node scripts/_probe_guard.mjs 2>&1
      $guardText = ($guardOut | Out-String)
      $gm = [regex]::Match($guardText, '守卫验收：(\d+) 通过 / (\d+) 失败')
      if ($gm.Success) {
        T '登录守卫 14 项' ([int]$gm.Groups[2].Value -eq 0) `
          "$($gm.Groups[1].Value) 通过 / $($gm.Groups[2].Value) 失败"
      } else {
        T '登录守卫' $false '无法解析探针输出'
      }

      $wbOut = & node scripts/_probe_workbench.mjs 2>&1
      $wbText = ($wbOut | Out-String)
      $wm = [regex]::Match($wbText, '工作台验收：(\d+) 通过 / (\d+) 失败')
      if ($wm.Success) {
        T '工作台 8 项（两问两答 / 配额 / 联动）' ([int]$wm.Groups[2].Value -eq 0) `
          "$($wm.Groups[1].Value) 通过 / $($wm.Groups[2].Value) 失败"
      } else {
        T '工作台验收' $false '无法解析探针输出'
      }

      $sseOut = & node scripts/_probe_sse.mjs 2>&1
      $sseText = ($sseOut | Out-String)
      $sm2 = [regex]::Match($sseText, 'SSE 验收：(\d+) 通过 / (\d+) 失败')
      if ($sm2.Success) {
        T 'SSE 六事件全可触发（AC-2.1）' ([int]$sm2.Groups[2].Value -eq 0) `
          "$($sm2.Groups[1].Value) 通过 / $($sm2.Groups[2].Value) 失败"
      } else {
        T 'SSE 六事件验收' $false '无法解析探针输出'
      }

      $s0Out = & node scripts/_probe_stage0.mjs 2>&1
      $s0Text = ($s0Out | Out-String)
      $s0m = [regex]::Match($s0Text, '底座缺陷回归：(\d+) 通过 / (\d+) 失败')
      if ($s0m.Success) {
        T 'Stage 0 底座缺陷回归 18 项' ([int]$s0m.Groups[2].Value -eq 0) `
          "$($s0m.Groups[1].Value) 通过 / $($s0m.Groups[2].Value) 失败"
      } else {
        T 'Stage 0 底座缺陷回归' $false '无法解析探针输出'
      }

      $rmOut = & node scripts/_probe_realmode.mjs 2>&1
      $rmText = ($rmOut | Out-String)
      $rmm = [regex]::Match($rmText, '真实态健壮性：(\d+) 通过 / (\d+) 失败')
      if ($rmm.Success) {
        T '真实态（无 mock）15 页不白屏不报错' ([int]$rmm.Groups[2].Value -eq 0) `
          "$($rmm.Groups[1].Value) 通过 / $($rmm.Groups[2].Value) 失败"
      } else {
        T '真实态健壮性' $false '无法解析探针输出'
      }
    } finally { Pop-Location }
  }
}

# ══════════════════════════════════════════════════════════════════════
# 二、后端
# ══════════════════════════════════════════════════════════════════════
if (-not $SkipBackend) {
  Section '后端 · Maven 工程结构'

  $be = Join-Path $root 'backend'
  T '父 POM 存在' (Test-Path (Join-Path $be 'pom.xml')) ''
  $mods = @('oj-common-core', 'oj-common-api', 'oj-gateway', 'oj-teaching-service',
            'oj-problem-service', 'oj-judge-service', 'oj-ai-agent-service', 'oj-admin-service')
  $modMissing = @()
  foreach ($m in $mods) {
    if (-not (Test-Path (Join-Path $be "$m\pom.xml"))) { $modMissing += $m }
  }
  T '8 个模块 pom.xml 齐备' ($modMissing.Count -eq 0) `
    $(if ($modMissing.Count) { "缺: $($modMissing -join ', ')" } else { '8/8' })

  $jars = @()
  foreach ($m in $mods) {
    if ($m -in @('oj-common-core', 'oj-common-api')) { continue }
    $p = Join-Path $be "$m\target\$m-1.0.0-SNAPSHOT.jar"
    if (Test-Path $p) { $jars += $m }
  }
  T '6 个可启动 fat jar 已构建' ($jars.Count -eq 6) `
    $(if ($jars.Count -eq 6) { '6/6' } else { "已构建 $($jars.Count)/6（先跑 scripts/build-backend.ps1）" })

  Section '后端 · 容器与中间件'

  $containers = @('oj-cloud-mysql', 'oj-cloud-redis', 'oj-cloud-rabbitmq', 'oj-cloud-nacos', 'oj-cloud-go-judge')
  $up = @()
  foreach ($c in $containers) {
    $st = (docker inspect -f '{{.State.Status}}' $c 2>$null)
    if ($st -eq 'running') { $up += $c }
  }
  T '5 个基础容器全部 running' ($up.Count -eq 5) "$($up.Count)/5"

  Section '后端 · 6 服务端口与 Nacos 注册'

  $svc = @(
    @{ n = 'oj-gateway'; p = 8080 }, @{ n = 'oj-teaching-service'; p = 8081 },
    @{ n = 'oj-problem-service'; p = 8082 }, @{ n = 'oj-judge-service'; p = 8083 },
    @{ n = 'oj-ai-agent-service'; p = 8084 }, @{ n = 'oj-admin-service'; p = 8085 }
  )
  $listening = @()
  foreach ($s in $svc) {
    $ok = Test-NetConnection 127.0.0.1 -Port $s.p -InformationLevel Quiet -WarningAction SilentlyContinue
    if ($ok) { $listening += $s.n }
  }
  T '6 个服务端口全部监听' ($listening.Count -eq 6) "$($listening.Count)/6（先跑 scripts/run-services.ps1 -Action start）"

  try {
    $list = Invoke-RestMethod 'http://127.0.0.1:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=50' -TimeoutSec 10
    T 'Nacos 注册服务数 = 6' ([int]$list.count -eq 6) "count=$($list.count) [$($list.doms -join ', ')]"

    $healthyAll = $true
    $detail = @()
    foreach ($s in $svc) {
      try {
        $inst = Invoke-RestMethod "http://127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName=$($s.n)" -TimeoutSec 10
        $h = ($inst.hosts | Where-Object { $_.healthy } | Measure-Object).Count
        if ($h -lt 1) { $healthyAll = $false }
        $detail += "$($s.n)=$h"
      } catch { $healthyAll = $false; $detail += "$($s.n)=ERR" }
    }
    T '6 个服务 Nacos 实例均 healthy' $healthyAll ($detail -join ' ')
  } catch {
    T 'Nacos 注册服务数 = 6' $false "Nacos 不可达：$($_.Exception.Message)"
    T '6 个服务 Nacos 实例均 healthy' $false 'Nacos 不可达'
  }

  Section '后端 · 网关路由与鉴权'

  foreach ($s in @('teaching', 'problem', 'judge', 'ai', 'admin')) {
    try {
      $r = Invoke-RestMethod "http://127.0.0.1:8080/api/$s/health" -TimeoutSec 15
      T "网关 → /api/$s/health" ($r.code -eq 200 -and $r.success) "$($r.data)  traceId=$($r.traceId)"
    } catch {
      T "网关 → /api/$s/health" $false $_.Exception.Message
    }
  }

  # 无 token 访问受保护路径必须 401，且响应体同构
  $code = & curl.exe -sS -o "$env:TEMP\oj401.json" -w '%{http_code}' --max-time 15 `
    'http://127.0.0.1:8080/api/teaching/students' 2>$null
  $body = if (Test-Path "$env:TEMP\oj401.json") { Get-Content "$env:TEMP\oj401.json" -Raw } else { '' }
  $isEnvelope = $body -match '"code"\s*:\s*401' -and $body -match '"traceId"'
  T '无 token 访问受保护路径 → 401' ($code -eq '401') "HTTP $code"
  T '401 响应体与 Result 同构（含 code/traceId）' $isEnvelope $(if ($isEnvelope) { 'ok' } else { $body })
  Remove-Item "$env:TEMP\oj401.json" -ErrorAction SilentlyContinue

  $wl = & curl.exe -sS -o NUL -w '%{http_code}' --max-time 15 'http://127.0.0.1:8080/api/problem/health' 2>$null
  T '白名单 /api/*/health 免鉴权' ($wl -eq '200') "HTTP $wl"

  # X-Trace-Id 响应头
  $hdr = & curl.exe -sS -D - -o NUL --max-time 15 'http://127.0.0.1:8080/api/problem/health' 2>$null
  T '网关注入 X-Trace-Id 响应头' ($hdr -match 'X-Trace-Id:\s*[0-9a-f]+') ''
}

# ══════════════════════════════════════════════════════════════════════
# 汇总
# ══════════════════════════════════════════════════════════════════════
$total = $results.Count
$passed = ($results | Where-Object { $_.Ok }).Count
$failed = $total - $passed

# 中文字符在等宽字体里占 2 列，PadRight 按字符数算会错位，这里手工补空格
$line = "  结果：$passed/$total 通过"
$padLen = 62 - ($line.Length + ($line.ToCharArray() | Where-Object { [int]$_ -gt 127 }).Count)
if ($padLen -lt 0) { $padLen = 0 }

Write-Host ''
Write-Host '  ╔══════════════════════════════════════════════════════════╗' -ForegroundColor Cyan
Write-Host ('  ║' + $line + (' ' * $padLen) + '║') -ForegroundColor $(if ($failed -eq 0) { 'Green' } else { 'Red' })
Write-Host '  ╚══════════════════════════════════════════════════════════╝' -ForegroundColor Cyan

if ($failed -gt 0) {
  Write-Host ''
  Write-Host '  未通过项：' -ForegroundColor Red
  $results | Where-Object { -not $_.Ok } | ForEach-Object {
    Write-Host ("    - {0}" -f $_.Name) -ForegroundColor Red
    if ($_.Detail) { Write-Host ("      {0}" -f $_.Detail) -ForegroundColor DarkGray }
  }
}

Write-Host ''
exit $(if ($failed -eq 0) { 0 } else { 1 })
