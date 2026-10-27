# -*- coding: utf-8 -*-
"""go-judge 第三轮：最小化逐项定位（前两轮出现 stdout/stderr 错位、/tmp 写入失败等异常）。

只回答 5 个问题：
  Q1 workdir 在哪？`/` 可写吗？`/tmp` 可写吗？
  Q2 stdout / stderr 到底怎么取？（name=stdout 的 files 条目 vs 响应顶层）
  Q3 `args` 里的 `-c` 脚本能否正常执行多语句？
  Q4 copyOut / copyIn 的相对路径基准是 workdir 吗？
  Q5 一次请求多 cmd 是否共享环境？

用法：python scripts/_probe_gojudge3.py
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
        with urllib.request.urlopen(req, timeout=120) as resp:
            r = json.loads(resp.read().decode("utf-8"))
            return r if isinstance(r, list) else [r]
    except urllib.error.HTTPError as exc:
        return [{"__http_error__": exc.code, "body": exc.read().decode("utf-8", "replace")}]


def show(tag, res):
    print(f"\n--- {tag} ---")
    print(json.dumps(res, ensure_ascii=False, indent=1)[:1600])


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


BOTH = [{"name": "stdout", "max": 1 << 20}, {"name": "stderr", "max": 1 << 20}]

# Q1 workdir / 可写性
show("Q1 pwd + 根目录可写性 + /tmp 可写性", run({"cmd": [c(
    ["/bin/sh", "-c",
     "echo PWD=$(pwd); "
     "echo ROOT_WRITE=$(touch /root_probe 2>&1 && echo yes || echo no); "
     "echo TMP_WRITE=$(touch /tmp/tmp_probe 2>&1 && echo yes || echo no); "
     "echo WORKDIR_LS=$(ls -a . 2>&1 | tr '\\n' ' ')"],
    BOTH)]}))

# Q2 stdout/stderr 取法
show("Q2 stdout/stderr 分流", run({"cmd": [c(
    ["/bin/sh", "-c", "echo OUT-LINE; echo ERR-LINE 1>&2; exit 0"], BOTH)]}))

# Q2b 不声明 files 时
show("Q2b 不声明 files", run({"cmd": [c(["/bin/sh", "-c", "echo OUT-LINE; echo ERR-LINE 1>&2; exit 0"])]}))

# Q3 多语句脚本
show("Q3 多语句 + && 链", run({"cmd": [c(
    ["/bin/sh", "-c", "mkdir -p /tmp/d && echo written > /tmp/d/f && cat /tmp/d/f && echo DONE"], BOTH)]}))

# Q4 copyOut 相对路径基准
show("Q4 copyOut 相对 workdir", run({"cmd": [c(
    ["/bin/sh", "-c", "echo ARTIFACT > ./art.txt; pwd; ls -l ./art.txt"], BOTH,
    copyOut=["art.txt"])]}))

# Q4b copyOut 绝对路径
show("Q4b copyOut 绝对 /tmp/art2.txt", run({"cmd": [c(
    ["/bin/sh", "-c", "echo ARTIFACT2 > /tmp/art2.txt"], BOTH,
    copyOut=["/tmp/art2.txt"])]}))

# Q5 多 cmd 共享性
show("Q5 多 cmd 共享性（A 写 B 读）", run({"cmd": [
    c(["/bin/sh", "-c", "echo SHARED > ./shared.txt; echo WROTE"], BOTH),
    c(["/bin/sh", "-c", "cat ./shared.txt 2>&1 || echo MISSING"], BOTH),
]}))

# Q6 编译产物 copyOutCached 正确用法（相对 workdir）
show("Q6 编译产物 copyOutCached（相对 workdir）", run({"cmd": [c(
    ["/bin/sh", "-c",
     "cat > ./m.cpp <<'EOF'\n#include <iostream>\nint main(){long long a,b;std::cin>>a>>b;std::cout<<a+b<<'\\n';}\nEOF\n"
     "g++ -O2 -std=c++17 -o ./m ./m.cpp && echo BUILT && ls -l ./m"],
    BOTH, cpu=20_000_000_000, clock=60_000_000_000, mem=1024 * 1024 * 1024,
    copyOutCached=["m"])]}))
