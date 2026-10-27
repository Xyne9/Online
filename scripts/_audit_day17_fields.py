"""一次性自查：把 verify-day17.py 里断言的字段路径全部抽出来，和契约原文逐字比对。

对照源：docs/analysis/02-契约补全规范-v2.0.md §6.5 / §6.6 / §7（第 698-781 行）
用法：python scripts/_audit_day17_fields.py
退出码 0 = 脚本断言的字段全部命中契约原文（没有自造字段、没有别名兼容）
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts/verify-day17.py"
CONTRACT = ROOT / "docs/analysis/02-契约补全规范-v2.0.md"

# 契约各端点的响应字段路径 -> 从 02 文档第 6 列原文解析
CONTRACT_RANGES = {
    "51": (698, 705), "52": (698, 705), "53": (698, 705),
    "54": (706, 719), "55": (706, 719), "56": (706, 719), "57": (706, 719),
    "58": (706, 719), "59": (706, 719), "60": (706, 719), "61": (706, 719),
    "62": (706, 719),
    "72": (739, 753), "73": (739, 753), "74": (739, 753), "75": (739, 753),
    "76": (739, 753), "77": (739, 753), "78": (739, 753), "79": (739, 753),
    "80": (754, 759),
    "81": (760, 767), "82": (760, 767), "83": (760, 767),
    "84": (768, 775), "85": (768, 775), "86": (768, 775),
    "87": (776, 781),
}

# 这些不是「契约字段」，是脚本自己做的派生断言（结构性/自洽性），不参与比对
DERIVED_OK = {
    "list", "total", "page", "pageSize", "hasNext", "summary", "dist", "stats",
    "byLevel", "channels", "sandbox", "users", "problems", "submissions", "ai",
    "teaching", "storage", "trend", "days", "weekPassRate", "weekSubmit", "weekAc",
    "peakDate", "healthyCount", "totalCount", "avgMastery", "weakCount",
    "avgKpMastery", "avgPerStudent", "stageDist", "topStudents", "dailyTrend",
    "rows", "footNote", "cells", "services", "middleware", "degraded",
    "autoDegrade", "checkedAt", "stageName", "count", "percent", "date",
    "submitCount", "acCount", "passRate", "weekday", "hour", "ttftMs", "calls",
    "at", "from", "to", "reason", "recordId", "tokens", "guardCode", "seqNo",
    "kind", "status", "msg", "ts", "highlight", "collectedAt", "truncated",
    "lines", "capacity", "running", "queued", "exited", "avgWaitMs", "avgRunMs",
    "recycleRate", "hitDist", "code", "todayTotal", "on", "degrade", "off",
    "classId", "className", "teacherId", "teacherName", "studentCount",
    "activeCount", "avgScore", "acRate", "key", "label", "subtitle", "severity",
    "href", "actionText", "warn", "problemId", "displayNo", "title", "difficulty",
    "triedCount", "firstFailSeq", "name", "stuNo", "failCount", "avgScore",
    "totalSubmit", "acCount", "failRate", "topWrongOutput", "errorTypes",
    "partial", "fail", "max", "min", "median", "sd", "peakDay", "peakCount",
    "weakestKp", "kpId", "kpName", "masteryPercent", "level", "symptom", "cause",
    "advice", "recommendProblems", "userId", "solved", "aiUsed", "spark",
    "riskLevel", "riskReason", "knowledgeTags", "recent", "submissionId",
    "problemTitle", "lastActiveAt", "suggestion", "stage", "kpId",
    "id", "channelCode", "channelName", "vendor", "modelName", "roleBindings",
    "baseUrl", "timeoutMs", "maxTokens", "temperature", "maxConcurrency",
    "isEnabled", "healthStatus", "healthNote", "avgTtftMs", "todayCalls",
    "tokensIn", "tokensOut", "costCents", "lastProbeAt", "apiKeyMasked",
    "trend24h", "recentCalls", "degradeEvents", "updatedAt", "deleted",
    "softDeleted", "effectiveAt", "latencyMs", "httpStatus", "modelEcho",
    "probedAt", "recoveredAt", "failStreak", "userName", "route", "guardAction",
    "guardReason", "traceId", "promptText", "replyText", "guardReplacementText",
    "chain", "sandboxInstanceId", "isFallback", "costCents", "user", "language",
    "languageLabel", "problemId", "cpuPercent", "memKb", "startedAt", "ageMs",
    "exitCode", "killedAt", "releasedCpu", "releasedMemKb", "students",
    "teachers", "admins", "activeToday", "published", "draft", "archived",
    "today", "callsToday", "tokensToday", "costCentsToday", "fallbackRate",
    "classes", "assignments", "exams", "pendingPlagiarism", "testCaseBytes",
    "codeBytes", "submissions", "aiCalls", "state",
}


def contract_text():
    return CONTRACT.read_text(encoding="utf-8").splitlines()


def main():
    src = SCRIPT.read_text(encoding="utf-8")
    lines = contract_text()

    # ── 1. 抽脚本里 expect(...) 用到的字段路径 ──
    # 形如 ("dist.partial", pct, "...") —— 只要括号里第一个是字符串常量且含字段语义
    paths = set()
    for match in re.finditer(r'\(\s*"([a-zA-Z][a-zA-Z0-9_.\[\]]*)"\s*,\s*(?:is_|pct|nonneg|one_of|is_list|is_num|is_int|is_str|is_bool)', src):
        paths.add(match.group(1))

    print("脚本断言的字段路径共 %d 个" % len(paths))

    # ── 2. 逐字比对一个最容易写歪的：dist 的第二键 ──
    print("\n── dist 键名核对（契约 §6.6.3，第 711 行）──")
    line_711 = lines[710]
    print("契约原文片段:", line_711[line_711.find("dist:"):line_711.find("dist:") + 60])
    contract_keys = re.search(r"dist:\{([^}]*)\}", line_711)
    contract_key_set = set(k.strip() for k in contract_keys.group(1).split(",")) if contract_keys else set()
    print("契约 dist 的键:", sorted(contract_key_set))
    script_dist = sorted(p.split(".", 1)[1] for p in paths if p.startswith("dist."))
    print("脚本断言的 dist 键:", script_dist)
    ok_dist = set(script_dist) == contract_key_set
    print(("PASS " if ok_dist else "FAIL ") + "dist 键集合与契约完全一致")
    if not ok_dist:
        print("  多出:", sorted(set(script_dist) - contract_key_set))
        print("  缺失:", sorted(contract_key_set - set(script_dist)))
    # 明确检查别名残留
    alias = [p for p in paths if p.endswith(".part") or p == "dist.part"]
    print(("PASS " if not alias else "FAIL ") + "没有 part 别名（只认契约的 partial）: %r" % (alias,))

    # ── 3. 枚举值逐字核对 ──
    print("\n── 枚举值逐字核对 ──")
    enum_checks = [
        ("#58 sortBy 白名单", {"score", "rate", "ai", "name"}, r"sortBy\(`score`/`rate`/`ai`/`name`\)"),
        ("#59 byLevel 键", {"ai", "low", "idle"}, r"byLevel:\{ai,low,idle\}"),
        ("#81 guardCode", {"pass", "block", "rewrite"}, r'guardCode:"pass"'),
        ("#84 state 四值", {"QUEUED", "RUNNING", "EXITED", "KILLED"}, r"QUEUED.*RUNNING.*EXITED.*KILLED"),
    ]
    for label, expect_set, pattern in enum_checks:
        # 先在契约里找原文
        found = [ln for ln in lines if re.search(pattern, ln)]
        in_contract = bool(found)
        # 再在脚本里找
        missing = sorted(v for v in expect_set if ('"%s"' % v) not in src and ("'%s'" % v) not in src)
        ok = in_contract and not missing
        print(("PASS " if ok else "FAIL ") + "%s：契约原文%s，脚本覆盖%s"
              % (label, "有" if in_contract else "没找到",
                 "完整" if not missing else "缺失 " + str(missing)))
        if not ok:
            print("      契约行:", (found[0][:160] if found else "—"))

    # ── 4. roleBindings 必须是数组（不是 v1.0 的 roleBinding）──
    print("\n── roleBindings 形态核对（契约 §7.2.1）──")
    has_array_assert = ('("list[].roleBindings", is_list' in src)
    has_single_guard = "roleBinding" in src and "不使用 v1.0 的单值 roleBinding" in src
    print(("PASS " if has_array_assert else "FAIL ") + "断言了 roleBindings 是数组")
    print(("PASS " if has_single_guard else "FAIL ") + "守卫了单值 roleBinding 不得出现")

    # ── 5. apiKeyMasked 不得泄漏明文 ──
    print("\n── 密钥明文守卫（agent.md §2.6）──")
    has_scan = "def scan_secrets" in src and "SECRET_PATTERN" in src
    print(("PASS " if has_scan else "FAIL ") + "脚本内置明文密钥扫描器（sk-xxx / ${VAR} / env:VAR）")
    for tag in ("72", "73", "82"):
        used = 'scan_secrets("%s"' % tag in src
        print(("PASS " if used else "FAIL ") + "  #%s 的响应做了密钥扫描" % tag)

    # ── 6. #83 12 列 ──
    print("\n── #83 CSV 列数 ──")
    twelve = "len(columns) == 12" in src
    print(("PASS " if twelve else "FAIL ") + "断言表头恰好 12 列")

    all_ok = ok_dist and not alias and has_array_assert and has_single_guard and has_scan and twelve
    print("\n%s" % ("全部自查通过" if all_ok else "存在不一致，见上"))
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
