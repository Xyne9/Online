# =====================================================================
#  _verify_problem_service.ps1 · 题库服务只读接口「真跑」验收
#
#  前提：临时实例已在 18082 端口起好（见 backend/_verify_problem_service_run.sh）。
#        本脚本只做 HTTP 断言，不负责起停进程。
#
#  用法：
#    pwsh -NoProfile -File scripts/_verify_problem_service.ps1
#
#  退出码：0 = 全部通过；非 0 = 失败项数。
# =====================================================================
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18082'
$fail = 0

function Get-Json([string]$path, [hashtable]$headers = @{}) {
    $resp = Invoke-WebRequest -Uri "$base$path" -Headers $headers -UseBasicParsing -TimeoutSec 20
    return ($resp.Content | ConvertFrom-Json)
}

function Get-Raw([string]$path, [hashtable]$headers = @{}) {
    return (Invoke-WebRequest -Uri "$base$path" -Headers $headers -UseBasicParsing -TimeoutSec 20).Content
}

# 期望失败（4xx）时取错误体；PowerShell 5/7 的异常体位置不同，两处都试
function Get-ErrorBody([string]$path, [hashtable]$headers = @{}) {
    try {
        $null = Get-Raw $path $headers
        return $null
    } catch {
        $body = $_.ErrorDetails.Message
        if (-not $body -and $_.Exception.Response) {
            $stream = $_.Exception.Response.GetResponseStream()
            $body = (New-Object System.IO.StreamReader($stream)).ReadToEnd()
        }
        if ($body) { return ($body | ConvertFrom-Json) }
        return $null
    }
}

function Assert([string]$name, [scriptblock]$test) {
    try {
        if (& $test) { Write-Host "  [PASS] $name" }
        else { Write-Host "  [FAIL] $name"; $script:fail++ }
    } catch {
        Write-Host "  [FAIL] $name -> $($_.Exception.Message)"; $script:fail++
    }
}

$stu  = @{ 'X-User-Id' = '1'; 'X-User-Role' = 'STUDENT' }
$tea  = @{ 'X-User-Id' = '2'; 'X-User-Role' = 'TEACHER' }

Write-Host "`n=== 1. 列表基础形态（未登录 = 学生视角）==="
$list = Get-Json '/api/problem/questions?page=1&pageSize=3'
Assert 'code=200'                    { $list.code -eq 200 }
Assert 'total=964'                   { $list.data.total -eq 964 }
Assert 'hasNext=true'                { $list.data.hasNext -eq $true }
Assert 'list 长度=3'                  { $list.data.list.Count -eq 3 }
Assert '全部 state=1'                 { ($list.data.list | Where-Object { $_.state -ne 1 }).Count -eq 0 }
Assert 'displayNo = "#"+id'          { $list.data.list[0].displayNo -eq "#$($list.data.list[0].id)" }
Assert 'difficultyLabel 非空'          { -not [string]::IsNullOrEmpty($list.data.list[0].difficultyLabel) }
Assert 'updatedAt 是 epoch 毫秒'        { $list.data.list[0].updatedAt -gt 1700000000000 }
Assert 'stats 五字段齐全'               { $null -ne $list.data.stats.total -and $null -ne $list.data.stats.solved -and $null -ne $list.data.stats.tried -and $null -ne $list.data.stats.acRate -and $null -ne $list.data.stats.weekNew }
Assert '未登录 myStatus=null'          { $null -eq $list.data.list[0].myStatus }
Assert '未登录 myScore=null'           { $null -eq $list.data.list[0].myScore }

Write-Host "`n=== 2. 学生强制 state=1 ==="
$forced = Get-Json '/api/problem/questions?state=0&pageSize=1'
Assert '学生传 state=0 仍返回已发布'      { $forced.data.list[0].state -eq 1 }
Assert 'total 仍为 964'                { $forced.data.total -eq 964 }

Write-Host "`n=== 3. 教师可见草稿 ==="
$draftList = Get-Json '/api/problem/questions?state=0&pageSize=2' $tea
Assert '教师 state=0 有数据'            { $draftList.data.total -eq 44 }
Assert '首行 state=0'                  { $draftList.data.list[0].state -eq 0 }

