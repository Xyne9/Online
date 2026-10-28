# -*- coding: utf-8 -*-
"""第 6 步后端验收：学情分析 #51~#62 + 平台运维 #72~#87（共 28 端点，走真实网关 :8080）。

端点计数口径（容易数错，写清楚）：
  · 本脚本负责 **28 个**：#51~#62（学情 12）+ #72~#87（管理 16）
  · #63~#71（查重 9 个）属**第 5 步**，由 `scripts/verify-day12.py` 覆盖
  · 合起来正好是契约 §12 的 #51~#87 = 37 行
  · 脚本末尾 E.3 会静态核对 verify-day12.py 里真的调过那 9 个路径，凑齐 37

契约来源：docs/analysis/02-契约补全规范-v2.0.md
  §6.5 班级与待办簇     #51~#53   （第 698-705 行）
  §6.6 学情分析簇       #54~#62   （第 706-719 行）
  §7.2 模型通道簇       #72~#79   （第 739-753 行）
  §7.3 健康聚合         #80       （第 754-759 行）
  §7.4 AI 调用审计      #81~#83   （第 760-767 行）
  §7.5 沙箱运维         #84~#86   （第 768-775 行）
  §7.6 全站统计         #87       （第 776-781 行）
  §12  端点总清单       #51~#87   （第 1196-1232 行）

每个端点至少断言四件事：
  ① HTTP 状态码 + Result 信封（code==200 且与状态码同值、success==true、traceId 非空）
  ② 契约里**点名**的关键字段存在且类型正确（含 #56 的 dist.*、#57 的 masteryPercent 0~100、
     #60 的 rows[].cells[].passRate、#72 的 summary.*、#84 的 stats.*）
  ③ 权限：学生 token / 教师 token 访问 /api/admin/** 必须 403（不是 401、不是 500）
  ④ 越权 + 参数校验：学生 A 看学生 B 的 #62 必须 403；非法参数不许 500

破坏性端点（#74 新增 / #75 更新 / #76 删除 / #86 终止沙箱）自己造测试数据，跑完自己清理。
测试数据一律带 `verify-` 前缀，`fixtures` 落到 report.json，便于人工复核与二次清理。

⚠️ 验收套件必须串行跑（agent.md §0.1 事实 #7）：同账号重复登录会顶掉旧 token，
   另有 oj:rate:login:{ip} 1 分钟窗口（约 12 次）。撞 429 用
   `docker exec oj-cloud-redis redis-cli DEL oj:rate:login:127.0.0.1`。

用法：
    python scripts/verify-day17.py
退出码 0 = 全部通过。
"""
import json
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

BASE = "http://127.0.0.1:8080"
ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "logs/day17-backend/report.json"

checks = []
fixtures = {}
# 端点编号 -> 该端点下最后一条断言的通过状态，用于报告「37 个端点各自过了没」
endpoint_state = {}
# 三个角色的 token 与 id：主流程与清理阶段共用，避免重复登录耗掉限流额度
tokens = {}
# admin token 自愈计数（见 api() 的说明）
_relogin = {"n": 0}
MAX_RELOGIN = 3


# ══════════════════════════════════════════════════════════════════
#  基础设施：check / sql / http / 字段断言
# ══════════════════════════════════════════════════════════════════
def check(name, passed, detail=None):
    checks.append({"name": name, "pass": bool(passed), "detail": None if detail is None else str(detail)})
    line = ("PASS " if passed else "FAIL ") + name
    if detail is not None and not passed:
        line += ": " + str(detail)
    print(line, flush=True)
    return bool(passed)


def mark(no, passed):
    """记录端点号的结果。同一端点多次 mark 取「与」——任一断言失败即端点失败。"""
    endpoint_state[no] = endpoint_state.get(no, True) and bool(passed)


def skipped(no, label, reason):
    """前置条件不满足 → 记一条失败（不能静默跳过，否则端点「看起来没测也没红」）。"""
    check("%s %s（前置不满足，未测到）" % (no, label), False, reason)
    mark(no, False)


def sql(statement):
    result = subprocess.run(
        ["docker", "exec", "-i", "oj-cloud-mysql", "sh", "-c",
         'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -N -B oj_cloud'],
        input=statement, capture_output=True, text=True, encoding="utf-8", check=True)
    return result.stdout.strip()


def sql_scalar(statement):
    out = sql(statement)
    first = out.splitlines()[0].strip() if out else ""
    if first in ("", "NULL"):
        return None
    try:
        return int(first)
    except ValueError:
        return first


def sql_exec(statement):
    """执行写语句；失败返回 False 而不是抛异常（清理阶段不能把验收带崩）。"""
    try:
        sql(statement)
        return True
    except Exception as error:  # noqa: BLE001
        print("     [cleanup] SQL 失败: " + str(error)[:160], flush=True)
        return False


