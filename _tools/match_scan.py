import sys, numpy as np
from PIL import Image

frame = np.asarray(Image.open(sys.argv[1]).convert('L'), dtype=np.float32)
tpl = np.asarray(Image.open(sys.argv[2]).convert('L'), dtype=np.float32)
th, tw = tpl.shape
fh, fw = frame.shape
print(f'frame {fw}x{fh}  tpl {tw}x{th}')

# 归一化互相关（stride=4 粗扫）
def norm(img):
    m = img.mean(); s = img.std()
    return (img - m) / (s + 1e-6)
fn = norm(frame)
tn = norm(tpl)
best = (-1, None, None)
step = 4
for y in range(0, fh - th + 1, step):
    for x in range(0, fw - tw + 1, step):
        win = fn[y:y+th, x:x+tw]
        score = float((win * tn).sum() / (th * tw))
        if score > best[0]:
            best = (score, x, y)
score, bx, by = best
print(f'best score={score:.4f} at ({bx},{by})  center=({bx+tw//2},{by+th//2})')

# 精扫 best 附近 +-6
best2 = (-1, None, None)
for y in range(max(0,by-6), min(fh-th,by+6)+1):
    for x in range(max(0,bx-6), min(fw-tw,bx+6)+1):
        win = fn[y:y+th, x:x+tw]
        s = float((win * tn).sum() / (th * tw))
        if s > best2[0]:
            best2 = (s, x, y)
score, bx, by = best2
print(f'fine  score={score:.4f} at ({bx},{by})  center=({bx+tw//2},{by+th//2})')

# 找第二高分（排除 best 附近 20px 邻域），看是否误匹配
x0b, x1b = max(0,bx-20), min(fw-tw,bx+20)
y0b, y1b = max(0,by-20), min(fh-th,by+20)
second = (-1, None, None)
for y in range(0, fh - th + 1, step):
    if y0b <= y <= y1b: continue
    for x in range(0, fw - tw + 1, step):
        if x0b <= x <= x1b: continue
        win = fn[y:y+th, x:x+tw]
        s = float((win * tn).sum() / (th * tw))
        if s > second[0]:
            second = (s, x, y)
print(f'second score={second[0]:.4f} at ({second[1]},{second[2]})')
