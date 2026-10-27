# -*- coding: utf-8 -*-
"""go-judge 第二轮探测：cmd 之间**不共享文件系统**（第一轮已证），
因此必须确认 copyOut / copyIn 的可用性，才能实现「编译一次 → 跑 N 个测试点」。

同时确认：
  · 单 cmd 内 echo 的 stdout 回传（第一轮因 mkdir 失败短路，未测到）
  · copyOut 的返回形态（是文件内容还是 fileId）
  · copyIn 的入参形态（content / fileId / name）
  · 编译产物（可执行二进制）能否经 copyOut→copyIn 往返

用法：python scripts/_probe_gojudge2.py
"""
import base64
import json
import sys
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:5050"
ENV = ["PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"]
RESULTS = []


def post(path, body, timeout=180):
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
    return res if isinstance(res, list) else [res]


def cmd(args, files=None, **kw):
    c = {
        "args": args, "env": ENV,
        "cpuLimit": kw.get("cpu", 10_000_000_000),
        "clockLimit": kw.get("clock", 30_000_000_000),
        "memoryLimit": kw.get("mem", 256 * 1024 * 1024),
        "stackLimit": kw.get("stack", 268_435_456),
        "procLimit": kw.get("proc", 64),
    }
    if files is not None:
        c["files"] = files
    if kw.get("copyIn"):
        c["copyIn"] = kw["copyIn"]
    if kw.get("copyOut"):
        c["copyOut"] = kw["copyOut"]
    if kw.get("copyOutCached"):
        c["copyOutCached"] = kw["copyOutCached"]
    return c


def out_of(r, key="stdout"):
    f = r.get("files") or {}
    return f.get(key, "") or ""


# ─────────────────────────────────────────────────────────────────────
print("\n=== 1. 单 cmd 的 stdout 回传 ===")
res = norm(post("/run", {"cmd": [cmd(["/bin/sh", "-c", "echo hello-stdout; echo hello-stderr >&2"],
                                     files=[{"name": "stdout", "max": 65536},
                                            {"name": "stderr", "max": 65536}])]}))
print(json.dumps(res, ensure_ascii=False)[:500])
check("stdout 回传 hello-stdout", "hello-stdout" in out_of(res[0]), repr(out_of(res[0])))
check("stderr 回传 hello-stderr", "hello-stderr" in out_of(res[0], "stderr"), repr(out_of(res[0], "stderr")))

# ─────────────────────────────────────────────────────────────────────
print("\n=== 2. copyOutCached：编译产物取出并拿到 fileId ===")
res = norm(post("/run", {"cmd": [cmd(
    ["/bin/sh", "-c",
     "cat > /tmp/m.cpp <<'OJEOF'\n"
     "#include <iostream>\nint main(){long long a,b;std::cin>>a>>b;std::cout<<a+b<<std::endl;}\n"
     "OJEOF\n"
     "g++ -O2 -std=c++17 -o /tmp/m /tmp/m.cpp && ls -l /tmp/m"],
    files=[{"name": "stdout", "max": 65536}, {"name": "stderr", "max": 65536}],
    cpu=20_000_000_000, clock=60_000_000_000, mem=1024 * 1024 * 1024,
    copyOutCached=["m"],
)]}))
print(json.dumps(res, ensure_ascii=False)[:800])
check("编译 Accepted", res[0].get("status") == "Accepted", str(res[0].get("status")))
file_ids = res[0].get("fileIds") or {}
print("fileIds =", file_ids)
check("copyOutCached 返回 fileIds.m", "m" in file_ids, str(list(file_ids.keys())))
check("stdout 含 ls 结果（证明二进制存在）", "m" in out_of(res[0]), repr(out_of(res[0])[:120]))

mid = file_ids.get("m")

# ─────────────────────────────────────────────────────────────────────
print("\n=== 3. copyIn fileId：把二进制带进新环境执行 ===")
if mid:
    res = norm(post("/run", {"cmd": [cmd(
        ["/bin/sh", "-c", "chmod +x /tmp/m2 && /tmp/m2"],
        files=[{"content": "12 34\n"}, {"name": "stdout", "max": 65536},
               {"name": "stderr", "max": 65536}],
        copyIn=[{"src": mid, "dst": "/tmp/m2"}],
    )]}))
    print(json.dumps(res, ensure_ascii=False)[:700])
    check("copyIn fileId 后执行 Accepted", res[0].get("status") == "Accepted", str(res[0].get("status")))
    check("输出 46", out_of(res[0]).strip() == "46", repr(out_of(res[0])))
else:
    check("copyIn fileId 后执行 Accepted", False, "无 fileId，跳过")

