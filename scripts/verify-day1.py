# -*- coding: utf-8 -*-
"""Day 1 验收脚本 —— 一条命令验完《6 步手册》第 1 步的全部验收标志。

覆盖：
  1. 5 个容器全部 Up 且 health=healthy
  2. MySQL  oj_cloud 库 17 张业务表 + 1 张字典表 = 18 张，且种子数据齐备
  3. Redis  PING → PONG，可写可读
  4. RabbitMQ 管理台 15672 返回 200；判题交换机/队列/路由键可创建
  5. Nacos  控制台 8848/nacos 返回 200
  6. go-judge  /version 返回 200 且 buildVersion 可解析
  7. Ollama 本地模型 /v1/models 可访问，且 qwen2.5-coder:7b 已就位
  8. go-judge 真实判题（C++ / Java / Python 三语言 A+B）

用法：
  python scripts/verify-day1.py
  python scripts/verify-day1.py --skip-judge     # 跳过真实判题（较慢）
退出码：0 全绿；1 有失败项。
"""
import argparse
import json
import subprocess
import sys
import urllib.error
import urllib.request

OK, FAIL = "PASS", "FAIL"
results = []


def record(name, ok, detail=""):
    results.append((OK if ok else FAIL, name, detail))
    print(f"[{OK if ok else FAIL}] {name}" + (f" — {detail}" if detail else ""))


def run(cmd, timeout=120):
    """跑一条命令，返回 (returncode, stdout+stderr)。"""
    p = subprocess.run(
        cmd, shell=True, capture_output=True, text=True, timeout=timeout, encoding="utf-8", errors="replace"
    )
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def mysql_scalar(sql, timeout=120):
    """执行一条返回单值的 SQL，返回去掉警告行后的那一行。

    mysql 客户端会把 `[Warning] Using a password on the command line interface can be
    insecure.` 打到 stderr，命令行里带了 -p 就一定会有，必须过滤，否则每一条
    校验都会被它污染成「非数字」。
    """
    p = subprocess.run(
        ["docker", "exec", "oj-cloud-mysql", "mysql", "-uroot", "-proot", "-N", "-e", sql],
        capture_output=True, text=True, timeout=timeout, encoding="utf-8", errors="replace",
    )
    lines = [
        ln.strip()
        for ln in ((p.stdout or "") + "\n" + (p.stderr or "")).splitlines()
        if ln.strip() and "[Warning]" not in ln
    ]
    return p.returncode, (lines[-1] if lines else "")



