# A/B 对照：有 @view-transition 与显式关闭，pageswap 的 viewTransition 是否不同
from playwright.sync_api import sync_playwright
import http.server, socketserver, threading, functools

BASE_DIR = r"D:\毕设\agent oj\prototype\app"
PORT = 8890


def serve():
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=BASE_DIR)
    with socketserver.TCPServer(("127.0.0.1", PORT), handler) as httpd:
        httpd.serve_forever()


threading.Thread(target=serve, daemon=True).start()

PROBE = """
window.addEventListener('pageswap', (e) => {
  try { sessionStorage.setItem('__swap', String(!!e.viewTransition)); } catch(err) {}
});
"""

DISABLE_CSS = """
window.addEventListener('DOMContentLoaded', () => {
  const s = document.createElement('style');
  s.textContent = '@view-transition{navigation:none !important}';
  document.head.appendChild(s);
});
"""


def run(label, disable):
    with sync_playwright() as p:
        b = p.chromium.launch()
        ctx = b.new_context(viewport={"width": 1440, "height": 900})
        pg = ctx.new_page()
        pg.add_init_script(PROBE)
        if disable:
            pg.add_init_script(DISABLE_CSS)
        pg.goto(f"http://127.0.0.1:{PORT}/t-dashboard.html")
        pg.wait_for_timeout(700)
        pg.evaluate("sessionStorage.removeItem('__swap')")
        pg.click("a.nav-item[href='t-problems.html']")
        pg.wait_for_timeout(1200)
        v = pg.evaluate("sessionStorage.getItem('__swap')")
        print(f"{label}: pageswap.viewTransition = {v}")
        b.close()


run("A 默认（@view-transition{navigation:auto}）", False)
run("B 显式关闭（navigation:none）", True)
