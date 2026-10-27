# -*- coding: utf-8 -*-
"""go-judge 第四轮：锁定 stdout/stderr 回传口径与 copyIn/copyOutCached 用法。

已确认的事实（前三轮）：
  · workdir = /w，根目录只读，/tmp 可写
  · 一次请求内多个 cmd **不共享文件系统**（各自独立环境）
  · copyOut 相对路径以 /w 为基准，也可用绝对路径
  · copyOutCached 返回 {"fileIds": {"m": "AFIZEKXZ"}}
  · copyIn 是 **map** 不是数组：{"dst": {"src": "fileId"}} 或 {"dst": {"content": "..."}}

本轮要解决：进程 stdout 到底落在响应 files 的哪个 key。
"""
import json
import sys
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:5050"
ENV = ["PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"]


def run(body):
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(BASE + "/run", data=data,
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            r = json.loads(resp.read().decode("utf-8"))
            return r if isinstance(r, list) else [r]
    except urllib.error.HTTPError as exc:
        return [{"__http_error__": exc.code, "body": exc.read().decode("utf-8", "replace")}]


def c(args, files=None, **kw):
    d = {"args": args, "env": ENV,
         "cpuLimit": kw.get("cpu", 10_000_000_000),
         "clockLimit": kw.get("clock", 30_000_000_000),
         "memoryLimit": kw.get("mem", 256 * 1024 * 1024),
         "stackLimit": 268_435_456, "procLimit": kw.get("proc", 64)}
    if files is not None:
        d["files"] = files
    for k in ("copyIn", "copyOut", "copyOutCached"):
        if k in kw:
            d[k] = kw[k]
    return d


def show(tag, res):
    print(f"\n--- {tag} ---")
    print(json.dumps(res, ensure_ascii=False, indent=1)[:1400])


# A. 只声明一个 name=stdout 的条目
show("A 只有 name=stdout", run({"cmd": [c(
    ["/bin/sh", "-c", "echo ONLY-STDOUT"], [{"name": "stdout", "max": 1 << 20}])]}))

# B. 只声明一个 name=stderr 的条目
show("B 只有 name=stderr", run({"cmd": [c(
    ["/bin/sh", "-c", "echo ONLY-STDOUT"], [{"name": "stderr", "max": 1 << 20}])]}))

# C. 顺序颠倒：[stderr, stdout]
show("C 顺序 [stderr, stdout]", run({"cmd": [c(
    ["/bin/sh", "-c", "echo S1; echo E1 1>&2"], [{"name": "stderr", "max": 1 << 20},
                                                  {"name": "stdout", "max": 1 << 20}])]}))

# D. 自定义名字 [out.txt]
show("D 自定义 name=out.txt", run({"cmd": [c(
    ["/bin/sh", "-c", "echo CUSTOM-NAME"], [{"name": "out.txt", "max": 1 << 20}])]}))

# E. 官方 smoke_test 的确切写法
show("E smoke_test 写法（stdout+stderr 两个）", run({"cmd": [c(
    ["/bin/sh", "-c", "echo TO-STDOUT; echo TO-STDERR 1>&2"],
    [{"name": "stdout", "max": 65536}, {"name": "stderr", "max": 65536}])]}))

# F. copyIn 正确形态：map {dst: {content}}
show("F copyIn map + content", run({"cmd": [c(
    ["/bin/sh", "-c", "cat /w/in.txt"],
    [{"name": "stdout", "max": 1 << 20}],
    copyIn={"/w/in.txt": {"content": "12 34\n"}})]}))

# G. 编译 → copyOutCached → 用 fileId 跑（两步）
r1 = run({"cmd": [c(
    ["/bin/sh", "-c",
     "cat > ./m.cpp <<'EOF'\n#include <iostream>\nint main(){long long a,b;std::cin>>a>>b;std::cout<<a+b<<'\\n';}\nEOF\n"
     "g++ -O2 -std=c++17 -o ./m ./m.cpp && echo BUILT"],
    [{"name": "stdout", "max": 65536}, {"name": "stderr", "max": 65536}],
    cpu=20_000_000_000, clock=60_000_000_000, mem=1024 * 1024 * 1024,
    copyOutCached=["m"])]})
show("G1 编译 + copyOutCached", r1)
fid = (r1[0].get("fileIds") or {}).get("m")
print("FILE ID =", fid)

if fid:
    r2 = run({"cmd": [c(
        ["/bin/sh", "-c", "chmod +x /w/m2 && /w/m2"],
        [{"name": "stdout", "max": 65536}, {"name": "stderr", "max": 65536}],
        copyIn={"/w/m2": {"src": fid}})]})
    show("G2 copyIn fileId 执行", r2)

# H. 同一请求内多 cmd，每个都 copyIn 同一 fileId（省一次往返）
if fid:
    r3 = run({"cmd": [
        c(["/bin/sh", "-c", "chmod +x /w/m3 && /w/m3"],
          [{"content": "12 34\n"}, {"name": "stdout", "max": 65536}],
          copyIn={"/w/m3": {"src": fid}}),
        c(["/bin/sh", "-c", "chmod +x /w/m3 && /w/m3"],
          [{"content": "-5 8\n"}, {"name": "stdout", "max": 65536}],
          copyIn={"/w/m3": {"src": fid}}),
    ]})
    show("H 多 cmd 复用同一 fileId", r3)

# I. 超时口径：clockLimit 生效时的状态
show("I clockLimit=1s 的 sleep 2s", run({"cmd": [c(
    ["/bin/sh", "-c", "sleep 2"],
    [{"name": "stdout", "max": 1024}], cpu=5_000_000_000, clock=1_000_000_000)]}))
