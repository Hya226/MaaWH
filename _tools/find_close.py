# -*- coding: utf-8 -*-
"""在最新虚拟屏帧上定位关闭按钮「×」，判断现有 cha_x.png 能否匹配。"""
import sys

try:
    import cv2
except ImportError:
    print("no-cv2")
    sys.exit(0)

FRAME = r'E:\MaaWH\_tools\now.jpg'
TPL = r'E:\MaaWH\whmx\image\cha_x.png'

frame = cv2.imread(FRAME)
if frame is None:
    print("frame-missing")
    sys.exit(0)
print("frame", frame.shape)

tpl = cv2.imread(TPL)
if tpl is not None:
    res = cv2.matchTemplate(frame, tpl, cv2.TM_CCOEFF_NORMED)
    _, mx, _, mxl = cv2.minMaxLoc(res)
    h, w = tpl.shape[:2]
    cx, cy = mxl[0] + w // 2, mxl[1] + h // 2
    print(f"cha_x.png best score={mx:.3f} center=({cx},{cy}) size={w}x{h}")

# 保存右上角放大图供人工确认
h, w = frame.shape[:2]
crop = frame[0:int(h * 0.28), int(w * 0.72):w]
zoom = cv2.resize(crop, None, fx=2.4, fy=2.4, interpolation=cv2.INTER_NEAREST)
cv2.imwrite(r'E:\MaaWH\_tools\_zoom_tr.png', zoom)
print("saved _zoom_tr.png (crop origin x=%d)" % int(w * 0.72))
