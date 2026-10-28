# -*- coding: utf-8 -*-
"""第六步真实运行补验：真实提交、Go-Judge 进程、管理端终止、终态与日志。

必须串行执行，前置：六服务、五容器、.env.local 的 OJ_INNER_TOKEN 已配置。
本套件保留自己的判题记录作为真实验收证据，不插入假 RUNNING 实例。
"""
import json
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASE = "http://127.0.0.1:8080"
checks = []
evidence = {}


def check(name, passed, detail=None):
    checks.append({"name": name, "pass": bool(passed), "detail": detail})
    print(("PASS " if passed else "FAIL ") + name, flush=True)
    if not passed:
        raise AssertionError(str(detail or name))


def request(method, path, token=None, body=None, expected=200):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        payload = json.load(response)
        check(method + " " + path + " HTTP " + str(expected),
              response.status == expected and payload.get("code") == expected,
              None if response.status == expected and payload.get("code") == expected
              else {"status": response.status, "payload": payload})
        return payload.get("data")


def login(name):
    return request("POST", "/api/auth/login", body={"username": name, "password": "123456"})["accessToken"]


def python_pids():
    output = subprocess.check_output(
        ["docker", "top", "oj-cloud-go-judge", "-eo", "pid,args"], text=True, encoding="utf-8")
    return {int(line.split()[0]) for line in output.splitlines()[1:]
            if "python3" in line and "/w/main.py" in line}


def wait_for(fn, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = fn()
        if result:
            return result
        time.sleep(0.1)
    raise AssertionError("等待真实运行状态超时")


def run():
    student = login("20220101")
    admin = login("admin")
    problems = request("GET", "/api/problem/questions?kw=A%20%2B%20B&page=1&pageSize=100", student)["list"]
    problem = next((p for p in problems if "".join(p["title"].split()).startswith("A+B")), None)
    check("按题名解析真实 A+B 题目", problem is not None)
    pid = problem["id"]
    evidence["problemId"] = pid
    before = python_pids()
    check("串行验收前没有其他判题 Python 进程", not before, sorted(before))
    submitted = request("POST", "/api/judge/submit", student, {
        "problemId": pid, "assignmentId": 0, "language": "python",
        "code": "import time\ntime.sleep(45)\nprint(0)\n"
    })
    sid = submitted["submissionId"]
    evidence["cancelSubmissionId"] = sid
    active = wait_for(lambda: python_pids() - before)
    evidence["observedPythonPids"] = sorted(active)
    check("Go-Judge 已真实启动本次 Python 进程", bool(active))

    def find_instance():
        boxes = request("GET", "/api/admin/sandbox/instances?pageSize=100&state=running", admin)
        return next((b for b in boxes["list"] if b.get("submissionId") == sid), None)

    box = wait_for(find_instance)
    box_id = box["sandboxInstanceId"]
    evidence["cancelInstanceId"] = box_id
    started = time.monotonic()
    killed = request("DELETE", "/api/admin/sandbox/instances/" + box_id + "?reason=verify-step6-runtime", admin)
    wait_for(lambda: not python_pids(), timeout=2)
    evidence["processReleaseMs"] = round((time.monotonic() - started) * 1000)
    check("终止后 2 秒内真实进程全部消失", not python_pids(), evidence)
    check("节点确认 KILLED / exitCode=137", killed["state"] == "KILLED" and killed["exitCode"] == 137)
    request("DELETE", "/api/admin/sandbox/instances/" + box_id, admin, expected=409)
    status = request("GET", "/api/judge/status/" + str(sid), student)
    check("被终止提交以 RE 结束并带终止原因",
          status["status"] == 8 and "终止" in str(status.get("errorMsg")), status)
    logs = request("GET", "/api/admin/sandbox/instances/" + box_id + "/logs", admin)
    check("真实日志记录终止原因", "verify-step6-runtime" in "\n".join(l["msg"] for l in logs["lines"]), logs)
    time.sleep(1)
    logs = request("GET", "/api/admin/sandbox/instances/" + box_id + "/logs", admin)
    check("消费者收尾未覆盖 KILLED", logs["state"] == "KILLED", logs)

    # 同一学生提交有 3 秒锁，等待自然过期，不删除生产锁。
    time.sleep(3)
    submitted = request("POST", "/api/judge/submit", student, {
        "problemId": pid, "assignmentId": 0, "language": "python",
        "code": "import sys\nfor line in sys.stdin:\n a=list(map(int,line.split()))\n if a: print(sum(a))\n"
    })
    success_sid = submitted["submissionId"]
    evidence["normalSubmissionId"] = success_sid

    def completed():
        data = request("GET", "/api/judge/status/" + str(success_sid), student)
        return data if data["status"] >= 3 else None

    success = wait_for(completed, 30)
    check("后续正常提交真实 AC，取消未损伤 MQ 消费者", success["status"] == 3, success)
    boxes = request("GET", "/api/admin/sandbox/instances?pageSize=100&state=exited", admin)
    normal = next((b for b in boxes["list"] if b.get("submissionId") == success_sid), None)
    check("正常提交拥有独立 EXITED 实例", normal is not None and normal["sandboxInstanceId"] != box_id, normal)
    request("DELETE", "/api/admin/sandbox/instances/" + normal["sandboxInstanceId"], admin, expected=409)
    normal_logs = request("GET", "/api/admin/sandbox/instances/" + normal["sandboxInstanceId"] + "/logs", admin)
    check("正常实例存在真实执行日志", bool(normal_logs["lines"]), normal_logs)


if __name__ == "__main__":
    try:
        run()
    except Exception as error:
        checks.append({"name": "runtime suite completed", "pass": False, "detail": str(error)})
        print("FAIL " + str(error), flush=True)
    finally:
        report = {"passed": sum(c["pass"] for c in checks), "total": len(checks),
                  "checks": checks, "evidence": evidence}
        out = ROOT / "logs/step6-runtime/report.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(str(report["passed"]) + "/" + str(report["total"]) + " -> " + str(out))
    raise SystemExit(0 if checks and all(c["pass"] for c in checks) else 1)
