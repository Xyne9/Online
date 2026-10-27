"""临时自检：验证 verify-day17.py 的字段路径解析逻辑（不联网、不起服务）。

覆盖 expect() 用到的三种路径写法：a.b / list[].b / rows[].cells[].c
重点是契约里最容易写歪的几组：#56 dist.partial、#60 cells.passRate、
#72 summary.*、#84 stats.capacity、#72 roleBindings 数组。
"""
import importlib.util
import sys

# 直接加载 verify-day17.py 里的纯函数（不执行 main）
spec = importlib.util.spec_from_file_location("v17", "scripts/verify-day17.py")
mod = importlib.util.module_from_spec(spec)
# 拦住模块级 SystemExit：文件末尾有 raise SystemExit(...)，只能在 exec 时捕获
try:
    spec.loader.exec_module(mod)
except SystemExit:
    pass

values_of = mod.values_of
is_num, is_int, is_list = mod.is_num, mod.is_int, mod.is_list
pct, nonneg, one_of = mod.pct, mod.nonneg, mod.one_of

failures = []


def case(label, payload, path, expected):
    got = values_of(payload, path)
    ok = got == expected
    print(("PASS " if ok else "FAIL ") + "%s: %s -> %r" % (label, path, got))
    if not ok:
        failures.append("%s: 期望 %r 得到 %r" % (label, expected, got))


print("── 路径解析：单层 / 数组 / 嵌套数组 ──")
# #56 契约 §6.6.3
dist = {"ac": 60, "partial": 25, "fail": 15, "max": 98, "min": 42, "median": 78, "sd": 12.3}
case("#56 班级总览", {"dist": dist}, "dist.partial", [25])
for key in ("ac", "partial", "fail", "max", "min", "median", "sd"):
    case("#56 dist." + key, {"dist": dist}, "dist." + key, [dist[key]])

print("\n── 反向守卫：后端写成 part 时必须取不到 ──")
# 后端若照抄原型变量名 dist.part，#56 的 dist.partial 断言必须取不到 → 判红
case("#56 错写成 part", {"dist": {"ac": 60, "part": 25, "fail": 15}}, "dist.partial", [])
print("     ↑ 空列表 = 正确判红（后端下发 part 不认）")

print("\n── #60 三层嵌套：rows[].cells[].passRate ──")
rows = {"rows": [
    {"problemId": 1, "title": "A", "cells": [{"seqNo": 1, "passRate": 80, "status": "ac"}]},
    {"problemId": 2, "title": "B", "cells": [{"seqNo": 1, "passRate": 0, "status": "none"}]},
]}
case("#60 热力图", rows, "rows[].cells[].passRate", [80, 0])
case("#60 cells 状态", rows, "rows[].cells[].status", ["ac", "none"])
case("#60 行标题", rows, "rows[].title", ["A", "B"])

print("\n── #72 通道列表：summary + roleBindings 数组 ──")
channels = {
    "list": [{"id": 1, "roleBindings": ["Tutor", "Router"]},
             {"id": 2, "roleBindings": ["Guardrail"]}],
    "total": 2, "page": 1, "pageSize": 50, "hasNext": False,
    "summary": {"on": 2, "degrade": 1, "off": 1, "todayTotal": 1840},
}
case("#72 roleBindings（数组本身）", channels, "list[].roleBindings", [["Tutor", "Router"], ["Guardrail"]])
case("#72 roleBindings 元素", channels, "list[].roleBindings[]", ["Tutor", "Router", "Guardrail"])
for key in ("on", "degrade", "off", "todayTotal"):
    case("#72 summary." + key, channels, "summary." + key, [channels["summary"][key]])

print("\n── #84 沙箱：stats 四值 + state 四态 ──")
boxes = {
    "list": [{"sandboxInstanceId": "sbx-0001", "state": "RUNNING", "language": "cpp"}],
    "total": 1,
    "stats": {"running": 3, "queued": 1, "exited": 2, "capacity": 16,
              "avgWaitMs": 820, "avgRunMs": 1240, "recycleRate": 33.3},
}
for key in ("running", "queued", "exited", "capacity", "avgWaitMs", "avgRunMs", "recycleRate"):
    case("#84 stats." + key, boxes, "stats." + key, [boxes["stats"][key]])
case("#84 state", boxes, "list[].state", ["RUNNING"])

print("\n── 谓词：pct / nonneg / one_of 边界 ──")
checks = [
    ("pct(0)", pct(0), True), ("pct(100)", pct(100), True),
    ("pct(101)", pct(101), False), ("pct(-1)", pct(-1), False),
    ("pct(True) 布尔不算数字", pct(True), False),
    ("pct(float 62.5)", pct(62.5), True),
    ("nonneg(0)", nonneg(0), True), ("nonneg(-0.1)", nonneg(-0.1), False),
    ("one_of('ac','wa','none')('wa')", one_of("ac", "wa", "none")("wa"), True),
    ("one_of('ac','wa','none')('run') 旧字典", one_of("ac", "wa", "none")("run"), False),
    ("one_of(QUEUED..)('run')", one_of("QUEUED", "RUNNING", "EXITED", "KILLED")("run"), False),
    ("is_list([])", is_list([]), True), ("is_list('a')", is_list("a"), False),
]
for label, got, want in checks:
    ok = got == want
    print(("PASS " if ok else "FAIL ") + "%s = %r（期望 %r）" % (label, got, want))
    if not ok:
        failures.append("%s" % label)

print("\n" + ("全部通过" if not failures else "失败 %d 条: %r" % (len(failures), failures)))
sys.exit(0 if not failures else 1)
