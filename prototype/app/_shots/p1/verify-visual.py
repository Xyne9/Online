# 视觉联动验证：同一会话内截图，裁剪三处区域做像素差
import os
from playwright.sync_api import sync_playwright
from PIL import Image, ImageChops

URL = "file:///D:/%E6%AF%95%E8%AE%BE/agent%20oj/prototype/app/s-workbench.html?state=wa"
OUT = r"D:\毕设\agent oj\prototype\app\_shots\p1"

# 三处目标的包围盒（在 1440x900 视口内，先由 DOM 量出来，避免手填）
MEASURE = """() => {
  const vis = sel => [...document.querySelectorAll(sel)].filter(e => e.offsetParent !== null);
  const box = el => { const r = el.getBoundingClientRect(); return {x:r.x,y:r.y,w:r.width,h:r.height}; };
  return {
    chain: box(document.querySelectorAll('.chain .cell')[2]),
    code:  box(vis('.code-row.cur')[0]),
    pin:   box(document.querySelector('.ai-pin[data-loss]'))
  };
}"""

def crop(img, box, pad=10):
    x = max(0, int(box["x"]) - pad); y = max(0, int(box["y"]) - pad)
    return img.crop((x, y, min(img.width, x + int(box["w"]) + pad*2), min(img.height, y + int(box["h"]) + pad*2)))

with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(viewport={"width": 1440, "height": 900})
    pg.goto(URL)
    pg.wait_for_timeout(2200)

    boxes = pg.evaluate(MEASURE)
    print("boxes:", boxes)

    def shot(name):
        path = os.path.join(OUT, name)
        pg.screenshot(path=path)
        return Image.open(path).convert("RGB")

    base = shot("v-base.png")
    crops = {"base": {k: crop(base, v) for k, v in boxes.items()}}

    for key, sel in [("chain", ".chain .cell >> nth=2"),
                     ("code", ".code-row.cur >> nth=0"),
                     ("pin", ".ai-pin[data-loss]")]:
        pg.mouse.move(700, 12); pg.wait_for_timeout(200)
        pg.locator(sel).hover(); pg.wait_for_timeout(300)
        img = shot(f"v-hover-{key}.png")
        crops[key] = {k: crop(img, v) for k, v in boxes.items()}
        pg.mouse.move(700, 12); pg.wait_for_timeout(200)

    b.close()

# 每个 hover 态 vs base：三处是否都变了
for key in ("chain", "code", "pin"):
    print(f"\n== 悬停 {key} ==")
    for target in ("chain", "code", "pin"):
        d = ImageChops.difference(crops["base"][target], crops[key][target])
        bbox = d.getbbox()
        changed = bbox is not None
        # 统计变化像素占比
        px = sum(1 for v in d.convert("L").getdata() if v > 6)
        total = crops["base"][target].width * crops["base"][target].height
        print(f"  {target:6s} 变化={changed}  bbox={bbox}  变化像素={px}/{total} ({px/total*100:.1f}%)")
        crops[key][target].save(os.path.join(OUT, f"crop-{key}-on-{target}.png"))
