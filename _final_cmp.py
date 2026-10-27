import os
from PIL import Image
base=r"D:\毕设\agent oj\_day2_baseline"; now=r"D:\毕设\agent oj\prototype\app\_shots\_day2"
tot=0
for f in sorted(os.listdir(base)):
    if not f.endswith(".png"): continue
    a=Image.open(os.path.join(base,f)).convert("RGB"); b=Image.open(os.path.join(now,f)).convert("RGB")
    import PIL.ImageChops as C
    d=C.difference(a,b); bb=d.getbbox()
    n=sum(1 for p in d.getdata() if p!=(0,0,0)) if bb else 0
    if bb: print(f"{f:22} diff={n} bbox={bb}")
    else: print(f"{f:22} identical")
