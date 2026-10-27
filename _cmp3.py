import os
from PIL import Image, ImageChops
base = r"D:\毕设\agent oj\_day2_baseline"; now = r"D:\毕设\agent oj\prototype\app\_shots\_day2"
out = r"D:\毕设\agent oj\_cmp_out"; os.makedirs(out, exist_ok=True)
a = Image.open(os.path.join(base,"s-workbench.png")).convert("RGB")
b = Image.open(os.path.join(now ,"s-workbench.png")).convert("RGB")
box=(1065,130,1095,160)
ca=a.crop(box); cb=b.crop(box)
w,h=ca.size; S=16
cv=Image.new("RGB",(w*S*2+24,h*S),(255,0,255))
cv.paste(ca.resize((w*S,h*S),Image.NEAREST),(0,0)); cv.paste(cb.resize((w*S,h*S),Image.NEAREST),(w*S+24,0))
cv.save(os.path.join(out,"wb_zoom.png"))
# 打印该区域内不同像素的具体颜色
d=ImageChops.difference(a.crop(box),b.crop(box))
for y in range(h):
    for x in range(w):
        p=d.getpixel((x,y))
        if p!=(0,0,0):
            print(f"({x+box[0]},{y+box[1]}) base={ca.getpixel((x,y))} now={cb.getpixel((x,y))}")
