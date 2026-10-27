"""一次性核对：verify-day17.py 的 E.3 判据现在能否在 verify-day12.py 里找到 #63/#71。

完全复刻 verify-day17.py 里 E.3 匹配逻辑，避免「改了 day12 但判据仍不认」。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
src = (ROOT / "scripts/verify-day12.py").read_text(encoding="utf-8")

plagiarism_paths = [
    ("63", "/api/teaching/plagiarism/check'", "POST"),
    ("64", "/api/teaching/plagiarism/diff/", "GET"),
    ("65", "/api/teaching/plagiarism/audit'", "PUT"),
    ("66", "/api/teaching/plagiarism/audit/", "DELETE"),
    ("67", "/api/teaching/plagiarism/tasks'", "POST"),
    ("68", "/api/teaching/plagiarism/tasks/", "GET"),
    ("69", "/api/teaching/plagiarism/pairs?", "GET"),
    ("70", "/api/teaching/plagiarism/pairs/", "GET"),
    ("71", "/api/teaching/plagiarism/tasks/", "POST"),
]
uncovered = []
for no, needle, method in plagiarism_paths:
    has_path = needle in src
    if no == "71":
        has_path = has_path and "/rerun" in src
    ok = has_path and method in src
    print(("PASS " if ok else "FAIL ") + "#%s  %s  [%s]" % (no, needle, method))
    if not ok:
        uncovered.append(no)

print()
print("E.3 uncovered:", uncovered if uncovered else "NONE — #63~#71 九个端点全部有真实调用")
sys.exit(0 if not uncovered else 1)
