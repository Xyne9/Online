import os, sys
from PIL import Image, ImageChops
base = r"D:\毕设\agent oj\_day2_baseline"
now  = r"D:\毕设\agent oj\prototype\app\_shots\_day2"
for n in ["index","s-exam","s-workbench","t-problem-edit"]:
    a = Image.open(os.path.join(base, n+".png")).convert("RGB")
    b = Image.open(os.path.join(now,  n+".png")).convert("RGB")
    if a.size != b.size:
        print(f"{n}: SIZE DIFF {a.size} vs {b.size}"); continue
    d = ImageChops.difference(a,b)
    bbox = d.getbbox()
    px = sum(1 for p in d.getdata() if p != (0,0,0))
    print(f"{n}: size={a.size} diffpix={px} bbox={bbox}")
    if bbox: print(f"   -> w={bbox[2]-bbox[0]} h={bbox[3]-bbox[1]} region={bbox}")
