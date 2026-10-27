from PIL import Image, ImageChops
a=Image.open(r"D:\毕设\agent oj\_day2_baseline\index.png").convert("RGB")
b=Image.open(r"D:\毕设\agent oj\prototype\app\_shots\_day2\index.png").convert("RGB")
d=ImageChops.difference(a,b); print("bbox:",d.getbbox())
box=d.getbbox()
ca=a.crop((box[0],box[1],box[2],box[3])); cb=b.crop((box[0],box[1],box[2],box[3]))
w,h=ca.size; S=4
cv=Image.new("RGB",(w*S,h*S*2+12),(255,0,255))
cv.paste(ca.resize((w*S,h*S),Image.LANCZOS),(0,0)); cv.paste(cb.resize((w*S,h*S),Image.LANCZOS),(0,h*S+12))
cv.save(r"D:\毕设\agent oj\_cmp_out\index_note.png"); print("saved", cv.size)
