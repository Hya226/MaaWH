# -*- coding: utf-8 -*-
# 用 frame_dropdown.jpg 做像素测量：验证用户填的 points.json + 标定稀有度颜色
import json, sys

PATH = r"E:\MaaWH\gacha\frame_dropdown.jpg"

def load_image(path):
    try:
        from PIL import Image
        return Image.open(path).convert("RGB")
    except ImportError:
        import cv2
        import numpy as np
        arr = cv2.imread(path)[:, :, ::-1].copy()
        from PIL import Image
        return Image.fromarray(arr)

im = load_image(PATH)
px = im.load()
W, H = im.size

bright = lambda p: sum(p) / 3 > 130
whiteish = lambda p: sum(p) / 3 > 130 and (max(p) - min(p)) < 70
sat = lambda p: (max(p) - min(p)) > 50 and max(p) > 70

def bbox(x1, y1, x2, y2, pred):
    minx = miny = maxx = maxy = None
    for y in range(y1, y2):
        for x in range(x1, x2):
            if pred(px[x, y]):
                if minx is None or x < minx: minx = x
                if maxx is None or x > maxx: maxx = x
                if miny is None or y < miny: miny = y
                if maxy is None or y > maxy: maxy = y
    if minx is None:
        return None
    return [minx, miny, maxx, maxy]

def center(bb):
    return [round((bb[0] + bb[2]) / 2), round((bb[1] + bb[3]) / 2)]

def y_bands(x1, x2, y1, y2, pred, min_count=2, gap=3):
    counts = []
    for y in range(y1, y2):
        c = sum(1 for x in range(x1, x2) if pred(px[x, y]))
        counts.append((y, c))
    out, start, last_hit = [], None, None
    for y, c in counts:
        if c >= min_count:
            if start is None:
                start = y
            last_hit = y
        elif start is not None and y - last_hit > gap:
            out.append((start, last_hit))
            start = None
    if start is not None:
        out.append((start, last_hit))
    return out

out = {}

# ---- 1) 稀有度竖条：行带（应 10 条）+ 颜色标定 ----
strip = dict(x1=308, x2=361, y1=200, y2=565)
bands = y_bands(strip["x1"], strip["x2"], strip["y1"], strip["y2"], sat)
out["rarity_row_bands"] = bands
out["rarity_band_count"] = len(bands)

colors = {"特出": [], "优异": [], "新生": []}
for y in range(strip["y1"], strip["y2"]):
    for x in range(strip["x1"], strip["x2"]):
        r, g, b = px[x, y]
        if sat((r, g, b)):
            if r > g and r > b and g < 140 and b < 140:
                colors["特出"].append((r, g, b))
            elif r > b + 40 and g > b + 30:
                colors["优异"].append((r, g, b))
            elif g > r and b > r:
                colors["新生"].append((r, g, b))
out["colors_measured"] = {
    k: ([round(sum(c[i] for c in v) / len(v)) for i in range(3)], len(v)) if v else (None, 0)
    for k, v in colors.items()
}

# ---- 2) 名字列 / 时间列 文字实际范围（时间列只看未被下拉面板遮挡的第 5~10 行）----
out["name_text_bbox"] = bbox(414, 220, 746, 549, whiteish)
if len(bands) >= 10:
    t_y1, t_y2 = bands[4][0] - 4, bands[9][1] + 4
else:
    t_y1, t_y2 = 380, 549
out["time_rows_used"] = [t_y1, t_y2]
out["time_text_bbox"] = bbox(924, t_y1, 1225, t_y2, whiteish)

# ---- 3) 分页栏三个按钮 ----
out["prevPage"] = center(bbox(420, 563, 515, 610, bright))
out["pageOne"] = center(bbox(525, 563, 565, 610, bright))
out["nextPage"] = center(bbox(960, 563, 1075, 610, bright))

# ---- 4) 下拉框（收起态按钮 + 展开态四个选项）----
out["dropdown_btn"] = center(bbox(1045, 133, 1235, 178, bright))
opt_bands = y_bands(1050, 1232, 180, 360, bright, min_count=3)
out["option_bands"] = opt_bands
opts = {}
names = ["限时渠道", "限定渠道", "招募渠道", "征集渠道"]
for name, (a, b) in zip(names, opt_bands):
    bb = bbox(1060, a - 1, 1188, b + 2, bright)  # x2=1188 避开选中项右侧的 ✓
    opts[name] = center(bb) if bb else None
out["poolOptions_measured"] = opts

# ---- 5) 与用户填的值比对 ----
user = {
    "dropdown": [1132, 155],
    "nextPage": [1018, 584],
    "prevPage": [466, 584],
    "pageOne": [543, 588],
    "限时渠道": [1134, 201], "限定渠道": [1139, 246],
    "招募渠道": [1139, 293], "征集渠道": [1139, 338],
}
cmp_res = {
    "dropdown": [out["dropdown_btn"], user["dropdown"]],
    "nextPage": [out["nextPage"], user["nextPage"]],
    "prevPage": [out["prevPage"], user["prevPage"]],
    "pageOne": [out["pageOne"], user["pageOne"]],
}
for n in names:
    cmp_res[n] = [opts.get(n), user[n]]
out["compare_measured_vs_filled"] = cmp_res

print(json.dumps(out, ensure_ascii=False, indent=1))
