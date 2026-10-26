# -*- coding: utf-8 -*-
"""go-judge 判题沙箱功能冒烟测试。

对应《从 0 到 1 的 6 步手册》第 1 步：go-judge 不只是端口活着，还要真的能编译、能跑、能拿到 stdout。

⚠ 协议坑（本机实测踩到，务必牢记）：
  `cpuLimit / clockLimit / memoryLimit / procLimit / stackLimit` 必须写在**每个 cmd 对象内部**。
  写到请求顶层会被静默忽略 → procLimit 取默认值 0 → go-judge 把目标 cgroup 的 pids.max 设为 0
  → clone3(CLONE_INTO_CGROUP) 返回 EAGAIN，接口表现为
  `{"status":"Internal Error","error":"execve: start: /bin/xxx: clone: resource temporarily unavailable"}`。
  这个报错看起来像宿主机资源不足，实际是参数位置写错了。

请求体（v1.13）：
  {"cmd":[{"args":[...],"env":[...],"files":[{"content":"..."}],"cpuLimit":ns,"clockLimit":ns,
           "memoryLimit":bytes,"stackLimit":bytes,"procLimit":n}]}
  files 中不带 name 的条目作为 stdin。
响应：本机版本返回**单个对象**（老版本返回数组），下面两种都兼容。

用法：python docker/judge/smoke_test.py [http://127.0.0.1:5050]
退出码：0 全部通过；1 有失败。
"""
import json
import sys
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:5050"
RUN_URL = BASE + "/run"
ENV = ["PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"]

# 三语言 A+B：输入 "12 34" → 期望输出 "46"。
# 统一用 /bin/sh -c 串「写源码 → 编译 → 运行」，单次请求内闭环。
CASES = [
    (
        "C++17",
        r"""
cat > /tmp/main.cpp <<'EOF'
#include <iostream>
int main(){ long long a,b; std::cin>>a>>b; std::cout<<a+b<<std::endl; return 0; }
EOF
g++ -O2 -std=c++17 -o /tmp/main /tmp/main.cpp && /tmp/main
""",
        "46",
        1024,
    ),
    (
        "Java17",
        r"""
mkdir -p /tmp/j
cat > /tmp/j/Main.java <<'EOF'
import java.util.*;
public class Main { public static void main(String[] a){ Scanner s=new Scanner(System.in); System.out.println(s.nextLong()+s.nextLong()); } }
EOF
javac -d /tmp/j /tmp/j/Main.java && java -cp /tmp/j Main
""",
        "46",
        2048,
    ),
    (
        "Python3",
        r"""
cat > /tmp/main.py <<'EOF'
a, b = map(int, input().split())
print(a + b)
EOF
python3 /tmp/main.py
""",
        "46",
        512,
    ),
]


def post(body):
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        RUN_URL, data=data, headers={"Content-Type": "application/json"}, method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"HTTP {exc.code}: {exc.read().decode('utf-8', 'replace')}") from exc


def main():
    ok = True
    for name, script, expect, mem_mb in CASES:
        # 关键：所有 limit 都在 cmd 对象内部
        body = {
            "cmd": [
                {
                    "args": ["/bin/sh", "-c", script],
                    "env": ENV,
                    # files 语义（v1.13）：
                    #   不带 name 的条目 = 标准输入；
                    #   带 name 的条目 = 把标准输出/错误重定向到该文件并回传（旧版语法就叫 name:"stdout"）。
                    "files": [
                        {"content": "12 34\n"},                 # stdin
                        {"name": "stdout", "max": 65536},       # 捕获 stdout
                        {"name": "stderr", "max": 65536},       # 捕获 stderr
                    ],
                    "cpuLimit": 10_000_000_000,        # 10s CPU
                    "clockLimit": 90_000_000_000,      # 90s 墙钟（首次编译/JVM 启动余量）
                    "memoryLimit": mem_mb * 1024 * 1024,
                    "stackLimit": 268_435_456,
                    "procLimit": 64,
                }
            ]
        }
        try:
            res = post(body)
        except Exception as exc:  # noqa: BLE001
            print(f"[FAIL] {name}: 请求失败 {exc}")
            ok = False
            continue
        r = res[0] if isinstance(res, list) else res
        # 本机 v1.13 把 stdout/stderr 放在 files 子对象里：{"files":{"stdout":...,"stderr":...}}
        files = r.get("files") or {}
        out = (files.get("stdout") or r.get("stdout") or "").strip()
        stderr = (files.get("stderr") or r.get("stderr") or "").strip()
        status = r.get("status")
        good = out == expect and status == "Accepted"
        print(
            f"[{'PASS' if good else 'FAIL'}] {name}: stdout={out!r} expect={expect!r} "
            f"status={status} time={r.get('time')}ns mem={r.get('memory')}B exit={r.get('exitStatus')}"
        )
        if stderr:
            print(f"       stderr: {stderr[:600]}")
        if r.get("error"):
            print(f"       error: {r['error']}")
        ok = ok and good
    print("RESULT:", "ALL PASS" if ok else "HAS FAILURE")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