# ─────────────────────────────────────────────────────────────────────
print("\n=== 4. copyIn content：直接把源码/输入带进环境 ===")
res = norm(post("/run", {"cmd": [cmd(
    ["/bin/sh", "-c", "cat /w/in.txt"],
    files=[{"name": "stdout", "max": 65536}, {"name": "stderr", "max": 65536}],
    copyIn=[{"content": "12 34\n", "dst": "/w/in.txt"}],
)]}))
print(json.dumps(res, ensure_ascii=False)[:500])
check("copyIn content 写入可读", out_of(res[0]).strip() == "12 34", repr(out_of(res[0])))

# ─────────────────────────────────────────────────────────────────────
print("\n=== 5. 一次请求多个 cmd：确认仍是独立环境（负向验证） ===")
res = norm(post("/run", {"cmd": [
    cmd(["/bin/sh", "-c", "echo A > /tmp/x && cat /tmp/x"],
        files=[{"name": "stdout", "max": 65536}]),
    cmd(["/bin/sh", "-c", "cat /tmp/x 2>&1 || echo MISSING"],
        files=[{"name": "stdout", "max": 65536}]),
]}))
print(json.dumps(res, ensure_ascii=False)[:600])
check("cmd[0] 能读到自己写的 /tmp/x", "A" in out_of(res[0]), repr(out_of(res[0])))
check("cmd[1] 读不到 /tmp/x（环境隔离已证实）", "MISSING" in out_of(res[1]) or "/tmp/x" in out_of(res[1]),
      repr(out_of(res[1])))

# ─────────────────────────────────────────────────────────────────────
print("\n=== 6. copyOut（不缓存）：直接拿回文件内容 ===")
res = norm(post("/run", {"cmd": [cmd(
    ["/bin/sh", "-c", "printf 'line1\\nline2\\n' > /tmp/o.txt"],
    files=[{"name": "stdout", "max": 65536}],
    copyOut=["o.txt"],
)]}))
print(json.dumps(res, ensure_ascii=False)[:600])
fo = res[0].get("files") or {}
check("copyOut 的键是目标文件名 o.txt", "o.txt" in fo, str(list(fo.keys())))
if "o.txt" in fo:
    check("copyOut 内容正确", fo["o.txt"] == "line1\nline2\n", repr(fo["o.txt"]))

# ─────────────────────────────────────────────────────────────────────
print("\n=== 7. max 足够大时不 OLE；超限才是 OLE ===")
res = norm(post("/run", {"cmd": [cmd(
    ["/bin/sh", "-c", "yes x | head -c 5000"],
    files=[{"name": "stdout", "max": 10 * 1024 * 1024}])]}))
check("5000B 输出 + max=10MB → Accepted", res[0].get("status") == "Accepted",
      f"{res[0].get('status')} len={len(out_of(res[0]))}")
check("5000B 完整回传", len(out_of(res[0])) == 5000, f"len={len(out_of(res[0]))}")

# ─────────────────────────────────────────────────────────────────────
print("\n=== 8. 编译产物大小上限（copyOutCached 是否支持大二进制） ===")
res = norm(post("/run", {"cmd": [cmd(
    ["/bin/sh", "-c",
     "cat > /tmp/big.cpp <<'OJEOF'\n"
     "#include <bits/stdc++.h>\nusing namespace std;\nint main(){vector<int> v(100);iota(v.begin(),v.end(),0);"
     "cout<<accumulate(v.begin(),v.end(),0)<<endl;}\nOJEOF\n"
     "g++ -O2 -std=c++17 -o /tmp/big /tmp/big.cpp && stat -c %s /tmp/big"],
    files=[{"name": "stdout", "max": 65536}, {"name": "stderr", "max": 65536}],
    cpu=30_000_000_000, clock=90_000_000_000, mem=1024 * 1024 * 1024,
    copyOutCached=["big"],
)]}))
print(json.dumps(res, ensure_ascii=False)[:600])
check("含 bits/stdc++ 的 C++ 编译 Accepted", res[0].get("status") == "Accepted", str(res[0].get("status")))
if res[0].get("fileIds", {}).get("big"):
    res2 = norm(post("/run", {"cmd": [cmd(
        ["/bin/sh", "-c", "chmod +x /tmp/big && /tmp/big"],
        files=[{"name": "stdout", "max": 65536}],
        copyIn=[{"src": res[0]["fileIds"]["big"], "dst": "/tmp/big"}],
        mem=1024 * 1024 * 1024, cpu=10_000_000_000, clock=30_000_000_000,
    )]}))
    check("大二进制 copyIn 后可执行且输出 4950", out_of(res2[0]).strip() == "4950",
          repr(out_of(res2[0])[:100]) + " status=" + str(res2[0].get("status")))

# ─────────────────────────────────────────────────────────────────────
passed = sum(1 for _, ok, _ in RESULTS if ok)
total = len(RESULTS)
print(f"\n{'=' * 60}\nRESULT: {passed}/{total} 通过")
for n, ok, d in RESULTS:
    if not ok:
        print(f"  [FAIL] {n}  ({d})")
sys.exit(0 if passed == total else 1)