Write-Host "`n=== 4. 参数越界 → 400 ==="
Assert 'sortBy 非白名单 400'            { (Get-ErrorBody '/api/problem/questions?sortBy=hack').code -eq 400 }
Assert 'lvl=9 400'                     { (Get-ErrorBody '/api/problem/questions?lvl=1,9').code -eq 400 }
Assert 'lvl 非数字 400'                 { (Get-ErrorBody '/api/problem/questions?lvl=abc').code -eq 400 }
Assert 'status 非白名单 400'            { (Get-ErrorBody '/api/problem/questions?status=xxx').code -eq 400 }
Assert 'sortOrder 非 asc/desc 400'      { (Get-ErrorBody '/api/problem/questions?sortOrder=up').code -eq 400 }
# 学生视角 state 会被强制成 1，因此 state=9 对学生不构成非法参数（契约：学生传了也按 1 处理）；
# 只有教师/管理员的 state 才真正参与校验，故这条用教师身份验。
Assert '教师 state=9 400'                { (Get-ErrorBody '/api/problem/questions?state=9' $tea).code -eq 400 }
Assert '学生 state=9 被强制成 1'          { (Get-Json '/api/problem/questions?state=9&pageSize=1').data.list[0].state -eq 1 }
Assert 'kw 超 50 字 400'                { (Get-ErrorBody ('/api/problem/questions?kw=' + ('a' * 51))).code -eq 400 }

Write-Host "`n=== 5. 筛选与排序 ==="
$lvl = Get-Json '/api/problem/questions?lvl=1,2&pageSize=10'
Assert 'lvl 过滤生效'                   { ($lvl.data.list | Where-Object { $_.difficulty -gt 2 }).Count -eq 0 }
$done = Get-Json '/api/problem/questions?status=done&pageSize=10' $stu
# 断言不写死条数：judge_submission 是判题服务 Track 的测试数据，可能被并发增删。
# 这里只校验「done 筛出的每一行确实有 AC 提交」与「stats 与之一致」。
Assert 'status=done 每行都有 myStatus'     { ($done.data.list | Where-Object { $null -eq $_.myStatus }).Count -eq 0 }
Assert 'status=done 与 stats.solved 一致'  { $done.data.total -eq $done.data.stats.solved }
$todo = Get-Json '/api/problem/questions?status=todo&pageSize=5' $stu
Assert 'status=todo 无 myStatus'         { ($todo.data.list | Where-Object { $null -ne $_.myStatus }).Count -eq 0 }
$rate = Get-Json '/api/problem/questions?sortBy=rate&sortOrder=desc&pageSize=3'
Assert 'sortBy=rate 降序单调'             { $rate.data.list[0].acRate -ge $rate.data.list[1].acRate }
$upd = Get-Json '/api/problem/questions?sortBy=updatedAt&sortOrder=desc&pageSize=3'
Assert 'sortBy=updatedAt 降序单调'        { $upd.data.list[0].updatedAt -ge $upd.data.list[1].updatedAt }
$kw = Get-Json '/api/problem/questions?kw=%E6%9C%80%E5%A4%A7&pageSize=3'
Assert 'kw 搜索有结果'                   { $kw.data.total -gt 0 }
$tag = Get-Json '/api/problem/questions?tag=%E5%8A%A8%E6%80%81%E8%A7%84%E5%88%92&pageSize=3'
Assert 'tag 精确匹配 137 条'              { $tag.data.total -eq 137 }

Write-Host "`n=== 6. 分页边界 ==="
$p0 = Get-Json '/api/problem/questions?pageSize=0'
Assert 'pageSize=0 → 20'               { $p0.data.pageSize -eq 20 }
$big = Get-Json '/api/problem/questions?pageSize=500'
Assert 'pageSize=500 → 100'            { $big.data.pageSize -eq 100 }
$oob = Get-Json '/api/problem/questions?page=9999&pageSize=20'
Assert '越界页返回空列表'                 { $oob.data.list.Count -eq 0 -and $oob.data.hasNext -eq $false }
$pg1 = Get-Json '/api/problem/questions?page=1&pageSize=5&sortBy=id&sortOrder=asc'
$pg2 = Get-Json '/api/problem/questions?page=2&pageSize=5&sortBy=id&sortOrder=asc'
$ids1 = $pg1.data.list | ForEach-Object { $_.id }
$ids2 = $pg2.data.list | ForEach-Object { $_.id }
Assert '两页无重复'                      { ($ids1 | Where-Object { $ids2 -contains $_ }).Count -eq 0 }

