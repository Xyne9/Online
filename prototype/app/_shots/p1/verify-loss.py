# 验证工作台「丢分点三处联动」是否真的生效
# 三处：判分链第 3 格(.cell) / 代码第 8 行(.code-row) / 抽屉丢分定位(.ai-pin)
import sys, io, json
from playwright.sync_api import sync_playwright

URL = "file:///D:/%E6%AF%95%E8%AE%BE/agent%20oj/prototype/app/s-workbench.html?state=wa"
OUT = r"D:\毕设\agent oj\prototype\app\_shots\p1"

def state(page):
    return page.evaluate("""() => {
      const pick = el => el ? el.className : null;
      const cell3 = document.querySelectorAll('.chain .cell')[2];
      const rows = [...document.querySelectorAll('.code-row.cur')].filter(e => e.offsetParent !== null);
      return {
        cell3: pick(cell3),
        code:  pick(rows[0]),
        pin:   pick(document.querySelector('.ai-pin[data-loss]')),
        attrs: [...document.querySelectorAll('[data-loss]')].map(e => e.tagName+'.'+e.className.split(' ')[0]+'='+e.getAttribute('data-loss'))
      };
    }""")

with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(viewport={"width":1440,"height":900})
    errs = []
    pg.on("console", lambda m: errs.append(m.text) if m.type == "error" else None)
    pg.on("pageerror", lambda e: errs.append(str(e)))
    pg.goto(URL)
    pg.wait_for_timeout(2200)   # 等判题动画跑完

    report = {}
    report["初始态"] = state(pg)

    # 悬停判分链第 3 格
    pg.locator(".chain .cell").nth(2).hover()
    pg.wait_for_timeout(250)
    report["悬停链带第3格"] = state(pg)
    pg.screenshot(path=OUT + r"\wb-hover-chain.png")

    # 移开
    pg.mouse.move(700, 20)
    pg.wait_for_timeout(250)
    report["移开后"] = state(pg)

    # 悬停代码第 8 行
    pg.locator(".code-row.cur").first.hover()
    pg.wait_for_timeout(250)
    report["悬停代码第8行"] = state(pg)

    # 悬停抽屉丢分定位
    pg.mouse.move(700, 20)
    pg.wait_for_timeout(150)
    pg.locator(".ai-pin[data-loss]").hover()
    pg.wait_for_timeout(250)
    report["悬停丢分定位"] = state(pg)

    # 点击锁定链带
    pg.locator(".chain .cell").nth(2).click()
    pg.wait_for_timeout(200)
    pg.mouse.move(700, 20)
    pg.wait_for_timeout(250)
    report["点击锁定后移开"] = state(pg)
    pg.screenshot(path=OUT + r"\wb-lock-chain.png")

    report["console_errors"] = errs
    print(json.dumps(report, ensure_ascii=False, indent=2))
    b.close()
