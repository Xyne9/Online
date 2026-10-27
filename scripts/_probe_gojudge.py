# -*- coding: utf-8 -*-
"""go-judge /run 协议深度探测（Day4 判题服务开工前的事实确认）。

目的：把 JudgeAdapter 需要依赖的**全部**协议事实一次性测准，避免边写边猜：
  1. `cmd` 数组是否在同一环境内**顺序执行**、文件系统是否共享（决定「编译一次 + 跑 N 个测试点」能否成立）
  2. 响应是数组还是单对象；每个 cmd 的结果如何对应
  3. `status` 的**精确字符串**：AC / TLE / MLE / RE(非零退出) / 编译失败 各是什么
  4. `time` / `memory` 的单位与语义（ns / byte）
  5. `files` 中带 name 的条目如何回传 stdout/stderr；无 name 条目如何作为 stdin
  6. `max` 截断行为
  7. 编译失败的判定口径（exitStatus != 0 + stderr）

用法：python scripts/_probe_gojudge.py
退出码 0 = 全部断言通过。
"""
import json
import sys
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:5050"
ENV = ["PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"]
RESULTS = []


def post(path, body, timeout=120):
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        BASE + path, data=data, headers={"Content-Type": "application/json"}, method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        return {"__http_error__": exc.code, "body": exc.read().decode("utf-8", "replace")}


def check(name, cond, detail=""):
    RESULTS.append((name, bool(cond), detail))
    print(f"[{'PASS' if cond else 'FAIL'}] {name}" + (f"  —— {detail}" if detail else ""))


def norm(res):
    """go-judge 老版本返回数组、v1.13 返回单对象（多 cmd 时必然是数组）。统一成 list。"""
    return res if isinstance(res, list) else [res]


def run(cmds):
    return norm(post("/run", {"cmd": cmds}))


def cmd(args, files=None, cpu=10_000_000_000, clock=30_000_000_000,
        mem=256 * 1024 * 1024, stack=268_435_456, proc=64):
    c = {
        "args": args,
        "env": ENV,
        "cpuLimit": cpu,
        "clockLimit": clock,
        "memoryLimit": mem,
        "stackLimit": stack,
        "procLimit": proc,
    }
    if files is not None:
        c["files"] = files
    return c


def stdout_of(r):
    f = r.get("files") or {}
    return f.get("stdout", r.get("stdout", "")) or ""


def stderr_of(r):
    f = r.get("stderr") or {}
    if isinstance(f, dict):
        f = f.get("stderr")
    return (r.get("files") or {}).get("stderr", r.get("stderr", "")) or ""


# ─────────────────────────────────────────────────────────────────────
# 探测 1：多 cmd 是否共享文件系统 + 顺序执行
# ─────────────────────────────────────────────────────────────────────
print("\n=== 探测 1：多 cmd 顺序执行 / 文件系统共享 ===")
res = run([
    cmd(["/bin/sh", "-c", "mkdir -p /w && echo 'compiled-artifact' > /w/a.txt && echo STEP1"],
        files=[{"name": "stdout", "max": 4096}]),
    cmd(["/bin/sh", "-c", "cat /w/a.txt && echo STEP2"],
        files=[{"name": "stdout", "max": 4096}]),
])
print(json.dumps(res, ensure_ascii=False)[:800])
check("多 cmd 返回数组且长度=2", len(res) == 2, f"len={len(res)}")
if len(res) == 2:
    o0, o1 = stdout_of(res[0]), stdout_of(res[1])
    check("cmd[0] 产出 STEP1", "STEP1" in o0, repr(o0[:80]))
    check("cmd[1] 能看到 cmd[0] 写的文件（文件系统共享）", "compiled-artifact" in o1, repr(o1[:120]))
    check("cmd[1] 的 stdout 只含自己的输出（不串流）", "STEP1" not in o1, repr(o1[:120]))