Write-Host "`n=== 7. 「我的状态」来自 judge_submission ==="
$mine = Get-Json '/api/problem/questions?pageSize=100&sortBy=id&sortOrder=asc' $stu
$p1001 = $mine.data.list | Where-Object { $_.id -eq 1001 } | Select-Object -First 1
Assert '#1001 可见'                     { $null -ne $p1001 }
Assert '#1001 myStatus=3(AC)'           { $p1001.myStatus -eq 3 }
Assert '#1001 myStatusCode=ac'          { $p1001.myStatusCode -eq 'ac' }
Assert '#1001 myStatusFilter=done'      { $p1001.myStatusFilter -eq 'done' }
Assert '#1001 myScore=100'              { $p1001.myScore -eq 100 }
# stats 的口径校验：solved <= tried，且两者都等于真实提交数（不写死具体值）
Assert 'stats.solved <= stats.tried'     { $mine.data.stats.solved -le $mine.data.stats.tried }
Assert 'stats.solved >= 1（1001 已 AC）'  { $mine.data.stats.solved -ge 1 }

Write-Host "`n=== 8. 详情·作答视角（未登录）==="
$raw = Get-Raw '/api/problem/questions/1001'
$detail = $raw | ConvertFrom-Json
Assert 'code=200'                       { $detail.code -eq 200 }
Assert '无 officialSolution 字段'         { -not ($raw -like '*officialSolution*') }
Assert 'statement 非空'                  { -not [string]::IsNullOrEmpty($detail.data.statement) }
Assert 'inputSpec 抽出成功'               { -not [string]::IsNullOrEmpty($detail.data.inputSpec) }
Assert 'outputSpec 抽出成功'              { -not [string]::IsNullOrEmpty($detail.data.outputSpec) }
Assert 'samples 2 条'                    { $detail.data.samples.Count -eq 2 }
Assert 'chainPreview 10 条'              { $detail.data.chainPreview.Count -eq 10 }
Assert 'chainPreview 无 input'           { $null -eq $detail.data.chainPreview[0].PSObject.Properties['input'] }
Assert 'chainPreview 无 output'          { $null -eq $detail.data.chainPreview[0].PSObject.Properties['output'] }
Assert 'chainPreview 无 inputText'       { -not ($raw -like '*inputText*') -and -not ($raw -like '*input_text*') }
Assert 'scoringMode=OI'                 { $detail.data.scoringMode -eq 'OI' }
Assert 'timeLimitMs=1000'               { $detail.data.timeLimitMs -eq 1000 }
Assert 'memoryLimitMb=256'              { $detail.data.memoryLimitMb -eq 256 }
Assert 'languageMultipliers 3 条'        { $detail.data.languageMultipliers.Count -eq 3 }
Assert 'cpp 倍率=1'                      { ($detail.data.languageMultipliers | Where-Object { $_.language -eq 'cpp' }).multiplier -eq 1 }
Assert 'java 倍率=2'                     { ($detail.data.languageMultipliers | Where-Object { $_.language -eq 'java' }).multiplier -eq 2 }
Assert 'publicCaseCount=2'              { $detail.data.publicCaseCount -eq 2 }
Assert '未登录 myRecord.submitted=false' { $detail.data.myRecord.submitted -eq $false }

Write-Host "`n=== 9. 详情·我的记录（用户1）==="
$dm = Get-Json '/api/problem/questions/1001' $stu
Assert 'submitted=true'                 { $dm.data.myRecord.submitted -eq $true }
Assert 'bestScore=100'                  { $dm.data.myRecord.bestScore -eq 100 }
Assert 'bestSubmissionId=1'             { $dm.data.myRecord.bestSubmissionId -eq 1 }
Assert 'attempts=1'                     { $dm.data.myRecord.attempts -eq 1 }
Assert 'lastStatus=3'                   { $dm.data.myRecord.lastStatus -eq 3 }
Assert 'lastStatusCode=ac'              { $dm.data.myRecord.lastStatusCode -eq 'ac' }

Write-Host "`n=== 10. 详情·权限 ==="
$draftId = $draftList.data.list[0].id
Assert '学生看草稿题 403'                 { (Get-ErrorBody "/api/problem/questions/$draftId").code -eq 403 }
Assert '教师看草稿题 200'                 { (Get-Json "/api/problem/questions/$draftId" $tea).data.state -eq 0 }
Assert '不存在 404'                       { (Get-ErrorBody '/api/problem/questions/999999').code -eq 404 }
Assert '非法 ID 400'                      { (Get-ErrorBody '/api/problem/questions/abc').code -eq 400 }