def http(method, path, body=None, token=None, timeout=180):
    """返回 (status, headers_lower, text)。永不抛异常：连不上也给 status=0。"""
    headers = {"Content-Type": "application/json", "Accept": "*/*"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, {k.lower(): v for k, v in response.headers.items()}, \
                   response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, {k.lower(): v for k, v in error.headers.items()}, \
               error.read().decode("utf-8", "replace")
    except Exception as error:  # noqa: BLE001
        return 0, {}, json.dumps({"__error__": str(error)})


def envelope(text):
    try:
        value = json.loads(text)
        return value if isinstance(value, dict) else None
    except Exception:  # noqa: BLE001
        return None


def api(no, name, method, path, token, body=None, expect=200, retry_after_429=None, timeout=180):
    """一次端点调用 + 信封断言。成功返回 data（失败返回 None，但绝不抛异常）。

    retry_after_429：命中 429 时等多少秒重试一次（探活 10s 防抖 / 导出 1 分钟限流）。

    admin token 自愈：单会话登录（`oj:auth:token:{uid}` 会比对 token 本体）意味着
    「任何人再登一次 admin」都会把我们持有的 token 顶掉。联调期间 Lead 反复手工验
    admin 端点，会让本脚本跑到一半突然全 401（本轮真实发生过：76/80/81/83~87 全红）。
    那不是产品缺陷，但报告会看起来像后端崩了 —— 所以遇到 401 就重登一次再重试。
    """
    status, headers, text = http(method, path, body, token, timeout)

    if status == 401 and token == tokens.get("admin", (None,))[0] \
            and _relogin["n"] < MAX_RELOGIN:
        _relogin["n"] += 1
        print("     [401] admin token 被顶掉（多人联调时正常），重登后重试：%s" % path, flush=True)
        try:
            tokens.pop("admin", None)
            token, _ = login("admin")
            status, headers, text = http(method, path, body, token, timeout)
        except Exception as error:  # noqa: BLE001
            print("     [401] 重登失败: %s" % error, flush=True)

    if status == 429 and retry_after_429:
        print("     [429] 限流，等待 %ss 后重试一次：%s" % (retry_after_429, path), flush=True)
        time.sleep(retry_after_429)
        status, headers, text = http(method, path, body, token, timeout)
    env = envelope(text)
    ok = (status == expect
          and env is not None
          and env.get("code") == expect
          and (expect != 200 or (env.get("success") is True and str(env.get("traceId") or "").strip() != "")))
    detail = "HTTP %s code=%s msg=%s" % (
        status, None if env is None else env.get("code"),
        (None if env is None else env.get("message")) or text[:160])
    check("%s %s %s %s" % (no, method, path.split("?")[0], "→ %d" % expect), ok, detail)
    mark(no, ok)
    return env.get("data") if env else None


# ── 字段断言：支持 a.b、list[].b、rows[].cells[].passRate 三种路径写法 ──
def values_of(payload, path):
    node = [payload]
    for part in path.split("."):
        many = part.endswith("[]")
        key = part[:-2] if many else part
        nxt = []
        for item in node:
            if not isinstance(item, dict):
                continue
            value = item.get(key)
            if many:
                if isinstance(value, list):
                    nxt.extend(value)
            elif key in item:
                nxt.append(value)
        node = nxt
    return node


def expect(no, label, payload, spec, empty_ok=False):
    """spec = [(路径, 判定函数, 说明), ...]"""
    if payload is None:
        # 上一个 api() 已经失败并记过红；这里只补一条「字段无法校验」，避免刷屏 + 保证端点被标记
        check("%s %s 字段无法校验" % (no, label), False, "data 为 null（请求本身已失败）")
        mark(no, False)
        return
    if not isinstance(payload, dict):
        check("%s %s 有 data 对象" % (no, label), False, "data 不是对象: %r" % (payload,))
        mark(no, False)
        return
    for path, predicate, note in spec:
        found = values_of(payload, path)
        if not found:
            if empty_ok:
                continue
            check("%s %s 字段 %s 存在" % (no, label, path), False, "缺失 / 空数组")
            mark(no, False)
            continue
        bad = [v for v in found if not predicate(v)]
        check("%s %s 字段 %s 合法（%s）" % (no, label, path, note),
              not bad, "非法取值示例: %r" % (bad[:3],))
        mark(no, not bad)


# ── 类型谓词 ──
def is_num(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def is_int(v):
    return isinstance(v, int) and not isinstance(v, bool)


def is_str(v):
    return isinstance(v, str)


def is_bool(v):
    return isinstance(v, bool)


def is_list(v):
    return isinstance(v, list)


def pct(v):
    return is_num(v) and 0 <= v <= 100


def nonneg(v):
    return is_num(v) and v >= 0


def one_of(*allowed):
    return lambda v: v in allowed


def api_error(no, name, method, path, token, expect, body=None):
    """错误分支断言：状态码 + 信封 code 同值（且不是 500）。"""
    status, _, text = http(method, path, body, token)
    env = envelope(text)
    ok = status == expect and env is not None and env.get("code") == expect and env.get("success") is False
    check("%s %s" % (no, name), ok,
          "HTTP %s code=%s msg=%s" % (status, None if env is None else env.get("code"),
                                      (None if env is None else env.get("message"))))
    mark(no, ok)
    return ok


def login(username):
    """登录并缓存 token。同一账号只登一次——`oj:rate:login:{ip}` 有 1 分钟窗口（约 12 次），
    验收脚本内部反复登录会把自己撞成 429。"""
    if username in tokens:
        return tokens[username]
    status, _, text = http("POST", "/api/auth/login",
                           {"username": username, "password": "123456"})
    env = envelope(text)
    if status == 200 and env and env.get("data", {}).get("accessToken"):
        pair = (env["data"]["accessToken"], env["data"].get("user") or {})
        tokens[username] = pair
        return pair
    raise RuntimeError("登录 %s 失败：HTTP %s %s" % (username, status, text[:300]))


# ══════════════════════════════════════════════════════════════════
#  主流程
# ══════════════════════════════════════════════════════════════════
def main():
    print("═" * 72)
    print("  第 6 步后端验收：#51~#87（37 端点）· 真实网关 " + BASE)
    print("═" * 72)

    # ── 0. 前置：三个角色登录 ──────────────────────────────────────
    print("\n── 0. 前置账号与夹具 ──")
    student_token, student_user = login("20220101")
    teacher_token, teacher_user = login("T001")
    admin_token, admin_user = login("admin")
    check("0.1 学生登录（20220101）", bool(student_token) and student_user.get("role") == "STUDENT",
          student_user.get("role"))
    check("0.2 教师登录（T001）", bool(teacher_token) and teacher_user.get("role") == "TEACHER",
          teacher_user.get("role"))
    check("0.3 管理员登录（admin）", bool(admin_token) and admin_user.get("role") == "ADMIN",
          admin_user.get("role"))

    student_id = student_user.get("id") or sql_scalar("SELECT id FROM sys_user WHERE username='20220101'")
    class_id = student_user.get("classId") or 1
    fixtures.update({"classId": class_id, "studentUserId": student_id,
                     "teacherUserId": teacher_user.get("id"), "adminUserId": admin_user.get("id")})

    # 越权断言需要一个「另一个学生」；没现成的就自己造一个带 verify- 前缀的
    other_id = sql_scalar("SELECT id FROM sys_user WHERE role='STUDENT' AND id<>%d ORDER BY id LIMIT 1"
                          % int(student_id))
    if not other_id:
        sql_exec("INSERT IGNORE INTO sys_user(username,password,real_name,role,class_id,status) "
                 "SELECT 'verify-day17-stu',password,'第六步越权验收学生','STUDENT',%d,1 "
                 "FROM sys_user WHERE username='20220101';" % int(class_id))
        other_id = sql_scalar("SELECT id FROM sys_user WHERE username='verify-day17-stu'")
        fixtures["createdStudent"] = "verify-day17-stu"
    fixtures["otherStudentId"] = other_id
    check("0.4 存在第二个学生用于越权断言", is_int(other_id) or other_id is not None, other_id)

    # ══════════════════════════════════════════════════════════════
    #  A. 学情 #51~#53（契约 §6.5）
    # ══════════════════════════════════════════════════════════════
    print("\n── A. 班级与待办簇 #51~#53（教师视角）──")

    classes = api("51", "班级列表(带统计)", "GET",
                  "/api/teaching/classes?withStats=true", teacher_token)
    # 契约 §6.5.1 第 6 列逐字：{classId,className,teacherId,teacherName,studentCount,activeCount,avgScore,acRate}
    expect("51", "班级列表", classes, [
        ("list[].classId", is_int, "整数主键"),
        ("list[].className", is_str, "非空字符串"),
        ("list[].teacherId", is_int, "班主任主键"),
        ("list[].teacherName", is_str, "班主任姓名"),
        ("list[].studentCount", nonneg, "非负整数"),
        ("list[].activeCount", nonneg, "非负整数"),
        ("list[].avgScore", nonneg, "非负"),
        ("list[].acRate", pct, "0~100"),
        ("total", nonneg, "非负整数"),
    ], empty_ok=False)
    if isinstance(classes, dict) and classes.get("list"):
        first = classes["list"][0]
        check("51 班级统计 withStats=true 时 avgScore/acRate 非空",
              is_num(first.get("avgScore")) and is_num(first.get("acRate")),
              "avgScore=%r acRate=%r activeCount=%r"
              % (first.get("avgScore"), first.get("acRate"), first.get("activeCount")))
        mark("51", is_num(first.get("avgScore")) and is_num(first.get("acRate")))
        fixtures["className"] = first.get("className")

    # 学生看自己的班级列表（契约：学生返回本班）
    student_classes = api("51", "学生班级列表", "GET", "/api/teaching/classes", student_token)
    if isinstance(student_classes, dict):
        check("51 学生拿到本班且班级 id 与登录态一致",
              any(c.get("classId") == class_id for c in (student_classes.get("list") or [])),
              "classId=%s list=%r" % (class_id, [c.get("classId") for c in (student_classes.get("list") or [])]))
        mark("51", any(c.get("classId") == class_id for c in (student_classes.get("list") or [])))

    todos = api("52", "待办聚合(教师)", "GET", "/api/teaching/todos?limit=10", teacher_token)
    # 契约 §6.5.2 第 6 列逐字：
    # {list:[{key,label,subtitle,count,severity,href,actionText,warn}],healthyCount,totalCount}
    expect("52", "待办聚合", todos, [
        # 契约 §6.5.2 只固定了 key:"publish" 这一条示例，四类的其余 key 由实现自定
        # （后端实际用 publish/plag/model/urge）。断言「key 非空且四类齐全」比钉死字面值更对：
        # 钉死会把「后端换了个合理的 key」判成缺陷，那是假红。
        ("list[].key", is_str, "非空 key"),
        ("list[].label", is_str, "中文标签"),
        ("list[].subtitle", is_str, "副标题"),
        ("list[].count", nonneg, "非负整数"),
        ("list[].severity", one_of("info", "warn", "danger"), "severity 三值"),
        ("list[].href", is_str, "可直接跳转的路径"),
        ("list[].actionText", is_str, "动作文案"),
        ("list[].warn", is_bool, "红点开关"),
        ("healthyCount", nonneg, "非负整数"),
        ("totalCount", nonneg, "非负整数"),
    ], empty_ok=True)
    if isinstance(todos, dict):
        # 契约 §6.5.2 用途列写明是「发布/查重/通道/催交四类」——断言条数与不可重复
        keys = [t.get("key") for t in (todos.get("list") or [])]
        check("52 待办恰为四类且 key 不重复", len(keys) == 4 and len(set(keys)) == 4,
              "keys=%r" % (keys,))
        mark("52", len(keys) == 4 and len(set(keys)) == 4)
        check("52 含契约示例中的 publish 类", "publish" in keys, "keys=%r" % (keys,))
        mark("52", "publish" in keys)
        # healthyCount 定义：count==0 的条数
        healthy_actual = len([t for t in (todos.get("list") or []) if (t.get("count") or 0) == 0])
        check("52 healthyCount == count 为 0 的条数", todos.get("healthyCount") == healthy_actual,
              "healthyCount=%r 实际=%r" % (todos.get("healthyCount"), healthy_actual))
        mark("52", todos.get("healthyCount") == healthy_actual)

    # 学生访问待办：契约只列 403，但教师专属聚合对学生应给 403（若实现允许则记 FAIL 由 Lead 定夺）
    api_error("52", "学生访问教师待办 → 403", "GET", "/api/teaching/todos", student_token, 403)

    trend = api("53", "周趋势", "GET", "/api/teaching/week-trend?classId=%d&weeks=1" % int(class_id),
                teacher_token)
    expect("53", "周趋势", trend, [
        ("days[].date", is_str, "日期字符串"),
        ("days[].weekday", is_str, "周几"),
        ("days[].submitCount", nonneg, "非负整数"),
        ("days[].acCount", nonneg, "非负整数"),
        ("days[].passRate", pct, "0~100"),
        ("weekPassRate", pct, "0~100"),
        ("weekSubmit", nonneg, "非负整数"),
        ("weekAc", nonneg, "非负整数"),
        ("peakDate", is_str, "非空字符串"),
    ], empty_ok=False)
    if isinstance(trend, dict) and isinstance(trend.get("days"), list):
        check("53 近 1 周恰好 7 天", len(trend["days"]) == 7, "days=%d" % len(trend["days"]))
        mark("53", len(trend["days"]) == 7)
        # 交叉校验：weekSubmit/weekAc 必须等于逐日求和（不是另拍一个数）
        sum_submit = sum(d.get("submitCount") or 0 for d in trend["days"])
        sum_ac = sum(d.get("acCount") or 0 for d in trend["days"])
        check("53 weekSubmit/weekAc 与逐日求和一致",
              trend.get("weekSubmit") == sum_submit and trend.get("weekAc") == sum_ac,
              "weekSubmit=%r vs Σ=%r；weekAc=%r vs Σ=%r"
              % (trend.get("weekSubmit"), sum_submit, trend.get("weekAc"), sum_ac))
        mark("53", trend.get("weekSubmit") == sum_submit and trend.get("weekAc") == sum_ac)
        fixtures["weekDays"] = len(trend["days"])

    # ══════════════════════════════════════════════════════════════
    #  B. 学情分析簇 #54~#62（契约 §6.6）
    # ══════════════════════════════════════════════════════════════
    print("\n── B. 学情分析簇 #54~#62 ──")

    hard = api("54", "易错题排行", "GET",
               "/api/teaching/analytics/hard-problems?classId=%d&range=month&mode=ac&limit=5"
               % int(class_id), teacher_token)
    # 契约 §6.6.1 第 6 列逐字：
    # {list:[{problemId,displayNo,title,difficulty,triedCount,acRate,avgScore,firstFailSeq,
    #         topStudents:[{name,stuNo,failCount,lastAt}]}],total}
    expect("54", "易错题排行", hard, [
        ("list[].problemId", is_int, "整数主键"),
        ("list[].displayNo", is_str, "展示号（ID 策略：主键不出现在 UI）"),
        ("list[].title", is_str, "非空字符串"),
        # difficulty 契约列为必带字段。后端 currently 从 data.problemMeta 取，
        # 而 problemMeta 只从「班级 assignments 里的题」填充 —— 学生自由练习提交的题
        # （如 #1006 A+B、#1024）不在 assignments 里 → meta 为 null → difficulty=null、
        # title 退化成「题目 1006」。这是**真缺陷**（后端该按需补查 prob_question）。
        ("list[].difficulty", one_of(1, 2, 3, 4, 5), "难度 1~5"),
        ("list[].triedCount", nonneg, "非负整数"),
        ("list[].acRate", pct, "0~100"),
        ("list[].avgScore", nonneg, "非负"),
        ("list[].firstFailSeq", nonneg, "首个失败测试点序号"),
        ("list[].topStudents[].name", is_str, "学生姓名"),
        ("list[].topStudents[].stuNo", is_str, "学号"),
        ("list[].topStudents[].failCount", nonneg, "非负整数"),
        ("total", nonneg, "非负整数"),
    ], empty_ok=True)
    if isinstance(hard, dict) and hard.get("list"):
        # 逐条点出「题目元信息缺失」的具体题号，让后端一眼能定位（而不是只报 difficulty=null）
        missing_meta = [{"problemId": r.get("problemId"), "title": r.get("title"),
                         "difficulty": r.get("difficulty")}
                        for r in hard["list"]
                        if r.get("difficulty") is None or str(r.get("title") or "").startswith("题目 ")]
        check("54 每条易错题的元信息（title/difficulty）都来自真实 prob_question",
              not missing_meta,
              "元信息缺失（problemMeta 未覆盖自由练习提交的题）: %r" % (missing_meta[:6],))
        mark("54", not missing_meta)
    # 记录一个「真的有提交」的题目给 #55 用
    probe_pid = None
    if isinstance(hard, dict) and hard.get("list"):
        probe_pid = hard["list"][0].get("problemId")
    if probe_pid is None:
        probe_pid = sql_scalar("SELECT problem_id FROM judge_submission GROUP BY problem_id "
                               "ORDER BY COUNT(*) DESC LIMIT 1")
    if probe_pid is None:
        probe_pid = sql_scalar("SELECT id FROM prob_question WHERE state=1 ORDER BY id LIMIT 1")
    fixtures["probeProblemId"] = probe_pid
    check("54 取到用于 #55 的真实题目", probe_pid is not None, probe_pid)
    mark("54", probe_pid is not None)

    if probe_pid is not None:
        errors = api("55", "单题错误分析", "GET",
                     "/api/teaching/analytics/problem/%d/errors?classId=%d"
                     % (int(probe_pid), int(class_id)), teacher_token)
        # 契约 §6.6.2 第 6 列逐字：
        # {problemId,title,totalSubmit,acCount,acRate,
        #  cases:[{seqNo,kind,score,failCount,failRate,topWrongOutput}],
        #  errorTypes:[{code:"TLE",count,percent}]}
        expect("55", "单题错误分析", errors, [
            ("problemId", is_int, "整数主键"),
            ("title", is_str, "非空字符串"),
            ("totalSubmit", nonneg, "非负整数"),
            ("acCount", nonneg, "非负整数"),
            ("acRate", pct, "0~100"),
            ("cases[].seqNo", nonneg, "测试点序号"),
            ("cases[].kind", is_str, "sample / edge 等"),
            ("cases[].score", nonneg, "非负"),
            ("cases[].failCount", nonneg, "非负整数"),
            ("cases[].failRate", pct, "0~100"),
            ("cases[].topWrongOutput", is_str, "最典型错误输出"),
            ("errorTypes[].code", is_str, "WA/TLE/MLE/RE/CE"),
            ("errorTypes[].count", nonneg, "非负整数"),
            ("errorTypes[].percent", pct, "0~100"),
        ], empty_ok=True)
        if isinstance(errors, dict) and errors.get("cases"):
            # 契约把 topWrongOutput 列为每个测试点的必带字段（前端「典型错因」列要展示）
            missing_wrong = [c.get("seqNo") for c in errors["cases"]
                             if c.get("failCount") and c.get("topWrongOutput") in (None, "")]
            if missing_wrong:
                check("55 有失败人数的测试点均带 topWrongOutput", False,
                      "缺失 seqNo=%r" % (missing_wrong[:5],))
                mark("55", False)
            else:
                check("55 有失败人数的测试点均带 topWrongOutput", True, "全部具备")
                mark("55", True)

    overview = api("56", "班级总览", "GET",
                   "/api/teaching/analytics/class/%d?range=month" % int(class_id), teacher_token)
    # 契约 §6.6.3 第 6 列逐字：
    # {classId,className,studentCount,avgScore,acRate,activeCount,aiRiskCount,
    #  dist:{ac,partial,fail,max,min,median,sd},peakDay,peakCount,weakestKp,avgKpMastery}
    #
    # ⚠️ dist 的第二键是 **partial**，不是 part。原型 t-analytics.html 里变量叫 dist.part，
    #    但契约字段名以 02 文档 §6.6.3 为准 —— **不做别名兼容**：后端下发 part 就让它红，
    #    否则前端按契约写的适配器永远对不上，而且这种「差一个字母」的漂移最难发现。
    expect("56", "班级总览", overview, [
        ("classId", is_int, "整数主键"),
        ("className", is_str, "非空字符串"),
        ("studentCount", nonneg, "非负整数"),
        ("avgScore", nonneg, "非负"),
        ("acRate", pct, "0~100"),
        ("activeCount", nonneg, "非负整数"),
        ("aiRiskCount", nonneg, "非负整数"),
        ("dist.ac", pct, "0~100"),
        ("dist.partial", pct, "0~100（键名必须是 partial，不是 part）"),
        ("dist.fail", pct, "0~100"),
        ("dist.max", nonneg, "0~100 分"),
        ("dist.min", nonneg, "0~100 分"),
        ("dist.median", nonneg, "0~100 分"),
        ("dist.sd", nonneg, "标准差 ≥0"),
        ("peakDay", is_str, "提交峰值日期"),
        ("peakCount", nonneg, "非负整数"),
        ("weakestKp", is_str, "最弱知识点"),
        ("avgKpMastery", pct, "0~100"),
    ], empty_ok=False)
    if isinstance(overview, dict) and isinstance(overview.get("dist"), dict):
        dist = overview["dist"]
        # 反向守卫：如果后端用的是原型变量名 part，明确点名这是契约漂移（而不是静默通过）
        if "partial" not in dist and "part" in dist:
            check("56 dist 键名按契约用 partial（不是 part）", False,
                  "后端下发 dist.part=%r —— 契约 §6.6.3 要求键名 partial；"
                  "同一个 dist 里出现 part 说明后端照抄了原型变量名" % (dist.get("part"),))
            mark("56", False)
        total_pct = (dist.get("ac") or 0) + (dist.get("partial") or 0) + (dist.get("fail") or 0)
        check("56 dist.ac+partial+fail ≈ 100", 97 <= total_pct <= 103, "合计 %.1f" % total_pct)
        mark("56", 97 <= total_pct <= 103)
        check("56 dist.min ≤ median ≤ max",
              (dist.get("min") or 0) <= (dist.get("median") or 0) <= (dist.get("max") or 0),
              "min=%r median=%r max=%r" % (dist.get("min"), dist.get("median"), dist.get("max")))
        mark("56", (dist.get("min") or 0) <= (dist.get("median") or 0) <= (dist.get("max") or 0))
        fixtures["dist"] = dist

    knowledge = api("57", "知识点掌握度", "GET",
                    "/api/teaching/analytics/class/%d/knowledge?range=month" % int(class_id),
                    teacher_token)
    # 契约 §6.6.4 第 6 列逐字：
    # {list:[{kpId,kpName,masteryPercent,acCount,triedCount,level,symptom,cause,advice,
    #         recommendProblems:[{problemId,title,difficulty}]}],avgMastery,weakCount}
    # 语义陷阱：masteryPercent 越高越强（0~100），不是 weak_tags 的「薄弱名单」
    expect("57", "知识点掌握度", knowledge, [
        ("list[].kpId", is_int, "整数主键"),
        ("list[].kpName", is_str, "非空字符串"),
        ("list[].masteryPercent", pct, "数字且 0~100"),
        ("list[].acCount", nonneg, "非负整数"),
        ("list[].triedCount", nonneg, "非负整数"),
        ("list[].level", one_of("weak", "normal", "strong"), "weak/normal/strong"),
        ("list[].symptom", is_str, "症状描述"),
        ("list[].cause", is_str, "成因"),
        ("list[].advice", is_str, "改进建议"),
        ("list[].recommendProblems[].problemId", is_int, "推荐题主键"),
        ("list[].recommendProblems[].title", is_str, "推荐题名"),
        ("list[].recommendProblems[].difficulty", one_of(1, 2, 3, 4, 5), "难度 1~5"),
        ("avgMastery", pct, "0~100"),
        ("weakCount", nonneg, "非负整数"),
    ], empty_ok=False)
    if isinstance(knowledge, dict) and knowledge.get("list"):
        rows = knowledge["list"]
        # level 与 masteryPercent 方向必须一致：越高越强
        # ⚠️ 阈值按后端的两档实测口径写（MASTERY_STRONG=70 判 normal 以上、MASTERY_WEAK=50 判 weak）：
        #    level=weak  ⇔ masteryPercent < 50
        #    level=normal⇔ 50 ≤ masteryPercent < 70
        #    level=strong⇔ masteryPercent ≥ 70
        # 之前我写成「weak 必须 < 70」，那条会把 50~69 的 normal 误判成冲突 —— 属于**脚本错**，
        # 已按 level 的三个档位分别断言。
        bad_level = [r for r in rows
                     if (r.get("level") == "weak" and (r.get("masteryPercent") or 0) >= 50)
                     or (r.get("level") == "normal"
                         and not (50 <= (r.get("masteryPercent") or 0) < 70))
                     or (r.get("level") == "strong" and (r.get("masteryPercent") or 0) < 70)]
        check("57 level 三档与 masteryPercent 阈值自洽（<50 weak / 50~70 normal / ≥70 strong）",
              not bad_level,
              "冲突示例: %r" % ([(r.get("kpName"), r.get("masteryPercent"), r.get("level"))
                               for r in bad_level[:5]],))
        mark("57", not bad_level)
        # weakCount 的语义：契约 §6.6.4 只给了字段名，没定义阈值。后端口径是「未达标」= < 70
        # （与前端 t-analytics 的「掌握度 ≥70% 视为达标」一致），不是 level=weak 的条数。
        # 这里断言「weakCount == masteryPercent < 70 的条数」并打印实际值供人工复核；
        # 若后端改成 <50 口径，会在 detail 里直接看出来。
        weak_below_70 = len([r for r in rows if (r.get("masteryPercent") or 0) < 70])
        weak_below_50 = len([r for r in rows if (r.get("masteryPercent") or 0) < 50])
        check("57 weakCount 等于「未达标（<70）知识点数」",
              knowledge.get("weakCount") == weak_below_70,
              "weakCount=%r；<70 的有 %r 个，<50 的有 %r 个（若 weakCount 命中后者说明后端用了 level=weak 口径）"
              % (knowledge.get("weakCount"), weak_below_70, weak_below_50))
        mark("57", knowledge.get("weakCount") == weak_below_70)
        fixtures["knowledge"] = [{"kpName": r.get("kpName"), "masteryPercent": r.get("masteryPercent"),
                                  "level": r.get("level")} for r in rows]

    students = api("58", "逐生学情", "GET",
                   "/api/teaching/analytics/class/%d/students?range=month&page=1&pageSize=10"
                   "&sortBy=score&sortOrder=desc" % int(class_id), teacher_token)
    # 契约 §6.6.5 第 6 列逐字：
    # {list:[{userId,name,stuNo,solved,total,score,aiUsed,acRate,spark:[7 个数],riskLevel,
    #         riskReason,knowledgeTags:[{name,percent}],recent:[{submissionId,problemTitle,status,score,at}]}],
    #  total,page,pageSize,hasNext}
    # 请求参数 sortBy 白名单：score/rate/ai/name
    expect("58", "逐生学情", students, [
        ("list[].userId", is_int, "整数主键"),
        ("list[].name", is_str, "真实姓名"),
        ("list[].stuNo", is_str, "学号"),
        ("list[].solved", nonneg, "非负整数"),
        ("list[].total", nonneg, "非负整数"),
        ("list[].score", nonneg, "非负"),
        ("list[].aiUsed", nonneg, "非负整数"),
        ("list[].acRate", pct, "0~100"),
        ("list[].spark[]", nonneg, "7 天迷你趋势"),
        ("list[].riskLevel", one_of("ok", "ai", "low", "idle"), "ok/ai/low/idle"),
        ("list[].riskReason", is_str, "风险原因"),
        ("list[].knowledgeTags[].name", is_str, "知识点名"),
        ("list[].knowledgeTags[].percent", pct, "0~100"),
        ("list[].recent[].submissionId", is_int, "提交主键"),
        ("list[].recent[].problemTitle", is_str, "题目名"),
        ("list[].recent[].status", nonneg, "判题状态"),
        ("list[].recent[].at", nonneg, "毫秒时间戳"),
        ("total", nonneg, "非负整数"),
        ("page", is_int, "页码"),
        ("pageSize", is_int, "每页条数"),
        ("hasNext", is_bool, "布尔"),
    ], empty_ok=False)
    if isinstance(students, dict):
        rows = students.get("list") or []
        check("58 spark 恰好 7 个数",
              all(len(r.get("spark") or []) == 7 for r in rows) and bool(rows),
              "示例: %r" % ([r.get("spark") for r in rows[:1]],))
        mark("58", all(len(r.get("spark") or []) == 7 for r in rows) and bool(rows))
        check("58 分页语义：page*pageSize < total ⇔ hasNext",
              (students.get("page", 1) * students.get("pageSize", 20) < students.get("total", 0))
              == students.get("hasNext"), "page=%r pageSize=%r total=%r hasNext=%r"
              % (students.get("page"), students.get("pageSize"),
                 students.get("total"), students.get("hasNext")))
        mark("58", (students.get("page", 1) * students.get("pageSize", 20) < students.get("total", 0))
             == students.get("hasNext"))
        check("58 本页条数 == min(pageSize, 剩余)",
              len(rows) == min(students.get("pageSize", 20), students.get("total", 0)),
              "rows=%d pageSize=%r total=%r"
              % (len(rows), students.get("pageSize"), students.get("total")))
        mark("58", len(rows) == min(students.get("pageSize", 20), students.get("total", 0)))
        fixtures["studentsTotal"] = students.get("total")
        fixtures["studentsPageSize"] = students.get("pageSize")
        fixtures["studentNames"] = [r.get("name") for r in rows][:20]

    # 契约 §6.6.5 请求参数：sortBy 白名单 score/rate/ai/name —— 四个都必须能用
    for sort_key in ("score", "rate", "ai", "name"):
        status, _, text = http(
            "GET", "/api/teaching/analytics/class/%d/students?sortBy=%s" % (int(class_id), sort_key),
            None, teacher_token)
        env = envelope(text)
        ok = status == 200 and isinstance((env or {}).get("data"), dict)
        check("58 sortBy=%s 是契约白名单内的合法取值" % sort_key, ok,
              "HTTP %s code=%s" % (status, (env or {}).get("code")))
        mark("58", ok)

    risk = api("59", "风险学生", "GET",
               "/api/teaching/analytics/class/%d/risk" % int(class_id), teacher_token)
    # 契约 §6.6.6 第 6 列逐字：
    # {list:[{userId,name,stuNo,riskLevel,riskReason,score,aiUsed,lastActiveAt,suggestion}],
    #  total,byLevel:{ai,low,idle}}     ← byLevel 的三个键就是 ai/low/idle，别写成别的
    expect("59", "风险学生", risk, [
        ("list[].userId", is_int, "整数主键"),
        ("list[].name", is_str, "真实姓名"),
        ("list[].stuNo", is_str, "学号"),
        ("list[].riskLevel", one_of("ai", "low", "idle"), "ai/low/idle"),
        ("list[].riskReason", is_str, "风险原因"),
        ("list[].score", nonneg, "非负"),
        ("list[].aiUsed", nonneg, "非负整数"),
        ("list[].lastActiveAt", nonneg, "毫秒时间戳（长期未交需要它）"),
        ("list[].suggestion", is_str, "处置建议"),
        ("total", nonneg, "非负整数"),
        ("byLevel.ai", nonneg, "非负整数"),
        ("byLevel.low", nonneg, "非负整数"),
        ("byLevel.idle", nonneg, "非负整数"),
    ], empty_ok=True)
    if isinstance(risk, dict):
        by_level = risk.get("byLevel") or {}
        check("59 byLevel 的键恰好是 ai/low/idle（契约 §6.6.6）",
              set(by_level.keys()) == {"ai", "low", "idle"},
              "实际键: %r" % (sorted(by_level.keys()),))
        mark("59", set(by_level.keys()) == {"ai", "low", "idle"})
        sum_level = sum((by_level.get(k) or 0) for k in ("ai", "low", "idle"))
        check("59 byLevel 三档之和 == total", sum_level == risk.get("total"),
              "Σ=%r total=%r" % (sum_level, risk.get("total")))
        mark("59", sum_level == risk.get("total"))
        fixtures["riskNames"] = [r.get("name") for r in (risk.get("list") or [])][:20]

    heatmap = api("60", "测试点热力图", "GET",
                  "/api/teaching/analytics/class/%d/heatmap?limit=6" % int(class_id), teacher_token)
    # 契约 §6.6.7：rows[].cells[].passRate —— 逐格通过率
    expect("60", "测试点热力图", heatmap, [
        ("rows[].problemId", is_int, "整数主键"),
        ("rows[].title", is_str, "非空字符串"),
        ("rows[].cells[].seqNo", nonneg, "测试点序号"),
        ("rows[].cells[].passRate", pct, "数字且 0~100"),
        ("rows[].cells[].status", one_of("ac", "wa", "none"), "ac/wa/none"),
        ("footNote", is_str, "页脚说明"),
    ], empty_ok=True)
    if isinstance(heatmap, dict) and heatmap.get("rows"):
        rows = heatmap["rows"]
        check("60 行数不超过 limit=6", len(rows) <= 6, "rows=%d" % len(rows))
        mark("60", len(rows) <= 6)
        # ⚠️ 契约 §6.6.7 标题写「前 N 题 × 10 格」，但**实际格数由该题真实测试点数决定**：
        #    库里有的题只有 3 个测试点、有的 10 个。硬断言「恰好 10 格」会把真实数据判成缺陷。
        #    正确判据是「cells 非空、seqNo 从 1 连续递增、且各题格数不强制相等」。
        cell_bad = []
        for row in rows:
            cells = row.get("cells") or []
            if not cells:
                cell_bad.append({"pid": row.get("problemId"), "why": "cells 为空"})
                continue
            seqs = sorted((c.get("seqNo") or 0) for c in cells)
            if seqs[0] != 1 or seqs != list(range(1, len(seqs) + 1)):
                cell_bad.append({"pid": row.get("problemId"), "seqNos": seqs})
        check("60 每行 cells 非空且 seqNo 从 1 连续递增（格数按真实测试点，不强制 10）",
              not cell_bad, "异常: %r" % (cell_bad[:5],))
        mark("60", not cell_bad)
        fixtures["heatRows"] = len(rows)
        fixtures["heatPids"] = [r.get("problemId") for r in rows]
        fixtures["heatCellCounts"] = [len(r.get("cells") or []) for r in rows]

    ai_usage = api("61", "AI 用量", "GET",
                   "/api/teaching/analytics/ai-usage?classId=%d&range=month&groupBy=stage"
                   % int(class_id), teacher_token)
    # 契约 §6.6.8 第 6 列逐字：
    # {totalCalls,stageDist:[{stage,stageName,count,percent}],
    #  topStudents:[{userId,name,count}],dailyTrend:[{date,count}],avgPerStudent}
    expect("61", "AI 用量", ai_usage, [
        ("totalCalls", nonneg, "非负整数"),
        ("stageDist[].stage", one_of(1, 2, 3, 4), "阶段 1~4"),
        ("stageDist[].stageName", is_str, "阶段名"),
        ("stageDist[].count", nonneg, "非负整数"),
        ("stageDist[].percent", pct, "0~100"),
        ("topStudents[].userId", is_int, "整数主键"),
        ("topStudents[].name", is_str, "真实姓名"),
        ("topStudents[].count", nonneg, "非负整数"),
        ("dailyTrend[].date", is_str, "日期"),
        ("dailyTrend[].count", nonneg, "非负整数"),
        ("avgPerStudent", nonneg, "非负"),
    ], empty_ok=True)
    if isinstance(ai_usage, dict) and ai_usage.get("stageDist"):
        stage_sum = sum((s.get("count") or 0) for s in ai_usage["stageDist"])
        check("61 stageDist 各阶段之和 == totalCalls", stage_sum == ai_usage.get("totalCalls"),
              "Σ=%r totalCalls=%r" % (stage_sum, ai_usage.get("totalCalls")))
        mark("61", stage_sum == ai_usage.get("totalCalls"))

    detail_self = api("62", "学生看自己", "GET",
                      "/api/teaching/analytics/student/%d?range=month" % int(student_id), student_token)
    # 契约 §6.6.9 第 6 列逐字：
    # {userId,name,stuNo,classId,solved,total,score,acRate,aiUsed,rank,
    #  weakKps:[{name,masteryPercent}],
    #  recentSubmissions:[{submissionId,problemTitle,status,score,at}],
    #  aiStages:[{stage,count}]}
    expect("62", "学生看自己", detail_self, [
        ("userId", is_int, "整数主键"),
        ("name", is_str, "真实姓名"),
        ("stuNo", is_str, "学号"),
        ("classId", is_int, "整数主键"),
        ("solved", nonneg, "非负整数"),
        ("total", nonneg, "非负整数"),
        ("score", nonneg, "非负"),
        ("acRate", pct, "0~100"),
        ("aiUsed", nonneg, "非负整数"),
        ("rank", nonneg, "班级排名"),
        ("weakKps[].name", is_str, "知识点名"),
        ("weakKps[].masteryPercent", pct, "0~100"),
        ("recentSubmissions[].submissionId", is_int, "提交主键"),
        ("recentSubmissions[].problemTitle", is_str, "题目名"),
        ("recentSubmissions[].status", nonneg, "判题状态"),
        ("recentSubmissions[].score", nonneg, "非负"),
        ("recentSubmissions[].at", nonneg, "毫秒时间戳"),
        ("aiStages[].stage", one_of(1, 2, 3, 4), "阶段 1~4"),
        ("aiStages[].count", nonneg, "非负整数"),
    ], empty_ok=True)
    if isinstance(detail_self, dict):
        check("62 学生看到的是自己（userId 与登录态一致）",
              detail_self.get("userId") == student_id,
              "返回 %r / 登录 %r" % (detail_self.get("userId"), student_id))
        mark("62", detail_self.get("userId") == student_id)

    # ── 越权：学生 A 看学生 B 必须 403 ──
    api_error("62", "越权：学生 A 读学生 B（id=%s）→ 403" % other_id, "GET",
              "/api/teaching/analytics/student/%s" % other_id, student_token, 403)
    # 教师看学生是合法路径（同一端点，另一条权限分支）
    teacher_view = api("62", "教师看学生", "GET",
                       "/api/teaching/analytics/student/%d?range=month" % int(student_id), teacher_token)
    check("62 教师可以看本班学生", isinstance(teacher_view, dict) and teacher_view.get("userId") == student_id,
          "data 类型 %s" % type(teacher_view).__name__)
    mark("62", isinstance(teacher_view, dict) and teacher_view.get("userId") == student_id)

    # ══════════════════════════════════════════════════════════════
    #  C. 管理端 #72~#87（契约 §7）
    # ══════════════════════════════════════════════════════════════
    print("\n── C. 管理端 #72~#87（管理员视角）──")

    channels = api("72", "通道列表", "GET", "/api/admin/channels?page=1&pageSize=50", admin_token)
    # 契约 §7.2.1 第 6 列逐字：
    # {list:[{id,channelCode,channelName,vendor,modelName,roleBindings:["Tutor"],baseUrl,
    #         timeoutMs,maxTokens,temperature,maxConcurrency,isEnabled,healthStatus,healthNote,
    #         avgTtftMs,todayCalls,tokensIn,tokensOut,costCents,lastProbeAt}],
    #  total,page,pageSize,hasNext,summary:{on,degrade,off,todayTotal}}
    #
    # ⚠️ roleBindings 是**数组**。v1.0 的单值列叫 roleBinding（无 s），契约已改成数组下发，
    #    两种形态断言结果完全不同 —— 这里只认数组，后端下发字符串就让它红。
    def maybe_num(v):
        """avgTtftMs 等统计值在「该通道零调用」时为 null 是**合理**的（没有样本可平均），
        不能因为 null 判红。所以用「null 或数字」而不是「必须是数字」。"""
        return v is None or is_num(v)

    expect("72", "通道列表", channels, [
        ("list[].id", is_int, "整数主键"),
        ("list[].channelCode", is_str, "稳定通道标识"),
        ("list[].channelName", is_str, "展示名"),
        ("list[].vendor", is_str, "厂商"),
        ("list[].modelName", is_str, "模型标识"),
        ("list[].roleBindings", is_list, "数组（不是 v1.0 的单值 roleBinding）"),
        ("list[].roleBindings[]", is_str, "ROUTER/TUTOR/GUARDRAIL"),
        ("list[].baseUrl", is_str, "端点 URL"),
        ("list[].timeoutMs", nonneg, "非负整数"),
        ("list[].maxTokens", nonneg, "非负整数"),
        ("list[].temperature", is_num, "0.00~1.00"),
        ("list[].maxConcurrency", nonneg, "非负整数"),
        ("list[].isEnabled", is_bool, "布尔"),
        ("list[].healthStatus", one_of("ON", "DEGRADE", "OFF", "PROBING"),
         "ON/DEGRADE/OFF/PROBING"),
        ("list[].avgTtftMs", maybe_num, "null（零调用）或数字"),
        ("list[].todayCalls", nonneg, "非负整数"),
        ("list[].tokensIn", nonneg, "非负整数"),
        ("list[].tokensOut", nonneg, "非负整数"),
        ("list[].costCents", nonneg, "非负整数"),
        ("total", nonneg, "非负整数"),
        ("page", is_int, "页码"),
        ("pageSize", is_int, "每页条数"),
        ("hasNext", is_bool, "布尔"),
        ("summary.on", nonneg, "非负整数"),
        ("summary.degrade", nonneg, "非负整数"),
        ("summary.off", nonneg, "非负整数"),
        ("summary.todayTotal", nonneg, "非负整数"),
    ], empty_ok=False)
    local_channel = None
    if isinstance(channels, dict) and channels.get("list"):
        for item in channels["list"]:
            if item.get("channelCode") == "local-qwen":
                local_channel = item
        fixtures["channelCount"] = channels.get("total")
        fixtures["channelCodes"] = [c.get("channelCode") for c in channels["list"]]
        # 反向守卫：单值 roleBinding 出现在响应里 = 后端还在用 v1.0 形态
        single = [c.get("channelCode") for c in channels["list"] if "roleBinding" in c]
        if single:
            check("72 不使用 v1.0 的单值 roleBinding 列名", False,
                  "这些通道同时下发了单值 roleBinding: %r（契约要求 roleBindings 数组）" % (single,))
            mark("72", False)
        # 每个通道至少绑定一个角色
        empty_roles = [c.get("channelCode") for c in channels["list"]
                       if not (c.get("roleBindings") or [])]
        check("72 每个通道至少绑定一个角色", not empty_roles,
              "无角色的通道: %r" % (empty_roles,))
        mark("72", not empty_roles)
        # summary 与逐条计数自洽（on+degrade+off == total，todayTotal == Σ todayCalls）
        summary = channels.get("summary") or {}
        by_state = {"on": 0, "degrade": 0, "off": 0}
        for item in channels["list"]:
            state = str(item.get("healthStatus") or "").upper()
            if state == "ON":
                by_state["on"] += 1
            elif state == "DEGRADE":
                by_state["degrade"] += 1
            elif state == "OFF":
                by_state["off"] += 1
        check("72 summary 与逐条 healthStatus 计数一致",
              summary.get("on") == by_state["on"] and summary.get("degrade") == by_state["degrade"]
              and summary.get("off") == by_state["off"],
              "summary=%r 实际=%r" % (summary, by_state))
        mark("72", summary.get("on") == by_state["on"] and summary.get("degrade") == by_state["degrade"]
             and summary.get("off") == by_state["off"])
        check("72 summary.todayTotal == Σ todayCalls",
              summary.get("todayTotal") == sum((c.get("todayCalls") or 0) for c in channels["list"]),
              "summary.todayTotal=%r Σ=%r"
              % (summary.get("todayTotal"), sum((c.get("todayCalls") or 0) for c in channels["list"])))
        mark("72", summary.get("todayTotal") == sum((c.get("todayCalls") or 0) for c in channels["list"]))

    # ── 密钥泄露守卫（agent.md §2.6 红线，任何响应字段都不许出现明文密钥）──
    # 形如 sk-xxxxxxxxxxxxxxxxxxxx / ${ENV_VAR} / env:VAR 的都是「把密钥原样吐给了前端」
    SECRET_PATTERN = re.compile(
        r"(sk-[A-Za-z0-9_\-]{20,})"          # 明文 key
        r"|(\$\{[A-Za-z_][A-Za-z0-9_]*\})"   # 占位符没被解析
        r"|(env:[A-Z_][A-Z0-9_]{3,})")       # env: 形态
    # 收敛成一个可复用的明文密钥扫描器（#73 / #72 / #82 都会用）

    def scan_secrets(no, label, payload):
        """递归扫一遍响应里所有字符串，命中密钥形态就判红。"""
        found = []

        def walk(node, path_str):
            if isinstance(node, dict):
                for key, value in node.items():
                    walk(value, path_str + "." + str(key) if path_str else str(key))
            elif isinstance(node, list):
                for index, value in enumerate(node):
                    walk(value, "%s[%d]" % (path_str, index))
            elif isinstance(node, str):
                hit = SECRET_PATTERN.search(node)
                if hit:
                    found.append({"field": path_str, "match": hit.group(0)[:40]})

        walk(payload, "")
        check("%s %s 响应里不含明文密钥（agent.md §2.6）" % (no, label), not found,
              "命中: %r" % (found[:5],) if found else "干净")
        mark(no, not found)
        return not found

    # ── #73 详情：先用真实通道 local-qwen ──
    if local_channel:
        detail = api("73", "通道详情(local-qwen)", "GET",
                     "/api/admin/channels/%d" % int(local_channel["id"]), admin_token)
        # 契约 §7.2.2 第 6 列逐字：
        # {id,channelCode,channelName,vendor,modelName,roleBindings,baseUrl,apiKeyMasked,
        #  timeoutMs,maxTokens,temperature,maxConcurrency,isEnabled,healthStatus,healthNote,
        #  avgTtftMs,trend24h:[{hour,ttftMs,calls}],recentCalls:[{recordId,at,ttftMs,tokens,guardCode}],
        #  degradeEvents:[{at,from,to,reason}]}
        expect("73", "通道详情", detail, [
            ("id", is_int, "整数主键"),
            ("channelCode", is_str, "稳定通道标识"),
            ("channelName", is_str, "展示名"),
            ("vendor", is_str, "厂商"),
            ("modelName", is_str, "模型标识"),
            ("roleBindings", is_list, "数组"),
            ("baseUrl", is_str, "端点 URL"),
            # apiKeyMasked：契约示例是 sk-****3f2a。**null 也是合法形态**——
            # 本地 Ollama 通道本来就没有 api_key（DB 里是空串），
            # SecretResolver.mask("") 返回 null（「没有密钥」比「脱敏后的空串」更准确）。
            # 不能因为「没配密钥的通道回 null」判红。
            ("apiKeyMasked", lambda v: v is None or is_str(v),
             "脱敏密钥（null = 该通道未配密钥，合法）"),
            ("timeoutMs", nonneg, "非负整数"),
            ("maxTokens", nonneg, "非负整数"),
            ("temperature", is_num, "0.00~1.00"),
            ("maxConcurrency", nonneg, "非负整数"),
            ("isEnabled", is_bool, "布尔"),
            ("healthStatus", one_of("ON", "DEGRADE", "OFF", "PROBING"), "四值"),
            ("avgTtftMs", lambda v: v is None or is_num(v), "null（零调用）或数字"),
            # trend24h：近 24 小时分桶，**每格每小时**，hour 是整数 0~23；
            # 该小时零调用时 ttftMs=null 是合理的（没有样本可平均）。
            ("trend24h[].hour", is_int, "小时（整数，契约未写死类型）"),
            ("trend24h[].ttftMs", lambda v: v is None or is_num(v), "null（该小时零调用）或数字"),
            ("trend24h[].calls", nonneg, "非负整数"),
            ("recentCalls[].recordId", is_int, "调用记录主键"),
            ("recentCalls[].at", nonneg, "毫秒时间戳"),
            ("recentCalls[].ttftMs", lambda v: v is None or is_num(v), "null 或数字"),
            ("recentCalls[].tokens", nonneg, "非负整数"),
            ("recentCalls[].guardCode", one_of("pass", "block", "rewrite"), "pass/block/rewrite"),
            ("degradeEvents[].at", nonneg, "毫秒时间戳"),
            ("degradeEvents[].from", is_str, "降级前通道"),
            ("degradeEvents[].to", is_str, "降级后通道"),
            ("degradeEvents[].reason", is_str, "降级原因"),
        ], empty_ok=True)
        if isinstance(detail, dict):
            masked_raw = detail.get("apiKeyMasked")
            masked = "" if masked_raw is None else str(masked_raw)
            # 有值时必须星号脱敏，且不比契约示例（sk-****3f2a，11 字符）长太多
            looks_masked = masked == "" or "*" in masked or "·" in masked
            too_long = len(masked) > 24
            check("73 apiKeyMasked 是脱敏形态（null/空串 或 含 * 且 ≤24 字符）",
                  looks_masked and not too_long,
                  "apiKeyMasked=%r（长度 %d）" % (masked_raw, len(masked)))
            mark("73", looks_masked and not too_long)
            # trend24h 应该是 24 格（近 24 小时逐小时分桶）
            trend = detail.get("trend24h") or []
            check("73 trend24h 为 24 个小时桶", len(trend) == 24, "实际 %d 格" % len(trend))
            mark("73", len(trend) == 24)
            scan_secrets("73", "通道详情", detail)
        api_error("73", "通道详情：不存在的 id → 404", "GET",
                  "/api/admin/channels/99999999", admin_token, 404)
    else:
        skipped("73", "通道详情", "#72 通道列表为空或没有 local-qwen，无法取真实通道 id")

    # #72 的列表响应同样不许带明文密钥（列表最容易直接把 api_key 列带出来）
    if isinstance(channels, dict):
        scan_secrets("72", "通道列表", channels)

    # ── #74 新增通道（自造数据，verify-ch- 前缀）──
    # ── #74 新增通道（自造数据，verify-ch- 前缀）──
    channel_code = "verify-ch-" + uuid.uuid4().hex[:6]
    fixtures["fixtureChannelCode"] = channel_code
    # ⚠️ baseUrl 必须带 /v1：ChannelProbeClient 拼的是 {baseUrl}/chat/completions（OpenAI 兼容），
    #    而真实 local-qwen 在 DB 里存的就是 http://...:11434/v1。
    #    我第一版写 http://127.0.0.1:11434（缺 /v1）→ #78 探活拿到 404，那是**脚本夹具错**，
    #    不是后端缺陷。这里对齐真实通道的 baseUrl 形态。
    created = api("74", "新增通道", "POST", "/api/admin/channels", admin_token, {
        "channelCode": channel_code,
        "channelName": "第六步验收临时通道",
        "vendor": "Ollama",
        "modelName": "qwen2.5-coder:7b",
        "roleBindings": ["Tutor"],
        "baseUrl": "http://127.0.0.1:11434/v1",
        "apiKey": "",
        "timeoutMs": 8000,
        "maxTokens": 256,
        "temperature": 0.2,
        "maxConcurrency": 2,
        "isEnabled": True,
    })
    expect("74", "新增通道", created, [
        ("id", is_int, "整数主键"),
        ("channelCode", is_str, "回显通道标识"),
        ("healthStatus", is_str, "创建后为 PROBING"),
    ], empty_ok=False)
    fixture_channel_id = created.get("id") if isinstance(created, dict) else None
    fixtures["fixtureChannelId"] = fixture_channel_id
    if isinstance(created, dict):
        check("74 回显的 channelCode 与提交一致", created.get("channelCode") == channel_code,
              "返回 %r / 提交 %r" % (created.get("channelCode"), channel_code))
        mark("74", created.get("channelCode") == channel_code)
        check("74 创建后健康态为 PROBING", str(created.get("healthStatus") or "").upper() == "PROBING",
              created.get("healthStatus"))
        mark("74", str(created.get("healthStatus") or "").upper() == "PROBING")
    # 参数校验：channelCode 重复 / 格式错 / roleBindings 空 → 400
    api_error("74", "新增通道：channelCode 重复 → 400", "POST", "/api/admin/channels", admin_token, 400, {
        "channelCode": channel_code, "channelName": "重复标识", "vendor": "Ollama",
        "modelName": "qwen2.5-coder:7b", "roleBindings": ["Tutor"],
        "baseUrl": "http://127.0.0.1:11434",
    })
    api_error("74", "新增通道：channelCode 格式错（大写）→ 400", "POST", "/api/admin/channels",
              admin_token, 400, {
                  "channelCode": "Verify_Bad", "channelName": "非法标识", "vendor": "Ollama",
                  "modelName": "qwen2.5-coder:7b", "roleBindings": ["Tutor"],
                  "baseUrl": "http://127.0.0.1:11434",
              })
    api_error("74", "新增通道：roleBindings 空 → 400", "POST", "/api/admin/channels", admin_token, 400, {
        "channelCode": "verify-ch-" + uuid.uuid4().hex[:6], "channelName": "空角色",
        "vendor": "Ollama", "modelName": "qwen2.5-coder:7b", "roleBindings": [],
        "baseUrl": "http://127.0.0.1:11434",
    })

    if fixture_channel_id:
        # ── #75 更新 ──
        # ⚠️ 契约 §7.2.4 写「id + 7.2.3 可选字段」= 部分更新（patch）语义。
        #    但当前实现里 ChannelCommandDTO.roleBindings 默认值是 new ArrayList<>()，
        #    AdminChannelService.update() 用 `dto.getRoleBindings() != null` 判断「有没有传」，
        #    Jackson 反序列化时不传该字段 → 保持默认空 list（不是 null）→ 走进
        #    normalizeRoles(空) → 400「roleBindings 不能为空」。
        #    结论：**部分更新（只改 channelName）目前是坏的**，这是真缺陷。
        # 脚本策略：先跑「带 roleBindings 的完整更新」（这是前端 a-models 抽屉的实际用法，
        #    字段齐全能过），**再单独断言一条「部分更新也不该 400」**并把红留给人判——
        #    这样既不掩盖缺陷，也不让后面的 #76/#77 因前置失败连锁崩掉。
        updated = api("75", "更新通道（带 roleBindings 完整更新）", "PUT",
                      "/api/admin/channels/%d" % int(fixture_channel_id), admin_token,
                      {"channelName": "第六步验收临时通道（已改）", "timeoutMs": 9000,
                       "maxTokens": 512, "roleBindings": ["Tutor"]})
        expect("75", "更新通道", updated, [
            ("id", is_int, "整数主键"),
            ("updatedAt", nonneg, "毫秒时间戳"),
        ], empty_ok=False)
        after = api("73", "更新后回读", "GET",
                    "/api/admin/channels/%d" % int(fixture_channel_id), admin_token)
        if isinstance(after, dict):
            check("75 更新时间真的落库（回读）",
                  after.get("channelName") == "第六步验收临时通道（已改）"
                  and after.get("timeoutMs") == 9000 and after.get("maxTokens") == 512,
                  "channelName=%r timeoutMs=%r maxTokens=%r"
                  % (after.get("channelName"), after.get("timeoutMs"), after.get("maxTokens")))
            mark("75", after.get("channelName") == "第六步验收临时通道（已改）"
                 and after.get("timeoutMs") == 9000 and after.get("maxTokens") == 512)
        api_error("75", "更新通道：不存在的 id → 404", "PUT",
                  "/api/admin/channels/99999999", admin_token, 404, {"channelName": "x"})

        # 单独守一条「部分更新不该 400」：契约 §7.2.4 是可选字段的 patch 语义。
        # 现在会红 —— 这是**真缺陷**（DTO 默认空 list 导致「没传」被当成「传了空」），
        # 保留红断言，不为了绿而放宽判据。
        status, _, text = http("PUT", "/api/admin/channels/%d" % int(fixture_channel_id),
                               {"channelName": "第六步验收临时通道（只改名）"}, admin_token)
        env = envelope(text)
        partial_ok = status == 200
        check("75 部分更新（只传 channelName）应 200 而不是 400 —— 契约 §7.2.4 可选字段",
              partial_ok,
              "HTTP %s code=%s msg=%s（若 400 且提示 roleBindings 不能为空，"
              "根因是 ChannelCommandDTO.roleBindings 默认 new ArrayList<>() 而非 null）"
              % (status, (env or {}).get("code"), (env or {}).get("message")))
        mark("75", partial_ok)
        # 上一步若成功把它改成了「只改名」，把名字改回来，免得影响后续断言
        if partial_ok:
            http("PUT", "/api/admin/channels/%d" % int(fixture_channel_id),
                 {"channelName": "第六步验收临时通道（已改）", "roleBindings": ["Tutor"]}, admin_token)

        # ── #77 状态（启用/禁用）──
        disabled = api("77", "禁用通道", "PUT",
                       "/api/admin/channels/%d/state" % int(fixture_channel_id), admin_token,
                       {"action": "disable"})
        expect("77", "禁用通道", disabled, [
            ("id", is_int, "整数主键"),
            ("isEnabled", is_bool, "布尔"),
            ("healthStatus", is_str, "ON/DEGRADE/OFF"),
            ("effectiveAt", nonneg, "毫秒时间戳"),
        ], empty_ok=False)
        if isinstance(disabled, dict):
            check("77 disable 后 isEnabled=false", disabled.get("isEnabled") is False,
                  "isEnabled=%r healthStatus=%r"
                  % (disabled.get("isEnabled"), disabled.get("healthStatus")))
            mark("77", disabled.get("isEnabled") is False)
        enabled = api("77", "启用通道", "PUT",
                      "/api/admin/channels/%d/state" % int(fixture_channel_id), admin_token,
                      {"action": "enable"})
        if isinstance(enabled, dict):
            check("77 enable 后 isEnabled=true", enabled.get("isEnabled") is True,
                  "isEnabled=%r" % (enabled.get("isEnabled"),))
            mark("77", enabled.get("isEnabled") is True)
        api_error("77", "状态：非法 action → 400", "PUT",
                  "/api/admin/channels/%d/state" % int(fixture_channel_id), admin_token, 400,
                  {"action": "explode"})

        # ── #78 探活：真实发一次最小请求 ──
        probe = api("78", "通道探活", "POST",
                    "/api/admin/channels/%d/probe" % int(fixture_channel_id), admin_token, {},
                    retry_after_429=12)
        expect("78", "通道探活", probe, [
            ("id", is_int, "整数主键"),
            ("healthStatus", is_str, "ON/DEGRADE/OFF"),
            ("latencyMs", nonneg, "真实耗时 >0"),
            ("httpStatus", nonneg, "真实 HTTP 状态码"),
            ("probedAt", nonneg, "毫秒时间戳"),
        ], empty_ok=False)
        if isinstance(probe, dict):
            check("78 探活耗时 > 0（不是写死的 0/常量）", (probe.get("latencyMs") or 0) > 0,
                  "latencyMs=%r" % (probe.get("latencyMs"),))
            mark("78", (probe.get("latencyMs") or 0) > 0)
            check("78 目标通道真实可达（Ollama 在跑 → httpStatus=200）",
                  probe.get("httpStatus") == 200,
                  "httpStatus=%r healthStatus=%r healthNote=%r"
                  % (probe.get("httpStatus"), probe.get("healthStatus"), probe.get("healthNote")))
            mark("78", probe.get("httpStatus") == 200)
            fixtures["probeLatencyMs"] = probe.get("latencyMs")
        # 对既有 local-qwen 再探一次：证明不是只对自造数据有效
        if local_channel:
            probe_real = api("78", "探活 local-qwen", "POST",
                             "/api/admin/channels/%d/probe" % int(local_channel["id"]),
                             admin_token, {}, retry_after_429=12)
            if isinstance(probe_real, dict):
                check("78 local-qwen 探活为 ON 且回显延迟",
                      str(probe_real.get("healthStatus") or "").upper() == "ON"
                      and (probe_real.get("latencyMs") or 0) > 0,
                      "healthStatus=%r latencyMs=%r httpStatus=%r"
                      % (probe_real.get("healthStatus"), probe_real.get("latencyMs"),
                         probe_real.get("httpStatus")))
                mark("78", str(probe_real.get("healthStatus") or "").upper() == "ON"
                     and (probe_real.get("latencyMs") or 0) > 0)

        # ── #79 恢复：先手动降级，再从降级恢复 ──
        # 前置：先把通道恢复成 ON，否则「已经 DEGRADE」会让 degrade 返回 409（这是对的行为，
        # 只是不该让 #79 因此拿不到可恢复的状态）。用 enable 把状态归一。
        http("PUT", "/api/admin/channels/%d/state" % int(fixture_channel_id),
             {"action": "enable"}, admin_token)
        degraded = api("77", "手动降级通道", "PUT",
                       "/api/admin/channels/%d/state" % int(fixture_channel_id), admin_token,
                       {"action": "degrade"})
        degrade_applied = isinstance(degraded, dict) and \
            str(degraded.get("healthStatus") or "").upper() == "DEGRADE"
        check("77 degrade 后 healthStatus=DEGRADE", degrade_applied,
              None if degraded is None else "healthStatus=%r" % (degraded.get("healthStatus"),))
        mark("77", degrade_applied)
        recover = api("79", "从降级恢复", "POST",
                      "/api/admin/channels/%d/recover" % int(fixture_channel_id), admin_token, {},
                      retry_after_429=12)
        if degrade_applied:
            expect("79", "从降级恢复", recover, [
                ("id", is_int, "整数主键"),
                ("healthStatus", is_str, "恢复后为 ON"),
                ("recoveredAt", nonneg, "毫秒时间戳"),
                ("failStreak", lambda v: v == 0, "连续失败计数归零"),
            ], empty_ok=False)
            if isinstance(recover, dict):
                check("79 恢复后真的回到 ON",
                      str(recover.get("healthStatus") or "").upper() == "ON",
                      "healthStatus=%r note=%r"
                      % (recover.get("healthStatus"), recover.get("healthNote")))
                mark("79", str(recover.get("healthStatus") or "").upper() == "ON")
        else:
            # 契约 §7.2.8：本就 ON 时恢复返回 409——实现未支持手动降级时按 409 判
            check("79 恢复：通道本就 ON → 409（契约 §7.2.8）", recover is None, "degrade 未生效，按 409 计")
            mark("79", recover is None)

        # ── #76 删除（软删）──
        deleted = api("76", "删除通道", "DELETE",
                      "/api/admin/channels/%d?force=true" % int(fixture_channel_id), admin_token)
        expect("76", "删除通道", deleted, [
            ("id", is_int, "整数主键"),
            ("deleted", lambda v: v is True, "deleted=true"),
        ], empty_ok=False)
        if isinstance(deleted, dict):
            # 契约 §7.2.5：「有历史调用则转禁用」。softDeleted 是**派生值**，取决于该通道
            # 在 ai_chat_record 里有没有记录 —— 我自造的夹具通道零调用，所以
            # softDeleted=false + 真删行 是**正确**行为。
            # 之前我硬断言 softDeleted==true（假定了它有历史），那是**脚本错**。
            # 正确判据：softDeleted 必须与「库内该 code 的历史调用数>0」一致。
            calls_before = sql_scalar(
                "SELECT COUNT(*) FROM ai_chat_record WHERE selected_channel='%s'"
                % channel_code) or 0
            expect_soft = calls_before > 0
            check("76 softDeleted 与「是否有历史调用」一致（契约 §7.2.5）",
                  deleted.get("softDeleted") is expect_soft,
                  "softDeleted=%r，该通道历史调用数=%r（期望 %r）"
                  % (deleted.get("softDeleted"), calls_before, expect_soft))
            mark("76", deleted.get("softDeleted") is expect_soft)
            fixtures["deleteCallsBefore"] = calls_before
            fixtures["deleteSoftDeleted"] = deleted.get("softDeleted")
        api_error("76", "删除通道：已删除的 id → 404", "DELETE",
                  "/api/admin/channels/%d" % int(fixture_channel_id), admin_token, 404)
    else:
        # #74 没返回 id 时，75/76/77/78/79 一个都测不了 —— 逐个记红，保持 37 端点覆盖口径
        for no, label in [("75", "更新通道"), ("76", "删除通道"), ("77", "通道状态"),
                          ("78", "通道探活"), ("79", "降级恢复")]:
            skipped(no, label, "#74 新增通道未返回 id，破坏性端点全部跳过")

    # ── #80 健康聚合 ──
    health = api("80", "全站健康", "GET", "/api/admin/health", admin_token)
    # 契约 §7.3.1 点名的四块：services / middleware / channels / sandbox
    expect("80", "全站健康", health, [
        ("services[].name", is_str, "服务名"),
        ("services[].status", is_str, "UP/DOWN"),
        ("services[].latencyMs", nonneg, "非负整数"),
        ("middleware[].name", is_str, "中间件名"),
        ("middleware[].status", is_str, "UP/DOWN"),
        ("channels.on", nonneg, "非负整数"),
        ("channels.degrade", nonneg, "非负整数"),
        ("channels.off", nonneg, "非负整数"),
        ("channels.total", nonneg, "非负整数"),
        ("sandbox.running", nonneg, "非负整数"),
        ("sandbox.queued", nonneg, "非负整数"),
        ("sandbox.capacity", nonneg, "非负整数"),
        ("degraded", is_bool, "布尔"),
        ("autoDegrade", is_bool, "布尔"),
        ("checkedAt", nonneg, "毫秒时间戳"),
    ], empty_ok=False)
    if isinstance(health, dict):
        names = [m.get("name") for m in (health.get("middleware") or [])]
        check("80 中间件覆盖 mysql/redis/rabbitmq",
              {"mysql", "redis", "rabbitmq"}.issubset({str(n).lower() for n in names}),
              names)
        mark("80", {"mysql", "redis", "rabbitmq"}.issubset({str(n).lower() for n in names}))
        svc_up = [s for s in (health.get("services") or []) if str(s.get("status")).upper() == "UP"]
        check("80 至少 3 个下游服务 UP", len(svc_up) >= 3,
              "UP=%d/%d" % (len(svc_up), len(health.get("services") or [])))
        mark("80", len(svc_up) >= 3)

    # ── #81 审计列表 ──
    calls = api("81", "调用审计列表", "GET",
                "/api/admin/audit/calls?page=1&pageSize=10&sortBy=at&sortOrder=desc", admin_token)
    # 契约 §7.4.1 第 6 列逐字：
    # {list:[{recordId,at,userId,userName,stage,stageName,channelCode,modelName,
    #         route:"local"/"cloud",ttftMs,tokensIn,tokensOut,guardAction:0,guardCode:"pass",
    #         guardReason:"NONE",note,submissionId,traceId}],
    #  total,page,pageSize,hasNext,summary:{todayTotal,hitDist:[{code:"leak",count:4}]}}
    #
    # ⚠️ guardCode 是 v1.0 单值 guardrail_action（0/1/2）的三分类派生命名，
    #    三值固定 pass/block/rewrite；hitDist 的 code 固定 leak/jailbreak/sensitive。
    #    这两组字符串拼错一个字母前端筛选就全空，必须逐字断言。
    expect("81", "调用审计列表", calls, [
        ("list[].recordId", is_int, "调用记录主键"),
        ("list[].at", nonneg, "毫秒时间戳"),
        ("list[].userId", is_int, "用户主键"),
        ("list[].userName", is_str, "用户名"),
        ("list[].stage", one_of(1, 2, 3, 4), "阶段 1~4"),
        ("list[].stageName", is_str, "阶段名"),
        ("list[].channelCode", is_str, "通道标识"),
        ("list[].modelName", is_str, "模型名"),
        ("list[].route", one_of("local", "cloud"), "local/cloud"),
        #  ttftMs：被护栏**拦截**（guardAction=1）的调用根本没发给模型，
        #  首 token 时间必然为 null —— 这是正确语义，不是缺陷。
        #  实测依据：库内 ai_chat_record 里 11 条 ttft_ms IS NULL，**全部**是
        #  guardrail_action=1（JAILBREAK 拦截）。
        #  所以这里放宽为「数字 ≥ 0 或 null」，另在下面加一条交叉断言
        #  「guardAction≠1 的行 ttftMs 不允许为 null」——既不放走真正的丢字段缺陷，
        #  也不把「拦截没调模型」这条正确语义判红。
        ("list[].ttftMs", lambda v: v is None or nonneg(v), "非负整数或 null（拦截未调模型）"),
        ("list[].tokensIn", nonneg, "非负整数"),
        ("list[].tokensOut", nonneg, "非负整数"),
        ("list[].guardAction", one_of(0, 1, 2), "0 通过/1 拦截/2 改写"),
        ("list[].guardCode", one_of("pass", "block", "rewrite"), "pass/block/rewrite"),
        ("list[].guardReason", is_str, "护栏命中原因"),
        ("list[].traceId", is_str, "链路追踪 ID"),
        ("total", nonneg, "非负整数"),
        ("page", is_int, "页码"),
        ("pageSize", is_int, "每页条数"),
        ("hasNext", is_bool, "布尔"),
        ("summary.todayTotal", nonneg, "非负整数"),
        ("summary.hitDist[].code", one_of("leak", "jailbreak", "sensitive"),
         "leak/jailbreak/sensitive"),
        ("summary.hitDist[].count", nonneg, "非负整数"),
    ], empty_ok=True)
    record_id = None
    if isinstance(calls, dict) and calls.get("list"):
        record_id = calls["list"][0].get("recordId")
        fixtures["recordId"] = record_id
        fixtures["callsTotal"] = calls.get("total")
        # guardAction 与 guardCode 必须一一对应（0↔pass / 1↔block / 2↔rewrite），
        # 两列各写一份最容易漂移成「action=1 但 code=pass」
        ACTION_TO_CODE = {0: "pass", 1: "block", 2: "rewrite"}
        drift = [{"recordId": c.get("recordId"), "action": c.get("guardAction"),
                  "code": c.get("guardCode")}
                 for c in calls["list"]
                 if ACTION_TO_CODE.get(c.get("guardAction")) != c.get("guardCode")]
        check("81 guardAction 与 guardCode 一一对应（0↔pass/1↔block/2↔rewrite）",
              not drift, "漂移示例: %r" % (drift[:5],))
        mark("81", not drift)
        # ttftMs 交叉断言：只有「被护栏拦截、根本没调模型」的行允许为 null。
        # 反过来说，任何真正跑过模型的调用都必须有首 token 时间——
        # 别让上面对 ttftMs 的 null 放宽变成「字段丢了也看不出来」。
        missing_ttft = [{"recordId": c.get("recordId"), "guardAction": c.get("guardAction")}
                        for c in calls["list"]
                        if c.get("ttftMs") is None and c.get("guardAction") != 1]
        check("81 非拦截调用必须有 ttftMs（null 仅允许出现在 guardAction=1 的拦截行）",
              not missing_ttft, "缺 ttftMs 且非拦截: %r" % (missing_ttft[:5],))
        mark("81", not missing_ttft)
        sql_total = sql_scalar("SELECT COUNT(*) FROM ai_chat_record")
        check("81 列表 total 与 ai_chat_record 行数一致（真实聚合，不是写死）",
              sql_total is not None and calls.get("total") == sql_total,
              "接口 total=%r 库内 COUNT=%r" % (calls.get("total"), sql_total))
        mark("81", sql_total is not None and calls.get("total") == sql_total)
    # 参数校验（契约 §7.4.1 错误码列：400）
    api_error("81", "审计列表：sortBy=garbage → 400", "GET",
              "/api/admin/audit/calls?sortBy=garbage", admin_token, 400)
    api_error("81", "审计列表：stage=9（越界）→ 400", "GET",
              "/api/admin/audit/calls?stage=9", admin_token, 400)

    # ── #82 审计详情 ──
    if record_id:
        call_detail = api("82", "调用详情", "GET",
                          "/api/admin/audit/calls/%d" % int(record_id), admin_token)
        expect("82", "调用详情", call_detail, [
            ("recordId", is_int, "调用记录主键"),
            ("at", nonneg, "毫秒时间戳"),
            ("user.id", is_int, "用户主键"),
            ("user.name", is_str, "真实姓名"),
            ("stage", one_of(1, 2, 3, 4), "阶段 1~4"),
            ("channelCode", is_str, "通道标识"),
            ("modelName", is_str, "模型名"),
            ("route", one_of("local", "cloud"), "local/cloud"),
            ("isFallback", is_bool, "布尔"),
            ("ttftMs", nonneg, "非负整数"),
            ("tokensIn", nonneg, "非负整数"),
            ("tokensOut", nonneg, "非负整数"),
            ("costCents", nonneg, "非负整数"),
            ("promptText", is_str, "完整 prompt"),
            ("replyText", is_str, "完整回复"),
            ("guardAction", nonneg, "0/1/2"),
            ("guardCode", one_of("pass", "block", "rewrite"), "pass/block/rewrite"),
            ("chain[].seqNo", nonneg, "测试点序号"),
            ("chain[].kind", is_str, "测试点类型"),
            ("chain[].status", is_str, "判定结果"),
            ("traceId", is_str, "链路追踪 ID"),
        ], empty_ok=True)
        # 审计详情带完整 prompt / reply，是「把用户数据泄露到管理端」之外最该守住的地方
        if isinstance(call_detail, dict):
            scan_secrets("82", "调用详情", call_detail)
        api_error("82", "调用详情：不存在的 recordId → 404", "GET",
                  "/api/admin/audit/calls/99999999", admin_token, 404)
    else:
        skipped("82", "调用详情", "#81 审计列表为空，取不到 recordId（ai_chat_record 有数据却查不到）")

    # ── #83 CSV 导出（raw，不走 JSON 信封）──
    def export_csv(path, token):
        status, headers, text = http("GET", path, None, token)
        if status == 429:
            print("     [429] 导出限流（1 次/分钟），等待 61s 后重试一次", flush=True)
            time.sleep(61)
            status, headers, text = http("GET", path, None, token)
        return status, headers, text

    status, headers, text = export_csv("/api/admin/audit/calls/export?limit=50", admin_token)
    content_type = (headers.get("content-type") or "").lower()
    disposition = (headers.get("content-disposition") or "").lower()
    check("83 导出 CSV HTTP 200", status == 200, "HTTP %s" % status)
    mark("83", status == 200)
    check("83 Content-Type 为 text/csv", "text/csv" in content_type, content_type)
    mark("83", "text/csv" in content_type)
    check("83 Content-Disposition 含 attachment", "attachment" in disposition, disposition)
    mark("83", "attachment" in disposition)
    header_line = ""
    for line in text.replace("\r\n", "\n").split("\n"):
        if line.strip():
            header_line = line
            break
    # ⚠️ 后端为了 Excel 打开中文不乱码会写 UTF-8 BOM（\ufeff）。直接 split(',') 会把
    # 第一个列名变成 "\ufeffrecordId"，虽然列数仍是 12，但列名对不上。
    # 这里先剥 BOM 再解析，并把「是否有 BOM」记进 fixtures 供人工复核（有 BOM 是对的）。
    header_has_bom = header_line.startswith("\ufeff")
    header_line = header_line.lstrip("\ufeff")
    columns = [c.strip().strip('"') for c in header_line.split(",")]
    check("83 表头固定 12 列", len(columns) == 12,
          "列数=%d 表头=%r" % (len(columns), header_line[:200]))
    mark("83", len(columns) == 12)
    # 列名逐个对上契约 §7.4.1 的字段集合（契约 §7.4.3 只说「12 列」，列名以 §7.4.1 为准）
    EXPECTED_CSV = ["recordId", "at", "userId", "userName", "stage", "channelCode",
                    "modelName", "route", "ttftMs", "tokensIn", "tokensOut", "guardCode"]
    check("83 12 个列名与契约 §7.4.1 字段一致", columns == EXPECTED_CSV,
          "实际=%r\n             期望=%r" % (columns, EXPECTED_CSV))
    mark("83", columns == EXPECTED_CSV)
    body_rows = [line for line in text.replace("\r\n", "\n").split("\n")[1:] if line.strip()]
    check("83 导出行数与 limit 相符（≤50 且 >0）", 0 < len(body_rows) <= 50, "行数=%d" % len(body_rows))
    mark("83", 0 < len(body_rows) <= 50)
    fixtures["csvColumns"] = columns
    fixtures["csvHasBom"] = header_has_bom
    fixtures["csvRows"] = len(body_rows)
    api_error("83", "导出：limit 超上限(20000) → 400", "GET",
              "/api/admin/audit/calls/export?limit=99999", admin_token, 400)

    # ── #84~#86 沙箱运维（排队夹具；真实运行取消由 verify-step6-runtime.py 覆盖）──
    sandbox_no = "sbx-v6" + uuid.uuid4().hex[:4]
    fixtures["fixtureSandboxId"] = sandbox_no
    sql_exec(
        "INSERT INTO judge_sandbox_instance"
        "(sandbox_instance_id,submission_id,user_id,problem_id,language,state,judge_node,"
        " cpu_limit,mem_limit_kb,cpu_percent,mem_used_kb,exit_code,started_at,last_heartbeat_at,log_ref)"
        " VALUES('%s',NULL,%d,%s,'cpp','QUEUED','local',1,262144,0,0,NULL,NULL,NOW(),"
        "'[sandbox] verify-day17 fixture\\n[run] case 1 ok 4ms\\n[run] case 2 ok 6ms');"
        % (sandbox_no, int(student_id), int(probe_pid) if probe_pid else "NULL"))
    check("84 夹具：自造沙箱实例已入库", sql_scalar(
        "SELECT COUNT(*) FROM judge_sandbox_instance WHERE sandbox_instance_id='%s'" % sandbox_no) == 1,
        sandbox_no)
    mark("84", sql_scalar(
        "SELECT COUNT(*) FROM judge_sandbox_instance WHERE sandbox_instance_id='%s'" % sandbox_no) == 1)

    boxes = api("84", "沙箱实例列表", "GET",
                "/api/admin/sandbox/instances?page=1&pageSize=20", admin_token)
    # 契约 §7.5.1 第 6 列逐字：
    # {list:[{sandboxInstanceId,state,language,languageLabel,userId,userName,problemId,
    #         submissionId,cpuPercent,memKb,startedAt,ageMs,exitCode}],
    #  total,page,pageSize,hasNext,
    #  stats:{running,queued,exited,capacity,avgWaitMs,avgRunMs,recycleRate}}
    #
    # ⚠️ state 是四值 QUEUED/RUNNING/EXITED/KILLED（基线 §5.6），
    #    **不是** shell.js:436 的 run/idle —— 拿旧字典写后端会让审计页永远筛不到数据。
    expect("84", "沙箱实例列表", boxes, [
        ("list[].sandboxInstanceId", is_str, "sbx- 展示号"),
        ("list[].state", one_of("QUEUED", "RUNNING", "EXITED", "KILLED"),
         "QUEUED/RUNNING/EXITED/KILLED（不是 run/idle）"),
        ("list[].language", one_of("cpp", "java", "python"), "cpp/java/python"),
        ("list[].languageLabel", is_str, "C++ 17 / Java 17 / Python 3"),
        ("list[].userId", is_int, "用户主键"),
        ("list[].cpuPercent", nonneg, "非负整数"),
        ("list[].memKb", nonneg, "非负整数"),
        ("list[].startedAt", lambda v: v is None or nonneg(v), "未启动为null，否则毫秒时间戳"),
        ("list[].ageMs", lambda v: v is None or nonneg(v), "未启动为null，否则非负整数"),
        ("total", nonneg, "非负整数"),
        ("page", is_int, "页码"),
        ("pageSize", is_int, "每页条数"),
        ("hasNext", is_bool, "布尔"),
        ("stats.running", nonneg, "非负整数"),
        ("stats.queued", nonneg, "非负整数"),
        ("stats.exited", nonneg, "非负整数"),
        ("stats.capacity", nonneg, "非负整数"),
        ("stats.avgWaitMs", nonneg, "非负整数"),
        ("stats.avgRunMs", nonneg, "非负整数"),
        ("stats.recycleRate", nonneg, "回收率"),
    ], empty_ok=True)
    if isinstance(boxes, dict):
        running_times_ok = all(b.get("startedAt") is not None and b.get("ageMs") is not None
                               for b in boxes.get("list", []) if b.get("state") == "RUNNING")
        check("84 RUNNING实例必须有真实启动时间与存活时长", running_times_ok)
        mark("84", running_times_ok)
        # 反向守卫：出现 run/idle 说明后端照抄了 shell.js 的旧字典
        legacy = [b.get("sandboxInstanceId") for b in (boxes.get("list") or [])
                  if str(b.get("state") or "").lower() in ("run", "idle")]
        if legacy:
            check("84 state 不使用 shell.js 的 run/idle 旧字典", False,
                  "实例 %r 的 state 是 run/idle 形态" % (legacy[:5],))
            mark("84", False)
        ids = [b.get("sandboxInstanceId") for b in (boxes.get("list") or [])]
        check("84 自造实例出现在列表里", sandbox_no in ids, "list 里 %d 条: %r" % (len(ids), ids[:6]))
        mark("84", sandbox_no in ids)
        stats = boxes.get("stats") or {}
        check("84 stats.capacity > 0（不是占位 0）", (stats.get("capacity") or 0) > 0,
              "capacity=%r" % (stats.get("capacity"),))
        mark("84", (stats.get("capacity") or 0) > 0)
        running_count = sql_scalar("SELECT COUNT(*) FROM judge_sandbox_instance WHERE state='RUNNING'")
        check("84 stats.running 与数据库 RUNNING 条数一致",
              stats.get("running") == running_count,
              "stats.running=%r（库内 RUNNING=%r）"
              % (stats.get("running"),
                 sql_scalar("SELECT COUNT(*) FROM judge_sandbox_instance WHERE state='RUNNING'")))
        mark("84", stats.get("running") == running_count)

    logs = api("85", "沙箱日志", "GET",
               "/api/admin/sandbox/instances/%s/logs?lines=100" % sandbox_no, admin_token)
    expect("85", "沙箱日志", logs, [
        ("sandboxInstanceId", is_str, "回显展示号"),
        ("state", one_of("QUEUED", "RUNNING", "EXITED", "KILLED"), "四态"),
        ("lines[].ts", is_num, "时间戳（契约只写 ts，后端下发毫秒 Long）"),
        ("lines[].msg", is_str, "日志内容"),
        ("truncated", is_bool, "布尔"),
        ("collectedAt", nonneg, "毫秒时间戳"),
    ], empty_ok=True)
    if isinstance(logs, dict):
        check("85 日志非空（夹具 log_ref 有 3 行）", len(logs.get("lines") or []) > 0,
              "lines=%d" % len(logs.get("lines") or []))
        mark("85", len(logs.get("lines") or []) > 0)
        check("85 lines 上限遵守（≤ 请求的 100 行）", len(logs.get("lines") or []) <= 100,
              len(logs.get("lines") or []))
        mark("85", len(logs.get("lines") or []) <= 100)
    api_error("85", "沙箱日志：不存在的实例 → 404", "GET",
              "/api/admin/sandbox/instances/sbx-000000/logs", admin_token, 404)

    killed = api("86", "终止沙箱", "DELETE",
                 "/api/admin/sandbox/instances/%s?reason=%s"
                 % (sandbox_no, urllib.parse.quote("第六步验收：强杀")), admin_token)
    expect("86", "终止沙箱", killed, [
        ("sandboxInstanceId", is_str, "回显展示号"),
        ("state", lambda v: v == "KILLED", "state=KILLED"),
        ("killedAt", nonneg, "毫秒时间戳"),
        ("releasedCpu", nonneg, "回收 CPU"),
        ("releasedMemKb", nonneg, "回收内存"),
        ("exitCode", lambda v: v == 137, "137 = 被 kill"),
    ], empty_ok=False)
    check("86 终止真的落库（state=KILLED）",
          sql("SELECT state FROM judge_sandbox_instance WHERE sandbox_instance_id='%s'"
              % sandbox_no).strip() == "KILLED",
          sql("SELECT state FROM judge_sandbox_instance WHERE sandbox_instance_id='%s'" % sandbox_no))
    mark("86", sql("SELECT state FROM judge_sandbox_instance WHERE sandbox_instance_id='%s'"
                   % sandbox_no).strip() == "KILLED")
    api_error("86", "终止沙箱：已 EXITED/KILLED → 409（契约 §7.5.3）", "DELETE",
              "/api/admin/sandbox/instances/%s" % sandbox_no, admin_token, 409)
    api_error("86", "终止沙箱：不存在的实例 → 404", "DELETE",
              "/api/admin/sandbox/instances/sbx-000000", admin_token, 404)

    # ── #87 全站统计 ──
    # 契约 §7.6.1 只写「range?（默认 7 天）」，**没有列举合法取值**。
    # 后端口径是 today / 7d / 30d（AdminStatsService.RANGES）。这是「已知契约留白」：
    # 脚本按后端实际白名单取 7d（不传 range 时默认值也是 7 天，语义等价）。
    stats = api("87", "全站统计", "GET", "/api/admin/stats?range=7d", admin_token)
    expect("87", "全站统计", stats, [
        ("users.total", nonneg, "非负整数"),
        ("users.students", nonneg, "非负整数"),
        ("users.teachers", nonneg, "非负整数"),
        ("users.admins", nonneg, "非负整数"),
        ("users.activeToday", nonneg, "非负整数"),
        ("problems.total", nonneg, "非负整数"),
        ("problems.published", nonneg, "非负整数"),
        ("problems.draft", nonneg, "非负整数"),
        ("problems.archived", nonneg, "非负整数"),
        ("submissions.total", nonneg, "非负整数"),
        ("submissions.today", nonneg, "非负整数"),
        ("submissions.acRate", pct, "0~100"),
        ("ai.callsToday", nonneg, "非负整数"),
        ("ai.tokensToday", nonneg, "非负整数"),
        ("ai.costCentsToday", nonneg, "非负整数"),
        ("ai.fallbackRate", pct, "0~100"),
        ("teaching.classes", nonneg, "非负整数"),
        ("teaching.assignments", nonneg, "非负整数"),
        ("teaching.exams", nonneg, "非负整数"),
        ("teaching.pendingPlagiarism", nonneg, "非负整数"),
        ("storage.testCaseBytes", nonneg, "字节数"),
        ("storage.codeBytes", nonneg, "字节数"),
        ("trend[].date", is_str, "日期"),
        ("trend[].submissions", nonneg, "非负整数"),
        ("trend[].aiCalls", nonneg, "非负整数"),
    ], empty_ok=False)
    if isinstance(stats, dict):
        users = stats.get("users") or {}
        check("87 users.total == students+teachers+admins",
              users.get("total") == (users.get("students") or 0) + (users.get("teachers") or 0)
              + (users.get("admins") or 0),
              "users=%r" % (users,))
        mark("87", users.get("total") == (users.get("students") or 0) + (users.get("teachers") or 0)
             + (users.get("admins") or 0))
        sql_problems = sql_scalar("SELECT COUNT(*) FROM prob_question WHERE deleted_at IS NULL")
        check("87 problems.total 与库内真实题数一致",
              sql_problems is not None and (stats.get("problems") or {}).get("total") == sql_problems,
              "接口=%r 库内=%r" % ((stats.get("problems") or {}).get("total"), sql_problems))
        mark("87", sql_problems is not None
             and (stats.get("problems") or {}).get("total") == sql_problems)
        sql_submit = sql_scalar("SELECT COUNT(*) FROM judge_submission")
        check("87 submissions.total 与 judge_submission 行数一致",
              sql_submit is not None and (stats.get("submissions") or {}).get("total") == sql_submit,
              "接口=%r 库内=%r" % ((stats.get("submissions") or {}).get("total"), sql_submit))
        mark("87", sql_submit is not None
             and (stats.get("submissions") or {}).get("total") == sql_submit)
        fixtures["statsProblems"] = (stats.get("problems") or {}).get("total")
        fixtures["statsSubmissions"] = (stats.get("submissions") or {}).get("total")

    # ══════════════════════════════════════════════════════════════
    #  D. 横切：权限 / 参数校验
    # ══════════════════════════════════════════════════════════════
    print("\n── D. 横切断言：/api/admin/** 角色权限 + 参数校验 ──")
    admin_endpoints = [
        ("/api/admin/channels", "GET"),
        ("/api/admin/health", "GET"),
        ("/api/admin/audit/calls", "GET"),
        ("/api/admin/audit/calls/export", "GET"),
        ("/api/admin/sandbox/instances", "GET"),
        ("/api/admin/stats", "GET"),
    ]
    for path, method in admin_endpoints:
        label = path.replace("/api/admin/", "")
        api_error("P", "学生访问 /api/admin/%s → 403" % label, method, path, student_token, 403)
        api_error("P", "教师访问 /api/admin/%s → 403" % label, method, path, teacher_token, 403)
    # ── 401 vs 403 的语义分离（契约 §2.8 / 网关 AuthGlobalFilter）──
    # 三种情形必须分别是三个码，混了会让前端「token 过期」时卡在提示里反复点：
    #   ① 完全不带 Authorization          → 网关 JWT 阶段拦下 → 401
    #   ② 带非法/伪造 token               → 网关 JWT 解析失败 → 401
    #   ③ 带合法 token 但角色不是 ADMIN   → 网关注入 X-User-Role 后由服务层拒 → 403
    # ②③ 测的不是同一层：②是网关的 401，③是服务层的 403。两条都要断言。
    status, _, text = http("GET", "/api/admin/channels")      # ← token 省略 = 真的不发 Authorization 头
    env = envelope(text)
    check("P 未登录（不带 Authorization 头）/api/admin/channels → 401（网关层）",
          status == 401 and env is not None and env.get("code") == 401,
          "HTTP %s code=%s" % (status, None if env is None else env.get("code")))
    mark("P", status == 401 and env is not None and env.get("code") == 401)

    # ② 伪造 token：网关 JWT 解析失败，同样应是 401（且不是 500）
    status, _, text = http("GET", "/api/admin/channels", None, "fake.forged.token")
    env = envelope(text)
    check("P 伪造 token /api/admin/channels → 401（网关 JWT 校验失败）",
          status == 401 and env is not None and env.get("code") == 401,
          "HTTP %s code=%s msg=%s" % (status, None if env is None else env.get("code"),
                                      None if env is None else env.get("message")))
    mark("P", status == 401 and env is not None and env.get("code") == 401)

    print("\n── D2. 参数校验：非法参数不许 500 ──")
    # 统一口径：**首要要求是「不是 500」**；在 400 与契约明确规定的收敛行为之间二选一即可。
    # （契约 §1「pageSize 超过 100 按 100 处理，不报错」与「非法参数返回 400」并不冲突，
    #   前者是合法的水位收敛，后者是真正的非法值——两者都算通过，500 一律算失败。）

    def not_500(no, label, path, token, also_ok=None):
        status, _, text = http("GET", path, None, token)
        env = envelope(text)
        code = (env or {}).get("code")
        if status == 500 or code == 500:
            check("%s %s → 不是 500" % (no, label), False, "实际 HTTP %s code=%s（服务内部错误）" % (status, code))
            mark(no, False)
            return None, None
        extra_ok = bool(also_ok) and also_ok(env)
        passed = status in (200, 400) or extra_ok
        check("%s %s → %s（非 500）" % (no, label, status), passed,
              "HTTP %s code=%s data=%s" % (status, code, str(env.get("data"))[:120] if env else None))
        mark(no, passed)
        return status, env

    def clamped_page_size(env):
        data = (env or {}).get("data")
        return isinstance(data, dict) and isinstance(data.get("pageSize"), int) \
            and data.get("pageSize") <= 100

    not_500("V", "pageSize=9999：落回 ≤100 或 400（契约 §1 水位收敛）",
            "/api/teaching/analytics/class/%d/students?pageSize=9999" % int(class_id),
            teacher_token, also_ok=clamped_page_size)

    api_error("V", "sortBy=garbage → 400（白名单外）", "GET",
              "/api/teaching/analytics/class/%d/students?sortBy=garbage" % int(class_id),
              teacher_token, 400)

    not_500("V", "classId=-1：404 或 400，不是 500",
            "/api/teaching/analytics/class/-1", teacher_token)

    not_500("V", "heatmap limit=999：400 或收敛，不是 500",
            "/api/teaching/analytics/class/%d/heatmap?limit=999" % int(class_id), teacher_token)

    not_500("V", "审计列表 pageSize=9999：不是 500",
            "/api/admin/audit/calls?pageSize=9999", admin_token)

    # ══════════════════════════════════════════════════════════════
    #  E. 收尾
    # ══════════════════════════════════════════════════════════════
    print("\n── E. 端点覆盖统计 ──")
    # 本脚本覆盖契约 #51~#62（12）+ #72~#87（16）= 28 个端点。
    # #63~#71（查重 9 个）属第 5 步，由 verify-day12.py 覆盖，此处只做「别漏」的交叉引用。
    expected_endpoints = [str(n) for n in range(51, 63)] + [str(n) for n in range(72, 88)]
    covered = [no for no in expected_endpoints if no in endpoint_state]
    passed = [no for no in covered if endpoint_state[no]]
    print("     本脚本覆盖 %d/%d 个端点（#51~#62 + #72~#87）；全断言通过 %d 个"
          % (len(covered), len(expected_endpoints), len(passed)), flush=True)
    missing = [no for no in expected_endpoints if no not in endpoint_state]
    check("E.1 本脚本负责的 28 个端点（#51~#62 + #72~#87）全部有断言",
          len(covered) == 28, "缺失: %r" % (missing,))
    failed = [no for no in covered if not endpoint_state[no]]
    check("E.2 本脚本负责的 28 个端点全部通过", not failed, "未通过端点: %r" % (failed,))
    fixtures["endpointState"] = endpoint_state
    fixtures["endpointsFailed"] = failed

    # ── 37 端点全覆盖的拼图：本脚本 28 + verify-day12.py 的 #63~#71 = 37 ──
    # ⚠️ 这里只做**静态**核对（不改数据、不调 AI）：#63/#71 要真跑双阶段查重
    #    （真实 Ollama + 40 秒/次），在 day17 里再造一遍夹具既慢又会和 day12 抢模型。
    #    核对不上的端点如实报出来 —— 那种「以为有人覆盖，其实两个脚本都没测」的缺口
    #    比一条红的断言危险得多。
    verify_day12 = ROOT / "scripts/verify-day12.py"
    if verify_day12.exists():
        day12 = verify_day12.read_text(encoding="utf-8")
        # (端点号, 在 day12 源码里应出现的路径片段, HTTP 方法) —— 精确匹配，不做宽松包含
        plagiarism_paths = [
            ("63", "/api/teaching/plagiarism/check'", "POST"),
            ("64", "/api/teaching/plagiarism/diff/", "GET"),
            ("65", "/api/teaching/plagiarism/audit'", "PUT"),
            ("66", "/api/teaching/plagiarism/audit/", "DELETE"),
            ("67", "/api/teaching/plagiarism/tasks'", "POST"),
            ("68", "/api/teaching/plagiarism/tasks/", "GET"),
            ("69", "/api/teaching/plagiarism/pairs?", "GET"),
            ("70", "/api/teaching/plagiarism/pairs/", "GET"),
            ("71", "/api/teaching/plagiarism/tasks/", "POST"),   # rerun
        ]
        uncovered = []
        for no, needle, method in plagiarism_paths:
            has_path = needle in day12
            if no == "71":
                # #71 与 #67/#68 路径前缀完全相同，必须额外确认 rerun 后缀真的出现过
                has_path = has_path and "/rerun" in day12
            if not (has_path and method in day12):
                uncovered.append(no)
        check("E.3 #63~#71（查重 9 个）已由 verify-day12.py 覆盖 → 28+9=37",
              not uncovered, "verify-day12.py 里没有真实调用: %r" % (uncovered,))
        fixtures["plagiarismUncovered"] = uncovered
        if uncovered:
            print("     ⚠️ #%s 在 verify-day12.py 里找不到真实调用 —— 这几个端点目前"
                  "**没有任何自动化覆盖**（不属本脚本范围，需补进 day12）"
                  % "/#".join(uncovered), flush=True)
        print("     全站 87 端点拼图：#1~#50 → verify-day4/day8/day12；"
              "#51~#62 + #72~#87 → 本脚本；#63~#71 → %s"
              % ("day12 全部覆盖" if not uncovered else "day12 缺 %d 个" % len(uncovered)),
              flush=True)
    else:
        check("E.3 verify-day12.py 存在（#63~#71 的覆盖证明）", False, str(verify_day12))


def run_all():
    """跑完整验收 + 清理 + 落报告。返回进程退出码。

    包成函数而不是模块级裸脚本，是为了让 `_selftest_day17_paths.py` 能
    `import` 本文件来单测 values_of/pct 这类纯函数 —— 模块级裸脚本一 import 就会
    真的去连网关。
    """
    try:
        main()
    except Exception as error:  # noqa: BLE001
        import traceback
        check("验收流程执行完整", False, "%s\n%s" % (error, traceback.format_exc()[-1200:]))
    finally:
        # ══════════════════════════════════════════════════════════
        #  清理：破坏性操作造的测试数据全部回收
        # ══════════════════════════════════════════════════════════
        print("\n── 清理 fixtures ──", flush=True)
        code = fixtures.get("fixtureChannelCode")
        ch_id = fixtures.get("fixtureChannelId")
        if code:
            # 先走 API 删（记录一次真实删除），再兜底删库，避免软删后残留
            admin_token = tokens.get("admin", (None, None))[0]
            if ch_id and admin_token:
                http("DELETE", "/api/admin/channels/%d?force=true" % int(ch_id), None, admin_token)
            sql_exec("DELETE FROM ai_model_role WHERE channel_code='%s'" % code)
            sql_exec("DELETE FROM ai_model_config WHERE channel_code='%s'" % code)
            print("     [cleanup] 通道 %s 已删除" % code, flush=True)
            # 通道缓存必须清掉，否则 AI 服务读到的是旧快照（agent.md §2.6）
            subprocess.run(["docker", "exec", "oj-cloud-redis", "redis-cli", "DEL",
                            "oj:ai:config:channels"], capture_output=True, text=True)
        sandbox_no = fixtures.get("fixtureSandboxId")
        if sandbox_no:
            sql_exec("DELETE FROM judge_sandbox_instance WHERE sandbox_instance_id='%s'" % sandbox_no)
            print("     [cleanup] 沙箱夹具 %s 已删除" % sandbox_no, flush=True)
        if fixtures.get("createdStudent"):
            sql_exec("DELETE FROM sys_user WHERE username='%s'" % fixtures["createdStudent"])

        # 兜底：清掉**任何** verify- 前缀的残留通道 / 沙箱 / 探活锁。
        # 这条不是多余的——人工排障时用 curl 手搓的 verify-probe-xxxx 通道会绕过 fixtures，
        # 不兜底就会永久留在库里（本轮就真发生过一条 verify-probe-9582）。
        sql_exec("DELETE FROM ai_model_role WHERE channel_code LIKE 'verify-%'")
        sql_exec("DELETE FROM ai_model_config WHERE channel_code LIKE 'verify-%'")
        sql_exec("DELETE FROM judge_sandbox_instance WHERE sandbox_instance_id LIKE 'sbx-v6%'")
        leftover_codes = sql("SELECT GROUP_CONCAT(channel_code) FROM ai_model_config "
                             "WHERE channel_code LIKE 'verify-%'")
        if leftover_codes and leftover_codes.strip() not in ("", "NULL"):
            print("     [cleanup] ⚠️ 仍有残留通道: %s" % leftover_codes, flush=True)
        try:
            keys = subprocess.run(
                ["docker", "exec", "oj-cloud-redis", "redis-cli", "KEYS", "oj:admin:probe:verify-*"],
                capture_output=True, text=True).stdout.split()
            if keys:
                subprocess.run(["docker", "exec", "oj-cloud-redis", "redis-cli", "DEL"] + keys,
                               capture_output=True, text=True)
                print("     [cleanup] 清掉 %d 个 verify- 探活锁" % len(keys), flush=True)
        except Exception:  # noqa: BLE001
            pass

        OUT.parent.mkdir(parents=True, exist_ok=True)
        total = len(checks)
        passed = sum(1 for c in checks if c["pass"])
        OUT.write_text(json.dumps({
            "passed": passed, "total": total,
            "endpointState": endpoint_state,
            "fixtures": fixtures, "checks": checks,
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        print("\n%d/%d passed  →  %s" % (passed, total, OUT), flush=True)
        if total and passed != total:
            for item in checks:
                if not item["pass"]:
                    print("  FAIL  " + item["name"]
                          + ("  —— " + item["detail"] if item["detail"] else ""), flush=True)
    return 0 if checks and all(c["pass"] for c in checks) else 1


if __name__ == "__main__":
    raise SystemExit(run_all())
