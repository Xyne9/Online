# 全站截图（1440×900 首屏），用法：python shoot-now.py <前缀> [页面名...]
import sys, os
from playwright.sync_api import sync_playwright

BASE = "file:///D:/%E6%AF%95%E8%AE%BE/agent%20oj/prototype/app/"
OUT = os.path.dirname(os.path.abspath(__file__))
PAGES = ["t-dashboard", "t-problems", "t-problem-edit", "t-assignments", "t-plagiarism",
         "t-analytics", "t-monitor", "s-problems", "s-exam", "s-workbench", "s-analytics",
         "s-assignments", "s-submissions", "a-models", "a-audit", "index"]
prefix = sys.argv[1] if len(sys.argv) > 1 else "now"
pages = sys.argv[2:] or PAGES
with sync_playwright() as p:
    b = p.chromium.launch(channel="chrome")
    ctx = b.new_context(viewport={"width": 1440, "height": 900})
    for n in pages:
        pg = ctx.new_page()
        errs = []
        pg.on("pageerror", lambda ex, e=errs: e.append(str(ex)))
        pg.on("console", lambda m, e=errs: e.append(m.text) if m.type == "error" else None)
        pg.goto(BASE + n + ".html")
        pg.wait_for_timeout(1200)
        pg.screenshot(path=os.path.join(OUT, f"{prefix}-{n}.png"))
        ov = pg.evaluate("document.documentElement.scrollWidth-document.documentElement.clientWidth")
        print(n, "overflowX=", ov, "errors=", errs[:3])
        pg.close()
    b.close()
