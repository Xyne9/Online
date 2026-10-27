# -*- coding: utf-8 -*-
"""go-judge 第五轮：最终锁定 JudgeAdapter 要用的三条协议。

已确认：
  · workdir=/w；/ 只读，/tmp 可写
  · 一次请求内多 cmd **不共享文件系统**
  · `files` 是**位置语义**：第 0 项 = stdin，第 1 项 = stdout，第 2 项 = stderr；
    `name` 只是响应里的键名，不决定 fd
  · copyOut 可用（相对 /w 或绝对路径），返回 {文件名: 内容}
  · copyIn 是 map：{"dst路径": {"content": "..."}}

本轮验证：
  A. files 位置语义的最终确认（带 stdin 读入）
  B. copyIn by fileId 是否可用（copyOutCached 的 fileId 能不能跨请求）
  C. 若 B 不可用 → base64 往返方案是否可行（编译产物 base64 → copyIn content → 解码执行）
  D. 单 cmd 内跑多测试点（备选方案）的可行性与限值精度

用法：python scripts/_probe_gojudge5.py
"""
import json
import sys
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:5050"
ENV = ["PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"]


def run(body, timeout=180):
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(BASE + "/run", data=data,
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
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


STD = [{"name": "stdout", "max": 1 << 20}, {"name": "stderr", "max": 1 << 20}]
OK = []


def check(n, cond, d=""):
    OK.append((n, bool(cond), d))
    print(f"[{'PASS' if cond else 'FAIL'}] {n}" + (f"  —— {d}" if d else ""))


def show(t, r):
    print(f"\n--- {t} ---")
    print(json.dumps(r, ensure_ascii=False, indent=1)[:1500])


# ── A. files 位置语义最终确认 ────────────────────────────────────────
show("A1 [content(stdin), name=stdout, name=stderr] 读 stdin", run({"cmd": [c(
    ["/bin/sh", "-c", "read a b; echo sum=$((a+b)); echo err-line 1>&2"],
    [{"content": "12 34\n"}, {"name": "stdout", "max": 65536}, {"name": "stderr", "max": 65536}])]}))
r = run({"cmd": [c(["/bin/sh", "-c", "read a b; echo sum=$((a+b)); echo err-line 1>&2"],
                   [{"content": "12 34\n"}, {"name": "stdout", "max": 65536},
                    {"name": "stderr", "max": 65536}])]})[0]
f = r.get("files") or {}
check("A1 stdout=sum=46", f.get("stdout", "").strip() == "sum=46", repr(f.get("stdout")))
check("A1 stderr=err-line", f.get("stderr", "").strip() == "err-line", repr(f.get("stderr")))
check("A1 exitStatus=0", r.get("exitStatus") == 0, str(r.get("exitStatus")))

# ── B. copyIn by fileId ────────────────────────────────────────────
r1 = run({"cmd": [c(["/bin/sh", "-c",
                     "cat > ./m.cpp <<'EOF'\n#include <iostream>\nint main(){long long a,b;std::cin>>a>>b;std::cout<<a+b<<'\\n';}\nEOF\n"
                     "g++ -O2 -std=c++17 -o ./m ./m.cpp && echo BUILT"],
                    [{"name": "stdout", "max": 65536}, {"name": "stderr", "max": 65536}],
                    cpu=20_000_000_000, clock=60_000_000_000, mem=1024 * 1024 * 1024,
                    copyOutCached=["m"])]})
show("B1 编译 + copyOutCached", r1)
fid = (r1[0].get("fileIds") or {}).get("m")
print("fileId =", fid)

b_ok = False
if fid:
    r2 = run({"cmd": [c(["/bin/sh", "-c", "chmod +x ./m2 && ./m2"],
                        [{"content": "12 34\n"}, {"name": "stdout", "max": 65536},
                         {"name": "stderr", "max": 65536}],
                        copyIn={"m2": {"src": fid}})]})
    show("B2 copyIn by fileId（相对 dst）", r2)
    b_ok = (r2[0].get("status") == "Accepted" and
            ((r2[0].get("files") or {}).get("stdout", "").strip() == "46"))
    check("B2 copyIn by fileId 可用", b_ok, str(r2[0].get("status")) + " " + str(r2[0].get("error", "")))

# ── C. base64 往返（B 不可用时的兜底） ────────────────────────────
if not b_ok:
    r1 = run({"cmd": [c(["/bin/sh", "-c",
                         "cat > ./m.cpp <<'EOF'\n#include <iostream>\nint main(){long long a,b;std::cin>>a>>b;std::cout<<a+b<<'\\n';}\nEOF\n"
                         "g++ -O2 -std=c++17 -o ./m ./m.cpp && base64 -w0 ./m > ./m.b64 && wc -c ./m.b64"],
                        STD, cpu=20_000_000_000, clock=60_000_000_000,
                        mem=1024 * 1024 * 1024, copyOut=["m.b64"])]})
    show("C1 编译产物 base64 取出", r1)
    b64 = (r1[0].get("files") or {}).get("m.b64", "")
    print("base64 长度 =", len(b64))
    if b64:
        r2 = run({"cmd": [c(["/bin/sh", "-c",
                             "base64 -d ./m.b64 > ./m2 && chmod +x ./m2 && ./m2"],
                            [{"content": "12 34\n"}, {"name": "stdout", "max": 65536},
                             {"name": "stderr", "max": 65536}],
                            copyIn={"m.b64": {"content": b64}})]})
        show("C2 base64 解码后执行", r2)
        c_ok = ((r2[0].get("files") or {}).get("stdout", "").strip() == "46")
        check("C2 base64 往返可执行", c_ok,
              str(r2[0].get("status")) + " out=" + repr((r2[0].get("files") or {}).get("stdout")))

# ── D. 单 cmd 内跑多测试点（同环境，一次编译） ─────────────────────
script = r"""
cat > ./m.cpp <<'EOF'
#include <iostream>
int main(){long long a,b;std::cin>>a>>b;std::cout<<a+b<<std::endl;}
EOF
g++ -O2 -std=c++17 -o ./m ./m.cpp || { echo COMPILE_FAIL; exit 1; }
i=0
for t in /w/tc/*.in; do
  i=$((i+1))
  s=$(date +%s%N)
  out=$(./m < "$t" 2>&1)
  e=$(date +%s%N)
  echo "###CASE $i"
  echo "###TIME $(( (e-s)/1000000 ))"
  echo "###OUT $out"
done
"""
r = run({"cmd": [c(["/bin/sh", "-c", script], STD,
                   cpu=20_000_000_000, clock=60_000_000_000, mem=1024 * 1024 * 1024,
                   copyIn={"/w/tc/1.in": {"content": "12 34\n"},
                           "/w/tc/2.in": {"content": "-5 8\n"}})]})
show("D 单 cmd 多测试点", r)
out = (r[0].get("files") or {}).get("stdout", "") or (r[0].get("files") or {}).get("stderr", "")
check("D 单 cmd 内多测试点可跑", "###CASE 1" in out and "###CASE 2" in out, repr(out[:300]))

# ── 汇总 ──────────────────────────────────────────────────────────
p = sum(1 for _, o, _ in OK if o)
print(f"\n{'=' * 60}\nRESULT: {p}/{len(OK)} 通过")
for n, o, d in OK:
    if not o:
        print(f"  [FAIL] {n} ({d})")
print("\n结论：copyIn by fileId =", "可用" if b_ok else "不可用 → 走 base64 或单 cmd 方案")
sys.exit(0 if p == len(OK) else 1)
