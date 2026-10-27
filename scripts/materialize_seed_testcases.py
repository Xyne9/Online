# -*- coding: utf-8 -*-
"""为 Day1 种子题（1001-1003）补齐磁盘测试数据。

问题：init_oj_cloud.sql 的 3 道种子题声明了 test_case_dir='/data/testcases/{id}/'
      和 test_case_count=10，但 data/testcases/ 下**没有对应目录**。
      判题服务按 test_case_dir 读文件时会直接报「文件不存在」。
      这是 Day1 建表时的遗留缺陷——当时只把测试点写进了 prob_test_case 表，
      没意识到 go-judge 走的是文件系统而不是数据库。

本脚本从 prob_test_case 表里把 input_text/output_text 导成 .in/.out 文件。
种子题的测试点本来就是完整文本（不像导入的 1000 道题只存 4KB 摘要），
所以可以直接落盘，不需要重新拉题库。

用法：python scripts/materialize_seed_testcases.py [--dry-run]
"""
import argparse
import os
import subprocess
import sys
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
TESTCASE_ROOT = PROJECT / "data" / "testcases"
MYSQL_BASE = ["docker", "exec", "-i", "oj-cloud-mysql", "mysql",
              "--default-character-set=utf8mb4", "-uroot", "-proot"]


def query_raw(sql, db="oj_cloud"):
    """返回原始字节，避免换行/编码被 PowerShell 或文本模式改写。"""
    p = subprocess.run(MYSQL_BASE + ["-N", "-B", "--raw", db],
                       input=sql.encode("utf-8"), capture_output=True)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.decode("utf-8", "replace")[:1000])
    return p.stdout


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--ids", default="1001,1002,1003")
    args = ap.parse_args()

    ids = [x.strip() for x in args.ids.split(",") if x.strip()]
    print("=" * 70)
    print("补齐种子题的磁盘测试数据")
    print("=" * 70)

    total = 0
    for pid in ids:
        # 用 HEX 传输，彻底避开换行/制表符被 -B 模式吃掉的问题
        rows = query_raw(
            "SELECT seq_no, HEX(input_text), HEX(output_text) FROM prob_test_case "
            "WHERE problem_id=%s AND deleted_at IS NULL ORDER BY seq_no;" % pid
        ).decode("utf-8", "replace").splitlines()

        dst = TESTCASE_ROOT / pid
        if not rows:
            print("  [skip] %s 无测试点" % pid)
            continue
        if args.dry_run:
            print("  [dry]  %s 将写入 %d 个测试点 → %s" % (pid, len(rows), dst))
            total += len(rows)
            continue

        dst.mkdir(parents=True, exist_ok=True)
        n = 0
        for line in rows:
            parts = line.split("\t")
            if len(parts) < 3:
                continue
            seq, hex_in, hex_out = parts[0], parts[1], parts[2]
            in_bytes = bytes.fromhex(hex_in) if hex_in else b""
            out_bytes = bytes.fromhex(hex_out) if hex_out else b""
            (dst / ("%s.in" % seq)).write_bytes(in_bytes)
            (dst / ("%s.out" % seq)).write_bytes(out_bytes)
            n += 1
        print("  [ok]   %s 写入 %d 个测试点 → %s" % (pid, n, dst))
        total += n

    print("\n合计 %d 个测试点。" % total)
    if not args.dry_run:
        print("落盘目录：%s" % TESTCASE_ROOT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
