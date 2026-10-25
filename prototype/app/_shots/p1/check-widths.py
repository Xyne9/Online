# 三档视口复核（改动后）
from playwright.sync_api import sync_playwright

URL = "file:///D:/毕设/agent%20oj/prototype/app/t-dashboard.html"
OUT = r"D:\毕设\agent oj\prototype\app\_shots\p1"

with sync_playwright() as p:
    b = p.chromium.launch()
    for w in (1440, 1240, 1080):
        ctx = b.new_context(viewport={"width": w, "height": 900})
        pg = ctx.new_page()
        errs = []
        pg.on("pageerror", lambda e: errs.append(str(e)))
        pg.goto(URL)
        pg.wait_for_timeout(800)
        info = pg.evaluate("""() => {
          const de = document.documentElement;
          const ms = document.querySelector('.mini-stats');
          const cols = ms ? getComputedStyle(ms).gridTemplateColumns.split(' ').length : 0;
          const hero = document.querySelector('.hero');
          const hcols = hero ? getComputedStyle(hero).gridTemplateColumns.split(' ').length : 0;
          const wk = document.querySelector('.hero-wk');
          return {ox: de.scrollWidth - de.clientWidth,
                  msCols: cols, heroCols: hcols,
                  wkW: wk ? Math.round(wk.getBoundingClientRect().width) : 0,
                  wkH: wk ? Math.round(wk.getBoundingClientRect().height) : 0};
        }""")
        pg.screenshot(path=f"{OUT}/v3-{w}.png", full_page=False)
        print(w, info, "errs:", errs)
        ctx.close()
    b.close()