# ─────────────────────────────────────────────────────────────────────
# 探测 2：stdin（无 name 的 files 条目）+ 三语言编译运行闭环
# ─────────────────────────────────────────────────────────────────────
print("\n=== 探测 2：编译一次 + 多测试点跑（真实三语言） ===")
SRC_CPP = "#include <iostream>\nint main(){long long a,b;std::cin>>a>>b;std::cout<<a+b<<std::endl;}\n"
res = run([
    cmd(["/bin/sh", "-c",
         "cat > /tmp/m.cpp <<'OJEOF'\n" + SRC_CPP + "OJEOF\n"
         "g++ -O2 -std=c++17 -o /tmp/m /tmp/m.cpp"],
        files=[{"name": "stdout", "max": 65536}, {"name": "stderr", "max": 65536}],
        cpu=20_000_000_000, clock=60_000_000_000, mem=512 * 1024 * 1024),
    cmd(["/tmp/m"], files=[{"content": "12 34\n"}, {"name": "stdout", "max": 65536},
                           {"name": "stderr", "max": 65536}]),
    cmd(["/tmp/m"], files=[{"content": "-5 8\n"}, {"name": "stdout", "max": 65536},
                           {"name": "stderr", "max": 65536}]),
])
print(json.dumps(res, ensure_ascii=False)[:1200])
check("C++ 三 cmd 返回 3 条", len(res) == 3, f"len={len(res)}")
if len(res) == 3:
    check("编译 cmd status=Accepted", res[0].get("status") == "Accepted", str(res[0].get("status")))
    check("测试点1 输出 46", stdout_of(res[1]).strip() == "46", repr(stdout_of(res[1])))
    check("测试点2 输出 3", stdout_of(res[2]).strip() == "3", repr(stdout_of(res[2])))
    check("每条 cmd 各自回 time(ns)>0", isinstance(res[1].get("time"), int) and res[1]["time"] > 0,
          str(res[1].get("time")))
    check("每条 cmd 各自回 memory(byte)>0", isinstance(res[1].get("memory"), int) and res[1]["memory"] > 0,
          str(res[1].get("memory")))

# ─────────────────────────────────────────────────────────────────────
# 探测 3：编译失败（CE）的精确表现
# ─────────────────────────────────────────────────────────────────────
print("\n=== 探测 3：编译失败（CE） ===")
res = run([
    cmd(["/bin/sh", "-c",
         "cat > /tmp/bad.cpp <<'OJEOF'\nint main(){ this is not cpp }\nOJEOF\n"
         "g++ -O2 -std=c++17 -o /tmp/bad /tmp/bad.cpp"],
        files=[{"name": "stdout", "max": 65536}, {"name": "stderr", "max": 65536}],
        cpu=20_000_000_000, clock=60_000_000_000, mem=512 * 1024 * 1024),
])
print(json.dumps(res, ensure_ascii=False)[:900])
if res:
    r = res[0]
    check("编译失败 status != Accepted", r.get("status") != "Accepted", str(r.get("status")))
    check("编译失败 exitStatus != 0", r.get("exitStatus") not in (0, None), str(r.get("exitStatus")))
    check("编译失败 stderr 非空（可作 CE 文案）", len(stderr_of(r)) > 0, repr(stderr_of(r)[:100]))

# ─────────────────────────────────────────────────────────────────────
# 探测 4：TLE / MLE / RE 的精确状态串
# ─────────────────────────────────────────────────────────────────────
print("\n=== 探测 4：TLE / MLE / RE 状态串 ===")
# TLE：死循环，cpu 限 0.2s
res = run([cmd(["/bin/sh", "-c", "while :; do :; done"],
               files=[{"name": "stdout", "max": 1024}, {"name": "stderr", "max": 1024}],
               cpu=200_000_000, clock=2_000_000_000)])
print("TLE ->", json.dumps(res, ensure_ascii=False)[:400])
check("死循环 status 含 Time Limit", res and "Time Limit" in str(res[0].get("status")),
      str(res[0].get("status") if res else None))

# MLE：分配 1GB，限制 64MB
res = run([cmd(["/bin/sh", "-c",
                "python3 -c \"x=bytearray(1024*1024*1024)\" 2>/dev/null || /bin/true"],
               files=[{"name": "stdout", "max": 1024}, {"name": "stderr", "max": 1024}],
               mem=64 * 1024 * 1024)])
print("MLE ->", json.dumps(res, ensure_ascii=False)[:400])
check("超内存 status 含 Memory Limit", res and "Memory Limit" in str(res[0].get("status")),
      str(res[0].get("status") if res else None))

# RE：非零退出
res = run([cmd(["/bin/sh", "-c", "exit 3"],
               files=[{"name": "stdout", "max": 1024}, {"name": "stderr", "max": 1024}])])
print("RE ->", json.dumps(res, ensure_ascii=False)[:400])
check("非零退出 status 含 Nonzero Exit", res and "Nonzero" in str(res[0].get("status")),
      str(res[0].get("status") if res else None))
check("非零退出 exitStatus=3", res and res[0].get("exitStatus") == 3, str(res[0].get("exitStatus") if res else None))

# ─────────────────────────────────────────────────────────────────────
# 探测 5：输出截断（max）
# ─────────────────────────────────────────────────────────────────────
print("\n=== 探测 5：stdout 截断（max） ===")
res = run([cmd(["/bin/sh", "-c", "yes 1234567890 | head -c 100000"],
               files=[{"name": "stdout", "max": 1024}, {"name": "stderr", "max": 1024}])])