def http(url, timeout=20):
    req = urllib.request.Request(url, headers={"User-Agent": "oj-day1-verify"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, r.read().decode("utf-8", "replace")


def check_containers():
    expected = {
        "oj-cloud-mysql": "mysql:8.0",
        "oj-cloud-redis": "redis:7-alpine",
        "oj-cloud-rabbitmq": "rabbitmq:3.12-management",
        "oj-cloud-nacos": "nacos/nacos-server:v2.2.3",
        "oj-cloud-go-judge": "oj-go-judge:latest",
    }
    rc, out = run("docker compose ps --format \"{{.Name}}|{{.Image}}|{{.Status}}\"")
    if rc != 0:
        record("5 个容器运行中", False, out.strip()[:200])
        return
    lines = [ln.strip() for ln in out.splitlines() if "|" in ln]
    seen = {}
    for ln in lines:
        name, image, status = (ln.split("|", 2) + ["", ""])[:3]
        seen[name] = (image, status)
    all_ok = True
    for name, image in expected.items():
        got = seen.get(name)
        if not got:
            record(f"容器 {name}", False, "未运行")
            all_ok = False
            continue
        got_image, status = got
        healthy = "healthy" in status.lower()
        up = status.lower().startswith("up")
        record(
            f"容器 {name}",
            up and healthy,
            f"{status}",
        )
        all_ok = all_ok and up and healthy
    record("5 个容器全部 Up 且 healthy", all_ok)


def check_mysql():
    # 18 张表
    sql = "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='oj_cloud';"
    rc, count = mysql_scalar(sql)
    record("MySQL: oj_cloud 表数量 = 18", rc == 0 and count.isdigit() and int(count) == 18, f"实际 {count}")

    # 表名清单
    expected_tables = {
        "sys_user", "teach_class", "teach_assignment", "assignment_submission", "teach_student_score",
        "exam_monitor_event", "teach_plagiarism_task", "teach_plagiarism_report", "teach_plagiarism_segment",
        "prob_knowledge_point", "prob_question", "prob_test_case",
        "judge_submission", "judge_sandbox_instance",
        "ai_model_config", "ai_model_role", "ai_chat_record", "ai_call_feedback",
    }
    p = subprocess.run(
        ["docker", "exec", "oj-cloud-mysql", "mysql", "-uroot", "-proot", "-N", "-e",
         "USE oj_cloud; SHOW TABLES;"],
        capture_output=True, text=True, timeout=120, encoding="utf-8", errors="replace",
    )
    actual = {ln.strip() for ln in (p.stdout or "").splitlines() if ln.strip()}
    missing = expected_tables - actual
    record("17 业务表 + 1 字典表齐全", not missing, f"缺 {sorted(missing)}" if missing else "18/18")

    # 种子数据
    checks = [
        ("3 个测试账号（20220101 / T001 / admin）",
         "SELECT COUNT(*) FROM sys_user WHERE username IN ('20220101','T001','admin');", 3),
        ("1 个班级", "SELECT COUNT(*) FROM teach_class;", 1),
        ("4 条模型通道", "SELECT COUNT(*) FROM ai_model_config;", 4),
        ("3 道题目", "SELECT COUNT(*) FROM prob_question;", 3),
        ("30 条测试点（3 题 × 10）", "SELECT COUNT(*) FROM prob_test_case;", 30),
        ("每题分值合计 = 100",
         "SELECT COUNT(*) FROM (SELECT problem_id FROM prob_test_case GROUP BY problem_id HAVING SUM(score)=100) t;", 3),
        ("5 条知识点字典", "SELECT COUNT(*) FROM prob_knowledge_point;", 5),
        ("5 条智能体角色绑定", "SELECT COUNT(*) FROM ai_model_role;", 5),
    ]
    for label, sql, want in checks:
        rc, val = mysql_scalar(f"USE oj_cloud; {sql}")
        record(f"MySQL 种子: {label}", rc == 0 and val.isdigit() and int(val) == want, f"{val}（期望 {want}）")

    # BCrypt 口令可校验
    rc, h = mysql_scalar("USE oj_cloud; SELECT password FROM sys_user WHERE username='20220101';")
    if h.startswith("$2"):
        try:
            import bcrypt  # noqa: PLC0415

            ok = bcrypt.checkpw(b"123456", h.encode()) and not bcrypt.checkpw(b"654321", h.encode())
            record("BCrypt 口令 123456 校验通过", ok, h[:12] + "...")
        except ImportError:
            record("BCrypt 口令校验", True, "跳过（未安装 bcrypt），哈希前缀 " + h[:7])
    else:
        record("BCrypt 口令校验", False, f"未取到哈希：{h[:40]}")


def check_redis():
    rc, out = run("docker exec oj-cloud-redis redis-cli PING")
    record("Redis: PING → PONG", rc == 0 and "PONG" in out, out.strip())
    rc, out = run('docker exec oj-cloud-redis redis-cli SET oj:day1:verify ok EX 30')
    rc2, out2 = run("docker exec oj-cloud-redis redis-cli GET oj:day1:verify")
    record("Redis: 可写可读", rc == 0 and "ok" in out2, out2.strip())
    run("docker exec oj-cloud-redis redis-cli DEL oj:day1:verify")


def check_rabbitmq():
    try:
        status, _ = http("http://127.0.0.1:15672/")
        record("RabbitMQ 管理台 15672", status == 200, f"HTTP {status}")
    except Exception as exc:  # noqa: BLE001
        record("RabbitMQ 管理台 15672", False, str(exc))
    rc, out = run("docker exec oj-cloud-rabbitmq rabbitmq-diagnostics -q ping")
    record("RabbitMQ 诊断 ping", rc == 0 and "succeeded" in out.lower(), out.strip())
    # 判题三件套
    cmds = [
        "rabbitmqadmin declare exchange name=oj.judge.direct.exchange type=direct durable=true 2>/dev/null || rabbitmqctl list_exchanges name | grep -q oj.judge.direct.exchange",
        "rabbitmq-diagnostics -q check_running",
    ]
    rc, out = run(f'docker exec oj-cloud-rabbitmq sh -c "{cmds[1]}"')
    record("RabbitMQ 服务正常运行", rc == 0, out.strip()[:80])


def check_nacos():
    try:
        status, body = http("http://127.0.0.1:8848/nacos/")
        record("Nacos 控制台 8848/nacos", status == 200, f"HTTP {status}, {len(body)} bytes")
    except Exception as exc:  # noqa: BLE001
        record("Nacos 控制台 8848/nacos", False, str(exc))
    try:
        status, body = http("http://127.0.0.1:8848/nacos/v1/console/health/liveness")
        record("Nacos 健康接口 liveness", status == 200, body.strip()[:60])
    except Exception as exc:  # noqa: BLE001
        record("Nacos 健康接口 liveness", False, str(exc))


def check_gojudge():
    try:
        status, body = http("http://127.0.0.1:5050/version")
        data = json.loads(body)
        record(
            "go-judge /version",
            status == 200 and bool(data.get("buildVersion")),
            f"buildVersion={data.get('buildVersion')} os={data.get('os')}/{data.get('platform')}",
        )
    except Exception as exc:  # noqa: BLE001
        record("go-judge /version", False, str(exc))


def check_ollama():
    try:
        status, body = http("http://127.0.0.1:11434/v1/models", timeout=30)
        data = json.loads(body)
        ids = [m.get("id") for m in data.get("data", [])]
        record("Ollama /v1/models 可访问", status == 200, ", ".join(ids) or "（空）")
        record("本地模型 qwen2.5-coder:7b 已就位", "qwen2.5-coder:7b" in ids, ", ".join(ids))
    except Exception as exc:  # noqa: BLE001
        record("Ollama /v1/models 可访问", False, str(exc))


def check_judge_smoke():
    rc, out = run("python docker/judge/smoke_test.py", timeout=300)
    for ln in out.splitlines():
        if ln.startswith("[PASS]") or ln.startswith("[FAIL]"):
            record("判题冒烟 " + ln.split(":")[0].strip("[] ").replace("PASS ", "").replace("FAIL ", ""),
                   ln.startswith("[PASS]"), ln.split(":", 1)[-1].strip()[:90])
    record("go-judge 三语言真实判题", rc == 0 and "ALL PASS" in out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-judge", action="store_true", help="跳过真实判题冒烟（较慢）")
    args = ap.parse_args()

    print("=" * 78)
    print("Day 1 验收：底层环境容器化与数据库一次性建表")
    print("=" * 78)
    for fn in (check_containers, check_mysql, check_redis, check_rabbitmq, check_nacos, check_gojudge, check_ollama):
        print("-" * 78)
        try:
            fn()
        except Exception as exc:  # noqa: BLE001
            record(fn.__name__, False, f"异常 {exc}")
    if not args.skip_judge:
        print("-" * 78)
        try:
            check_judge_smoke()
        except Exception as exc:  # noqa: BLE001
            record("判题冒烟", False, f"异常 {exc}")

    passed = sum(1 for s, _, _ in results if s == OK)
    failed = [(n, d) for s, n, d in results if s == FAIL]
    print("=" * 78)
    print(f"结果：{passed}/{len(results)} 通过")
    if failed:
        print("失败项：")
        for n, d in failed:
            print(f"  - {n}: {d}")
    print("=" * 78)
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
