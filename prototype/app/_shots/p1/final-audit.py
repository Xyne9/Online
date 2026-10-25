# 全站体检：横向溢出 / 控制台报错 / 断链 / 字号规范
import os, json, re
from playwright.sync_api import sync_playwright

BASE = r"D:/毕设/agent%20oj/prototype/app/"
DIR = r"D:\毕设\agent oj\prototype\app"

PAGES = [
    "index.html", "t-dashboard.html", "t-problems.html", "t-problem-edit.html",
    "t-assignments.html", "t-plagiarism.html", "t-analytics.html",
    "t-monitor.html", "s-problems.html", "s-exam.html", "s-workbench.html",
    "s-analytics.html", "s-assignments.html", "s-submissions.html",
    "a-models.html", "a-audit.html",
]

W = 1440

with sync_playwright() as p:
    b = p.chromium.launch()
    ctx = b.new_context(viewport={"width": W, "height": 900})
    results = {}
    for page_name in PAGES:
        pg = ctx.new_page()
        errs = []
        pg.on("console", lambda m, e=errs: e.append("console: " + m.text) if m.type == "error" else None)
        pg.on("pageerror", lambda ex, e=errs: e.append("pageerror: " + str(ex)))
        pg.goto(BASE + page_name)
        pg.wait_for_timeout(900)
        info = pg.evaluate("""() => {
          const de = document.documentElement;
          const overflowX = de.scrollWidth - de.clientWidth;
          const overflowY = de.scrollHeight - de.clientHeight;
          // 找具体溢出的元素
          const bad = [];
          document.querySelectorAll('body *').forEach(el => {
            const r = el.getBoundingClientRect();
            if (r.width > 0 && r.right > de.clientWidth + 1.5) {
              bad.push(el.tagName + '.' + (el.className || '').toString().split(' ')[0] + ' right=' + Math.round(r.right));
            }
          });
          // 内联裸 px 字号
          const rawFs = [];
          document.querySelectorAll('[style]').forEach(el => {
            const m = /font-size\\s*:\\s*(\\d+(?:\\.\\d+)?)px/.exec(el.getAttribute('style'));
            if (m) rawFs.push(m[1] + 'px @' + el.tagName);
          });
          return {overflowX, overflowY, bad: bad.slice(0, 6), badCount: bad.length, rawFs};
        }""")
        results[page_name] = {"overflowX": info["overflowX"], "overflowY": info["overflowY"],
                              "badCount": info["badCount"], "bad": info["bad"],
                              "rawFs": info["rawFs"], "errors": errs}
        pg.close()
    b.close()

print(json.dumps(results, ensure_ascii=False, indent=1))

print("\n===== 汇总 =====")
problems = 0
for k, v in results.items():
    flags = []
    if v["overflowX"] > 0: flags.append(f"横向溢出 {v['overflowX']}px")
    if v["errors"]: flags.append(f"报错 {len(v['errors'])}")
    if v["rawFs"]: flags.append(f"裸px字号 {v['rawFs']}")
    if flags:
        problems += 1
        print(f"  {k}: " + "; ".join(flags))
if problems == 0:
    print("  全部 16 页：无横向溢出、无控制台报错、无内联裸 px 字号")

# 断链检查
print("\n===== 断链 =====")
allfiles = set(os.listdir(DIR))
subdirs = {d: set(os.listdir(os.path.join(DIR, d)))
           for d in os.listdir(DIR)
           if os.path.isdir(os.path.join(DIR, d)) and d != "_shots"}
broken = []
checked = 0
for page_name in PAGES:
    with open(os.path.join(DIR, page_name), encoding="utf-8") as f:
        txt = f.read()
    # 只匹配真正的 HTML 属性，排除 JS 模板字符串里的反引号
    for m in re.finditer(r'(?<![\w`])(?:href|src)="([^"#?]+\.(?:html|css|js))"', txt):
        target = m.group(1)
        if target.startswith(("http", "//")):
            continue
        checked += 1
        if "/" in target:
            sub, name = target.split("/", 1)
            if sub not in subdirs or name not in subdirs[sub]:
                broken.append(f"{page_name} -> {target}")
        elif target not in allfiles:
            broken.append(f"{page_name} -> {target}")
print(f"  检查 {checked} 个引用")
print("\n".join(broken) if broken else "  无断链")
