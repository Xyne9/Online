# -*- coding: utf-8 -*-
"""导入后的端到端判题验证。

用真实的题库数据走一遍完整链路：
  DB 取题 → 读 test_case_dir 的磁盘文件 → go-judge 编译运行 → 逐点比对 → 算 OI 分

这一步是「导入成功」和「能用」的分界线。只验证数据库有行数是不够的——
必须证明判题服务按契约能拿到正确的输入输出、按 rtrim 规则比对、按分值累加。

用法：python scripts/verify_import.py [--sample 20]
"""
import argparse
import json
import subprocess
import sys
import urllib.request

GOJUDGE = "http://127.0.0.1:5050"
TESTCASE_HOST = r"D:\毕设\agent oj\data\testcases"
MYSQL_BASE = ["docker", "exec", "-i", "oj-cloud-mysql", "mysql",
              "--default-character-set=utf8mb4", "-uroot", "-proot"]

# 按题目 ID 准备的「必然 AC」解法。选的都是标准 I/O、无 SPJ 的入门题，
# 用来证明数据通路正确，而不是证明题目难度。
SOLUTIONS = {
    "cpp": r'''
#include <bits/stdc++.h>
using namespace std;
int main(){
    long long a, b;
    if (cin >> a >> b) cout << a + b << endl;
    return 0;
}
''',
}


def query(sql, db="oj_cloud"):
    p = subprocess.run(MYSQL_BASE + ["-N", "-B", db],
                       input=sql.encode("utf-8"), capture_output=True)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.decode("utf-8", "replace")[:1000])
    return [l for l in p.stdout.decode("utf-8", "replace").splitlines() if l.strip()]


def run_gojudge(source, cpp=True, stdin_path=None, time_ms=2000, mem_mb=256):
    """调 go-judge 跑一次。stdin 用 copyIn 从 /data/testcases 挂载目录读。"""
    script = (
        "cat > /tmp/m.cpp <<'EOF'\n" + source + "\nEOF\n"
        "g++ -O2 -std=c++17 -o /tmp/m /tmp/m.cpp && /tmp/m"
    )
    cmd = {
        "args": ["/bin/sh", "-c", script],
        "env": ["PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"],
        "files": [{"name": "stdout", "max": 1048576}],
        "cpuLimit": time_ms * 1_000_000,
        "clockLimit": time_ms * 2 * 1_000_000,
        "memoryLimit": mem_mb * 1024 * 1024,
        "procLimit": 64,
    }
    if stdin_path:
        cmd["files"].insert(0, {"src": stdin_path})
    req = {"cmd": [cmd]}
    r = urllib.request.urlopen(urllib.request.Request(
        GOJUDGE + "/run", data=json.dumps(req).encode(),
        headers={"Content-Type": "application/json"}), timeout=120)
    d = json.loads(r.read())
    return d[0] if isinstance(d, list) else d


def rtrim_compare(stdout, expected):
    """契约规定的比对：逐行 stripTrailing（去行尾空格与 \\r），忽略末尾空行。"""
    def norm(s):
        lines = [ln.rstrip(" \t\r") for ln in str(s).replace("\r\n", "\n").split("\n")]
        while lines and lines[-1] == "":
            lines.pop()
        return "\n".join(lines)
    return norm(stdout) == norm(expected)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sample", type=int, default=10, help="抽几道 A+B 类题验证")
    args = ap.parse_args()

    print("=" * 78)
    print("导入后判题链路验证")
    print("=" * 78)

    # 1. 找一个「标准 IO、已发布、有测试点」的题
    rows = query("""
        SELECT q.id, q.title, q.test_case_dir, q.time_limit, q.memory_limit, q.test_case_count
        FROM prob_question q
        WHERE q.state = 1 AND q.test_case_count > 0 AND q.id >= 1004
          AND q.title IN ('A + B 问题')
        ORDER BY q.id LIMIT %d;""" % args.sample)
    if not rows:
        print("[FAIL] 找不到可验证的 A+B 题")
        return 1

    total_pass = total_fail = 0
    for line in rows:
        p = line.split("\t")
        pid, title, tcdir, tl, ml, cnt = p[0], p[1], p[2], p[3], p[4], p[5]
        print("\n题目 #%s %s  (%s 个测试点, %sms/%sMB)" % (pid, title, cnt, tl, ml))

        cases = query("SELECT seq_no, score, input_size, output_size FROM prob_test_case "
                      "WHERE problem_id=%s ORDER BY seq_no;" % pid)
        oi = 0
        all_ok = True
        for c in cases:
            seq, score, isz, osz = c.split("\t")
            in_path = "/data/testcases/%s/%s.in" % (pid, seq)
            out_file = "%s\\%s\\%s.out" % (TESTCASE_HOST, pid, seq)
            try:
                expected = open(out_file, "r", encoding="utf-8", errors="replace").read()
            except OSError as e:
                print("   [FAIL] 测试点 %s 读不到磁盘文件: %s" % (seq, e))
                all_ok = False
                break
            res = run_gojudge(SOLUTIONS["cpp"], stdin_path=in_path,
                              time_ms=int(tl), mem_mb=int(ml))
            status = res.get("status")
            stdout = (res.get("files") or {}).get("stdout", "") or ""
            if status == "Accepted" and rtrim_compare(stdout, expected):
                oi += int(score)
                print("   点%-2s %-10s +%-3s  输出=%r" % (seq, "OK", score, stdout[:20]))
            else:
                all_ok = False
                print("   点%-2s %-10s  输出=%r 期望=%r" % (seq, status, stdout[:40], expected[:40]))

        print("   OI 得分：%d / 100  %s" % (oi, "AC" if oi == 100 else "未满分"))
        if oi == 100:
            total_pass += 1
        else:
            total_fail += 1

    print("\n" + "=" * 78)
    print("验证结果：%d 道 AC / %d 道未满分" % (total_pass, total_fail))
    print("=" * 78)
    return 0 if total_fail == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