if res:
    out = stdout_of(res[0])
    check("max=1024 时 stdout 被截断", len(out) <= 1024, f"len={len(out)}")
    check("截断后仍 status=Accepted", res[0].get("status") == "Accepted", str(res[0].get("status")))

# ─────────────────────────────────────────────────────────────────────
# 探测 6：Java / Python 在同一次请求内编译+跑
# ─────────────────────────────────────────────────────────────────────
print("\n=== 探测 6：Java / Python 同一请求内编译+跑 ===")
JAVA_SRC = ("import java.util.*;\npublic class Main{public static void main(String[] a){"
            "Scanner s=new Scanner(System.in);System.out.println(s.nextLong()+s.nextLong());}}\n")
res = run([
    cmd(["/bin/sh", "-c",
         "mkdir -p /tmp/j && cat > /tmp/j/Main.java <<'OJEOF'\n" + JAVA_SRC + "OJEOF\n"
         "javac -encoding UTF-8 -d /tmp/j /tmp/j/Main.java"],
        files=[{"name": "stdout", "max": 65536}, {"name": "stderr", "max": 65536}],
        cpu=30_000_000_000, clock=90_000_000_000, mem=1024 * 1024 * 1024, proc=256),
    cmd(["/bin/sh", "-c", "cd /tmp/j && java -Xss256m Main"],
        files=[{"content": "12 34\n"}, {"name": "stdout", "max": 65536}, {"name": "stderr", "max": 65536}],
        cpu=10_000_000_000, clock=30_000_000_000, mem=1024 * 1024 * 1024, proc=256),
])
print(json.dumps(res, ensure_ascii=False)[:900])
if len(res) == 2:
    check("Java 编译 Accepted", res[0].get("status") == "Accepted", str(res[0].get("status")))
    check("Java 运行输出 46", stdout_of(res[1]).strip() == "46", repr(stdout_of(res[1])[:100]))

PY_SRC = "a,b=map(int,input().split())\nprint(a+b)\n"
res = run([
    cmd(["/bin/sh", "-c", "cat > /tmp/m.py <<'OJEOF'\n" + PY_SRC + "OJEOF\npython3 -m py_compile /tmp/m.py"],
        files=[{"name": "stdout", "max": 65536}, {"name": "stderr", "max": 65536}],
        cpu=20_000_000_000, clock=60_000_000_000, mem=512 * 1024 * 1024),
    cmd(["/bin/sh", "-c", "python3 /tmp/m.py"],
        files=[{"content": "12 34\n"}, {"name": "stdout", "max": 65536}, {"name": "stderr", "max": 65536}],
        cpu=10_000_000_000, clock=30_000_000_000, mem=512 * 1024 * 1024),
])
print(json.dumps(res, ensure_ascii=False)[:900])
if len(res) == 2:
    check("Python 语法检查 Accepted", res[0].get("status") == "Accepted", str(res[0].get("status")))
    check("Python 运行输出 46", stdout_of(res[1]).strip() == "46", repr(stdout_of(res[1])[:100]))

# ─────────────────────────────────────────────────────────────────────
# 探测 7：cpuLimit / clockLimit 单位确认（ns）
# ─────────────────────────────────────────────────────────────────────
print("\n=== 探测 7：限值单位（ns）与 time 单位 ===")
res = run([cmd(["/bin/sh", "-c", "sleep 0.5"],
               files=[{"name": "stdout", "max": 1024}, {"name": "stderr", "max": 1024}],
               cpu=5_000_000_000, clock=10_000_000_000)])
if res:
    t = res[0].get("time")
    # sleep 0.5s 的 cpu time 接近 0（sleep 不耗 CPU），用 wall clock 无法从 time 读；
    # 这里只断言 time 是纳秒量级（< 10^10），且 memory 是字节量级（> 10^5）
    check("time 是纳秒量级（< 10s = 1e10）", isinstance(t, int) and 0 <= t < 10_000_000_000, str(t))
    m = res[0].get("memory")
    check("memory 是字节量级（> 100KB）", isinstance(m, int) and m > 100_000, str(m))
    check("sleep 0.5 在 clockLimit=10s 内完成", res[0].get("status") == "Accepted", str(res[0].get("status")))

# ─────────────────────────────────────────────────────────────────────
# 汇总
# ─────────────────────────────────────────────────────────────────────
passed = sum(1 for _, ok, _ in RESULTS if ok)
total = len(RESULTS)
print(f"\n{'=' * 60}\nRESULT: {passed}/{total} 通过")
if passed != total:
    print("失败项：")
    for n, ok, d in RESULTS:
        if not ok:
            print(f"  - {n}  ({d})")
sys.exit(0 if passed == total else 1)
