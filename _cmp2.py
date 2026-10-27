import os
from PIL import Image
base = r"D:\毕设\agent oj\_day2_baseline"
now  = r"D:\毕设\agent oj\prototype\app\_shots\_day2"
out  = r"D:\毕设\agent oj\_cmp_out"
os.makedirs(out, exist_ok=True)
# 1) s-workbench 12x12 区域放大 8 倍，并排
box = (1040, 110, 1130, 175)
for n in ["s-workbench"]:
    a = Image.open(os.path.join(base, n+".png")).crop(box)
    b = Image.open(os.path.join(now,  n+".png")).crop(box)
    w,h = a.size
    canvas = Image.new("RGB", (w*2*8+24, h*8), (255,0,255))
    canvas.paste(a.resize((w*8,h*8), Image.NEAREST), (0,0))
    canvas.paste(b.resize((w*8,h*8), Image.NEAREST), (w*8+24,0))
    canvas.save(os.path.join(out, "wb.png"))
# 2) 侧边栏 区域
box2 = (0, 100, 240, 170)
for n in ["s-exam","t-problem-edit"]:
    a = Image.open(os.path.join(base, n+".png")).crop(box2)
    b = Image.open(os.path.join(now,  n+".png")).crop(box2)
    w,h = a.size
    canvas = Image.new("RGB", (w*3+20, h*3*2+20), (255,0,255))
    canvas.paste(a.resize((w*3,h*3), Image.NEAREST), (0,0))
    canvas.paste(b.resize((w*3,h*3), Image.NEAREST), (0,h*3+20))
    canvas.save(os.path.join(out, n+"_side.png"))
print("ok")