Write-Host "`n=== 11. 内部接口 judge-config ==="
$jcRaw = Get-Raw '/inner/problem/judge-config/1001'
$jc = $jcRaw | ConvertFrom-Json
Assert 'problemId=1001'                 { $jc.data.problemId -eq 1001 }
Assert 'published=true'                 { $jc.data.published -eq $true }
Assert 'testCaseBriefs 10 条'            { $jc.data.testCaseBriefs.Count -eq 10 }
Assert 'brief 无 input/output'           { $null -eq $jc.data.testCaseBriefs[0].PSObject.Properties['input'] }
Assert 'brief 只含 id/seqNo/kind/score'   { (($jc.data.testCaseBriefs[0].PSObject.Properties.Name | Sort-Object) -join ',') -eq 'id,kind,score,seqNo' }
Assert 'languages 3 个'                  { $jc.data.languages.Count -eq 3 }
Assert 'scoringMode=OI'                 { $jc.data.scoringMode -eq 'OI' }
Assert 'timeLimitMs=1000'               { $jc.data.timeLimitMs -eq 1000 }
Assert '无 officialSolution 泄漏'         { -not ($jcRaw -like '*officialSolution*') }

Write-Host "`n=== 12. 内部接口 edit ==="
$ed = Get-Json '/inner/problem/edit/1001'
Assert 'totalScore=100'                 { $ed.data.totalScore -eq 100 }
Assert 'canPublish=true'                { $ed.data.canPublish -eq $true }
Assert 'publishHint=null'               { $null -eq $ed.data.publishHint }
Assert 'testCases 10 条'                 { $ed.data.testCases.Count -eq 10 }
Assert 'testCases 带正文'                 { -not [string]::IsNullOrEmpty($ed.data.testCases[0].input) }
Assert 'knowledgePoint=动态规划'          { $ed.data.knowledgePoint -eq '动态规划' }
Assert 'aiStages 非空'                   { $ed.data.aiStages.Count -gt 0 }
Assert 'version=1'                      { $ed.data.version -eq 1 }
Assert 'savedAt 是毫秒'                  { $ed.data.savedAt -gt 1700000000000 }

Write-Host "`n=== 13. 内部接口 test-cases ==="
$tc = Get-Json '/inner/problem/test-cases/1006'
Assert '1006 有 3 个测试点'               { $tc.data.Count -eq 3 }
Assert '第 1 条 kind=sample'              { $tc.data[0].kind -eq 'sample' }
Assert '第 1 条 isPublic=true'            { $tc.data[0].isPublic -eq $true }
Assert '第 3 条 isPublic=false'           { $tc.data[2].isPublic -eq $false }
Assert '第 3 条带隐藏正文'                 { -not [string]::IsNullOrEmpty($tc.data[2].input) }
$tc404 = Get-ErrorBody '/inner/problem/test-cases/999999'
Assert '不存在题目 404（不是空列表）'        { $tc404.code -eq 404 }

Write-Host "`n=== 14. Result 包装与 HTTP 同码 ==="
$resp = Invoke-WebRequest -Uri "$base/api/problem/questions" -UseBasicParsing
Assert 'HTTP 200 且 code=200'            { $resp.StatusCode -eq 200 -and ($resp.Content | ConvertFrom-Json).code -eq 200 }
try {
    $null = Invoke-WebRequest -Uri "$base/api/problem/questions?sortBy=hack" -UseBasicParsing
    Assert '400 时 HTTP 也是 400'          { $false }
} catch {
    $code = [int]$_.Exception.Response.StatusCode
    Assert '400 时 HTTP 也是 400'          { $code -eq 400 }
}
$traceId = ($resp.Content | ConvertFrom-Json).traceId
Assert 'traceId 非空（12 位）'             { -not [string]::IsNullOrEmpty($traceId) -and $traceId.Length -eq 12 }
Assert 'timestamp 是毫秒'                  { ($resp.Content | ConvertFrom-Json).timestamp -gt 1700000000000 }

Write-Host "`n================ 汇总 ================"
if ($fail -eq 0) {
    Write-Host "全部断言通过 ✅"
    exit 0
}
Write-Host "$fail 项断言失败 ❌"
exit $fail
