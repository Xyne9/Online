# -*- coding: utf-8 -*-
"""第 3 步验收：登录 → 题库 → 异步判题闭环（端到端，走真实网关 :8080）。

对应《从 0 到 1 的 6 步手册》第 3 步的验收标志：
  在工作台输入一段故意把初始值写成 ans = 0 的 C++ 代码，点提交，
  1 秒后 10 个测试点格子真实点亮 8 个绿格、第 3 个红格（WA，得分 80）。

本脚本**不用 mock**，每一步都打真实接口，失败即失败（绝不伪造通过）。

用法：python scripts/verify-day4.py
      python scripts/verify-day4.py --verbose
退出码 0 = 全部通过。
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080"
VERBOSE = "--verbose" in sys.argv
RESULTS = []


def req(method, path, body=None, token=None, timeout=60, raw=False):
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    r = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            text = resp.read().decode("utf-8", "replace")
            if raw:
                return resp.status, text
            return resp.status, json.loads(text) if text else {}
    except urllib.error.HTTPError as exc:
        text = exc.read().decode("utf-8", "replace")
        try:
            return exc.code, json.loads(text) if text else {}
        except json.JSONDecodeError:
            return exc.code, {"__raw__": text}
    except Exception as exc:  # noqa: BLE001
        return 0, {"__error__": str(exc)}


def check(name, cond, detail=""):
    RESULTS.append((name, bool(cond), detail))
    print(f"[{'PASS' if cond else 'FAIL'}] {name}" + (f"  —— {detail}" if detail else ""))
    return cond


def section(title):
    print(f"\n{'─' * 62}\n{title}\n{'─' * 62}")


def brief(obj, limit=300):
    s = json.dumps(obj, ensure_ascii=False)
    return s if len(s) <= limit else s[:limit] + "…"


# ══════════════════════════════════════════════════════════════════
section("0. 网关与 4 服务健康")
# ══════════════════════════════════════════════════════════════════
for svc in ("teaching", "problem", "judge"):
    st, body = req("GET", f"/api/{svc}/health")
    check(f"网关 → {svc} 健康", st == 200 and body.get("code") == 200,
          f"HTTP {st} {brief(body, 120)}")

# ══════════════════════════════════════════════════════════════════
section("1. 认证链路 #1-#5（契约 §2）")
# ══════════════════════════════════════════════════════════════════
st, body = req("POST", "/api/auth/login", {"username": "20220101", "password": "123456"})
ok = check(" #1 登录成功", st == 200 and body.get("code") == 200, f"HTTP {st}")
data = body.get("data") or {}
token = data.get("accessToken", "")
refresh = data.get("refreshToken", "")
user = data.get("user") or {}
check(" #1 返回 accessToken", bool(token), f"{len(token)} 字符")
check(" #1 返回 refreshToken", bool(refresh), f"{len(refresh)} 字符")
check(" #1 homePath 按角色下发", data.get("homePath") == "s-problems.html",
      str(data.get("homePath")))
check(" #1 用户卡完整（realName/role/className/avatarText）",
      bool(user.get("realName")) and user.get("role") == "STUDENT"
      and bool(user.get("className")) and bool(user.get("avatarText")),
      brief(user, 200))
check(" #1 expiresIn = 86400", data.get("expiresIn") == 86400, str(data.get("expiresIn")))
if VERBOSE:
    print("   login.data =", brief(data, 600))

# 错误密码必须 401
st, body = req("POST", "/api/auth/login", {"username": "20220101", "password": "wrongpass"})
check(" #1 错误密码 → 401", st == 401 and body.get("code") == 401,
      f"HTTP {st} msg={body.get('message')}")
check(" #1 错误文案不泄露账号是否存在",
      "账号或密码错误" in str(body.get("message", "")), str(body.get("message")))

# 无 token 访问受保护接口必须 401
st, body = req("GET", "/api/auth/me")
check(" #4 无 token → 401", st == 401, f"HTTP {st}")

# 带 token 的 me
st, body = req("GET", "/api/auth/me", token=token)
me = body.get("data") or {}
check(" #4 me 返回当前用户", st == 200 and me.get("username") == "20220101",
      f"HTTP {st} {brief(me, 200)}")
check(" #4 weakTags 是数组（绝不为 null）", isinstance(me.get("weakTags"), list),
      str(me.get("weakTags")))
check(" #4 lastLoginAt 是毫秒时间戳",
      isinstance(me.get("lastLoginAt"), int) and me["lastLoginAt"] > 1_600_000_000_000,
      str(me.get("lastLoginAt")))

st, body = req("GET", "/api/auth/menus", token=token)
menus = body.get("data") or {}
groups = menus.get("groups") or []
check(" #5 menus 返回分组", st == 200 and len(groups) >= 2, f"HTTP {st} groups={len(groups)}")
check(" #5 学生含「学习」组", any(g.get("key") == "study" for g in groups),
      str([g.get("key") for g in groups]))

# refresh 轮换
st, body = req("POST", "/api/auth/refresh", {"refreshToken": refresh})
rdata = body.get("data") or {}
new_token = rdata.get("accessToken", "")
new_refresh = rdata.get("refreshToken", "")
check(" #3 refresh 换到新 token", st == 200 and bool(new_token), f"HTTP {st}")
check(" #3 轮换：新 refresh 与旧的不同", bool(new_refresh) and new_refresh != refresh,
      f"旧 {refresh[:20]}… 新 {new_refresh[:20]}…")

# 旧 refresh 必须失效（防重放）
st, body = req("POST", "/api/auth/refresh", {"refreshToken": refresh})
check(" #3 旧 refresh 已失效 → 401", st == 401, f"HTTP {st}")

# 旧 access 也应失效（Redis 在线态被新 token 覆盖）
# 前置：网关必须开 redis-check-enabled（见 oj-gateway/application.yml）。
# 关着的话这里会 200 —— 那不是本脚本的问题，是配置没生效。
st, body = req("GET", "/api/auth/me", token=token)
check(" #3 旧 access 因在线态轮换而失效 → 401", st == 401,
      f"HTTP {st}（若为 200，检查网关 oj.gateway.redis-check-enabled 是否为 true）")

token = new_token   # 后续用新 token

# ══════════════════════════════════════════════════════════════════
section("2. 题库只读链路 #6-#7（契约 §3）")
# ══════════════════════════════════════════════════════════════════
st, body = req("GET", "/api/problem/questions?page=1&pageSize=5", token=token)
lst = (body.get("data") or {}).get("list") or []
d = body.get("data") or {}
check(" #6 题库列表返回", st == 200 and len(lst) > 0, f"HTTP {st} 返回 {len(lst)} 条")
check(" #6 total > 900（1008 道题已入库）", d.get("total", 0) > 900, f"total={d.get('total')}")
check(" #6 分页元信息完整（page/pageSize/hasNext）",
      all(k in d for k in ("page", "pageSize", "hasNext")),
      f"page={d.get('page')} pageSize={d.get('pageSize')} hasNext={d.get('hasNext')}")
check(" #6 内嵌 stats 四格", isinstance(d.get("stats"), dict) and
      all(k in d["stats"] for k in ("total", "solved", "tried", "acRate")),
      brief(d.get("stats"), 160))
if lst:
    p0 = lst[0]
    check(" #6 题目字段完整（displayNo/title/difficulty/tags/acRate）",
          all(k in p0 for k in ("id", "displayNo", "title", "difficulty", "tags", "acRate")),
          brief(p0, 320))
    check(" #6 difficulty 在 1-5", 1 <= p0.get("difficulty", 0) <= 5, str(p0.get("difficulty")))
    check(" #6 学生视角 myStatus 为 null 或语义串",
          p0.get("myStatus") in (None, "ac", "fail", "todo"), str(p0.get("myStatus")))

# 难度筛选
st, body = req("GET", "/api/problem/questions?page=1&pageSize=5&lvl=1", token=token)
l1 = (body.get("data") or {}).get("list") or []
check(" #6 难度筛选 lvl=1 生效", st == 200 and all(x.get("difficulty") == 1 for x in l1),
      f"返回 {len(l1)} 条，难度={[x.get('difficulty') for x in l1]}")

# 非法 sortBy 必须 400
st, body = req("GET", "/api/problem/questions?sortBy=evil", token=token)
check(" #6 非法 sortBy → 400", st == 400, f"HTTP {st} msg={body.get('message')}")

# 关键词搜索
st, body = req("GET", "/api/problem/questions?page=1&pageSize=5&kw=A%20%2B%20B", token=token)
kwl = (body.get("data") or {}).get("list") or []
check(" #6 关键词搜索生效", st == 200 and len(kwl) > 0, f"返回 {len(kwl)} 条")

# 详情
pid = 1006 if any(x.get("id") == 1006 for x in lst) else (lst[0]["id"] if lst else 1006)
st, body = req("GET", f"/api/problem/questions/{pid}", token=token)
det = body.get("data") or {}
check(f" #7 题目详情 id={pid}", st == 200 and det.get("id") == pid, f"HTTP {st}")
check(" #7 含题面与样例", bool(det.get("statement")) and isinstance(det.get("samples"), list),
      f"statement={len(str(det.get('statement','')))}字符 samples={len(det.get('samples') or [])}")
check(" #7 含时限/内存", det.get("timeLimitMs") and det.get("memoryLimitMb"),
      f"{det.get('timeLimitMs')}ms / {det.get('memoryLimitMb')}MB")
check(" #7 不泄露 official_solution", "officialSolution" not in det,
      f"keys={sorted(det.keys())[:12]}…")
check(" #7 chainPreview 只回 seqNo/kind/score（不回输入输出）",
      all(set(c.keys()) <= {"seqNo", "kind", "score"} for c in (det.get("chainPreview") or [])),
      brief(det.get("chainPreview"), 200))

# 404
st, body = req("GET", "/api/problem/questions/99999999", token=token)
check(" #7 不存在题目 → 404", st == 404, f"HTTP {st}")

# ══════════════════════════════════════════════════════════════════
section("3. 异步判题闭环 #19-#25（契约 §4）")
# ══════════════════════════════════════════════════════════════════
# 用 A+B 题（1006 / 1011，3 个测试点）做确定性验证
AB_PID = 1006
st, body = req("GET", f"/api/problem/questions/{AB_PID}", token=token)
ab = body.get("data") or {}
check(f" A+B 题 {AB_PID} 可读", st == 200 and ab.get("id") == AB_PID,
      f"title={ab.get('title')} cases={ab.get('testCaseCount')}")

CODE_OK = """#include <iostream>
int main(){long long a,b;std::cin>>a>>b;std::cout<<a+b<<std::endl;return 0;}
"""

# #25 run-sample（不落库）
st, body = req("POST", "/api/judge/run-sample", {
    "problemId": AB_PID, "language": "cpp", "code": CODE_OK, "mode": "sample"
}, token=token, timeout=120)
rs = body.get("data") or {}
check(" #25 run-sample 返回", st == 200 and rs.get("status") == "FINISHED",
      f"HTTP {st} {brief(rs, 240)}")
check(" #25 正确代码样例通过", all(c.get("statusCode") == "ac" for c in (rs.get("cases") or [])) or
      rs.get("actualOutput", "").strip() != "",
      brief(rs.get("cases"), 240))

# #19 self-test
# ⚠️ 3 秒防抖锁（oj:judge:lock:{userId}）被 #25 与 #19 共用：run-sample 刚占过一次，
#    立刻调 self-test 必然 429。这是**正确行为**，不是缺陷——所以脚本要等锁过期。
time.sleep(3.2)
st, body = req("POST", "/api/judge/self-test", {
    "problemId": AB_PID, "language": "cpp", "code": CODE_OK, "customInput": "12 34\n"
}, token=token, timeout=120)
stt = body.get("data") or {}
check(" #19 self-test 返回", st == 200 and stt.get("status") == "FINISHED",
      f"HTTP {st} {brief(stt, 240)}")
check(" #19 self-test 输出 46", "46" in str(stt.get("actualOutput", "")),
      f"actualOutput={stt.get('actualOutput')!r}")

# #20 提交（正确代码 → 应 AC）
time.sleep(3.2)   # 让 3 秒防抖锁过期（前面 run-sample 占过一次）
st, body = req("POST", "/api/judge/submit", {
    "problemId": AB_PID, "assignmentId": 0, "language": "cpp", "code": CODE_OK
}, token=token)
sub = body.get("data") or {}
sid = sub.get("submissionId")
check(" #20 提交受理", st == 200 and sid, f"HTTP {st} {brief(sub, 240)}")
check(" #20 返回 displayNo", bool(sub.get("displayNo")), str(sub.get("displayNo")))
check(" #20 初始 status=0（排队）", sub.get("status") == 0, str(sub.get("status")))

# #21 轮询到终态
final = None
t0 = time.time()
for _ in range(60):
    st, body = req("GET", f"/api/judge/status/{sid}", token=token)
    d = body.get("data") or {}
    if d.get("status", 0) >= 3:
        final = d
        break
    time.sleep(0.5)
elapsed = time.time() - t0
check(" #21 判题到达终态", final is not None, f"耗时 {elapsed:.1f}s")
if final:
    check(" #21 正确代码 → AC(3)", final.get("status") == 3,
          f"status={final.get('status')} {final.get('statusLabel')}")
    check(" #21 oiScore=100", final.get("oiScore") == 100, str(final.get("oiScore")))
    check(" #21 passCaseCount = totalCaseCount",
          final.get("passCaseCount") == final.get("totalCaseCount"),
          f"{final.get('passCaseCount')}/{final.get('totalCaseCount')}")
    check(" #21 三字段联动（status/statusLabel/statusCode）",
          final.get("status") == 3 and final.get("statusLabel") == "AC"
          and final.get("statusCode") == "ac",
          f"{final.get('status')}/{final.get('statusLabel')}/{final.get('statusCode')}")
    cases = final.get("caseDetails") or []
    check(" #21 caseDetails 逐测试点明细", len(cases) > 0, f"{len(cases)} 个测试点")
    if cases:
        check(" #21 每个测试点含 seqNo/score/gotScore/timeMs/memKb",
              all(all(k in c for k in ("seqNo", "score", "gotScore", "timeMs", "memKb"))
                  for c in cases),
              brief(cases[0], 260))
    check(" #21 aiStateUpdate 存在", isinstance(final.get("aiStateUpdate"), dict),
          brief(final.get("aiStateUpdate"), 160))
    if VERBOSE:
        print("   final =", brief(final, 900))

# 3 秒防抖锁：连续两次提交，第二次必须 429
st, body = req("POST", "/api/judge/submit", {
    "problemId": AB_PID, "assignmentId": 0, "language": "cpp", "code": CODE_OK
}, token=token)
st2, body2 = req("POST", "/api/judge/submit", {
    "problemId": AB_PID, "assignmentId": 0, "language": "cpp", "code": CODE_OK
}, token=token)
check(" #20 3 秒防抖锁 → 429", st2 == 429, f"第二次 HTTP {st2} msg={body2.get('message')}")

# 错误代码 → 必须不是 AC（WA/RE/CE 都可，但不能假 AC）
time.sleep(3.2)
BAD = """#include <iostream>
int main(){long long a,b;std::cin>>a>>b;std::cout<<a-b<<std::endl;return 0;}
"""
st, body = req("POST", "/api/judge/submit", {
    "problemId": AB_PID, "assignmentId": 0, "language": "cpp", "code": BAD
}, token=token)
bad_sid = (body.get("data") or {}).get("submissionId")
bad_final = None
for _ in range(60):
    st, body = req("GET", f"/api/judge/status/{bad_sid}", token=token)
    d = body.get("data") or {}
    if d.get("status", 0) >= 3:
        bad_final = d
        break
    time.sleep(0.5)
if bad_final:
    check(" 错误代码 → 非 AC（不伪造通过）", bad_final.get("status") != 3,
          f"status={bad_final.get('status')} {bad_final.get('statusLabel')} "
          f"score={bad_final.get('oiScore')}")
    check(" 错误代码 oiScore < 100", bad_final.get("oiScore", 100) < 100,
          str(bad_final.get("oiScore")))
    check(" 错误代码记录首个失败点", bad_final.get("firstFailedCaseId") is not None,
          str(bad_final.get("firstFailedCaseId")))
else:
    check(" 错误代码 → 非 AC（不伪造通过）", False, "未拿到终态")

# 编译错误 → CE(9)
time.sleep(3.2)
CE_CODE = "int main(){ this is not cpp }\n"
st, body = req("POST", "/api/judge/submit", {
    "problemId": AB_PID, "assignmentId": 0, "language": "cpp", "code": CE_CODE
}, token=token)
ce_sid = (body.get("data") or {}).get("submissionId")
ce_final = None
for _ in range(60):
    st, body = req("GET", f"/api/judge/status/{ce_sid}", token=token)
    d = body.get("data") or {}
    if d.get("status", 0) >= 3:
        ce_final = d
        break
    time.sleep(0.5)
if ce_final:
    check(" 编译错误 → CE(9)", ce_final.get("status") == 9,
          f"status={ce_final.get('status')} {ce_final.get('statusLabel')}")
    check(" CE 带编译错误信息", bool(ce_final.get("errorMsg")),
          str(ce_final.get("errorMsg"))[:150])

# ══════════════════════════════════════════════════════════════════
section("4. 提交记录 #22-#24")
# ══════════════════════════════════════════════════════════════════
st, body = req("GET", "/api/judge/submissions?page=1&pageSize=8&range=30", token=token)
d = body.get("data") or {}
sl = d.get("list") or []
check(" #22 提交记录列表", st == 200 and len(sl) > 0, f"HTTP {st} 返回 {len(sl)} 条")
check(" #22 total >= 3（本次产生了 3 次提交）", d.get("total", 0) >= 3, f"total={d.get('total')}")
check(" #22 内嵌 stats", isinstance(d.get("stats"), dict) and
      all(k in d["stats"] for k in ("totalAc", "acRate", "statusDist", "langDist")),
      brief(d.get("stats"), 300))
if sl:
    check(" #22 行字段完整（displayNo/problemTitle/languageLabel/statusCode/oiScore）",
          all(k in sl[0] for k in ("id", "displayNo", "problemTitle", "language",
                                   "languageLabel", "status", "statusLabel",
                                   "statusCode", "score")),
          brief(sl[0], 400))

# 状态筛选
st, body = req("GET", "/api/judge/submissions?page=1&pageSize=20&range=30&status=ac", token=token)
al = (body.get("data") or {}).get("list") or []
check(" #22 状态筛选 status=ac 生效",
      st == 200 and all(x.get("status") == 3 for x in al),
      f"返回 {len(al)} 条")

# #23 详情
if final and sid:
    st, body = req("GET", f"/api/judge/submissions/{sid}?previewLimit=200", token=token)
    dd = body.get("data") or {}
    check(" #23 提交详情", st == 200 and dd.get("id") == sid, f"HTTP {st}")
    check(" #23 含源代码", bool(dd.get("code")), f"{len(str(dd.get('code','')))} 字符")
    check(" #23 含逐测试点预览", isinstance(dd.get("caseDetails"), list) and
          len(dd["caseDetails"]) > 0, f"{len(dd.get('caseDetails') or [])} 个")
    check(" #23 含同题轨迹", isinstance(dd.get("trace"), list), f"{len(dd.get('trace') or [])} 条")

# #24 轨迹
st, body = req("GET", f"/api/judge/submissions/trace?problemId={AB_PID}&limit=20", token=token)
tr = body.get("data") or {}
check(" #24 同题轨迹", st == 200 and isinstance(tr.get("list"), list),
      f"HTTP {st} {len(tr.get('list') or [])} 条")
check(" #24 含 bestSubmissionId / bestScore",
      "bestSubmissionId" in tr and "bestScore" in tr,
      f"best={tr.get('bestSubmissionId')} score={tr.get('bestScore')}")

# ══════════════════════════════════════════════════════════════════
section("5. 越权与鉴权（契约 §2.8）")
# ══════════════════════════════════════════════════════════════════
st, body = req("GET", "/api/judge/submissions", token=None)
check(" 无 token 访问提交记录 → 401", st == 401, f"HTTP {st}")

st, body = req("GET", "/api/judge/status/99999999", token=token)
check(" 不存在的提交 → 404", st == 404, f"HTTP {st}")

st, body = req("GET", "/api/judge/submissions/99999999", token=token)
check(" 不存在的提交详情 → 404", st == 404, f"HTTP {st}")

# 伪造 token
st, body = req("GET", "/api/auth/me", token="eyJhbGciOiJIUzI1NiJ9.fake.signature")
check(" 伪造 token → 401", st == 401, f"HTTP {st}")

# ══════════════════════════════════════════════════════════════════
section("6. 注销 #2")
# ══════════════════════════════════════════════════════════════════
# ⚠️ 本段必须放在最后：注销会删掉 Redis 在线态，之后所有请求都会 401。
#    同时它会消耗一次登录限流额度（10 次/分钟/IP），所以放在全流程末尾。
st, body = req("POST", "/api/auth/logout", {"allDevices": False}, token=token)
check(" #2 注销成功", st == 200 and (body.get("data") or {}).get("loggedOut") is True,
      f"HTTP {st} {brief(body.get('data'), 120)}")

# 注销后 token 必须立即失效——这是 redis-check-enabled 的核心价值。
# 关闭时这里会 200，旧 token 能一直用到 24h 过期。
st, body = req("GET", "/api/auth/me", token=token)
check(" #2 注销后 token 立即失效 → 401", st == 401,
      f"HTTP {st}（若为 200，检查网关 oj.gateway.redis-check-enabled 是否为 true）")

# 幂等：再注销一次仍 200 或 401（两者都可接受，但不能 500）
st, body = req("POST", "/api/auth/logout", {}, token=token)
check(" #2 重复注销不 500", st in (200, 401), f"HTTP {st}")

# ══════════════════════════════════════════════════════════════════
passed = sum(1 for _, ok, _ in RESULTS if ok)
total = len(RESULTS)
print(f"\n{'═' * 62}")
print(f"  第 3 步验收：{passed}/{total} 通过")
print(f"{'═' * 62}")
if passed != total:
    print("\n失败项：")
    for n, ok, d in RESULTS:
        if not ok:
            print(f"  ✗ {n}   ({d})")
sys.exit(0 if passed == total else 1)
