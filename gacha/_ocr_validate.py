# -*- coding: utf-8 -*-
# 离线验证：frame_jilu.jpg 的 10 行名字/时间裁剪 → PP-OCRv4 rec 识别
# 双通道互相印证：
#   A) RapidOCR 引擎（use_det/use_cls 关掉，纯 rec，参考实现）
#   B) 手写预处理 + CTC 解码（= 以后 Kotlin 端口的原样逻辑）
import numpy as np
import onnxruntime as ort
import os
import rapidocr_onnxruntime
from PIL import Image

PKG = os.path.dirname(rapidocr_onnxruntime.__file__)
REC = os.path.join(PKG, "models", "ch_PP-OCRv4_rec_infer.onnx")

EXPECTED_NAME = ["天气卜骨", "蛙锣", "幻戏图", "七盘舞砖", "山水人物镜",
                 "冷暖自知印", "样式雷", "蛙锣", "宝石冠", "蛙锣"]
EXPECTED_TIME = ["2026年9月10日10时46分"] * 10
BANDS = [(228, 241), (261, 275), (294, 309), (328, 341), (362, 375),
         (396, 408), (427, 441), (461, 475), (495, 508), (528, 542)]

im = Image.open(os.path.join(os.path.dirname(__file__), "frame_jilu.jpg")).convert("RGB")

def row_crop(x1, x2, y1, y2, scale=2):
    c = im.crop((x1, y1 - 5, x2, y2 + 5))
    c = c.resize((c.width * scale, c.height * scale), Image.LANCZOS)
    return np.asarray(c)[:, :, ::-1].copy()  # RGB→BGR，cv2 习惯

# ---------- A) RapidOCR 引擎 ----------
engine = rapidocr_onnxruntime.RapidOCR()

def engine_rec(bgr):
    res, _ = engine(bgr, use_det=False, use_cls=False)
    if not res:
        return "", 0.0
    return res[0][0], float(res[0][1])   # 单行图：第一个结果 (text, score)

# ---------- B) 手写预处理 + CTC（Kotlin 端口的参照） ----------
sess = ort.InferenceSession(REC, providers=["CPUExecutionProvider"])
charset = sess.get_modelmeta().custom_metadata_map["character"].splitlines()

def manual_rec(bgr):
    h = 48
    w = max(8, int(round(h * bgr.shape[1] / bgr.shape[0])))
    pil = Image.fromarray(bgr[:, :, ::-1]).resize((w, h), Image.LANCZOS)
    x = np.asarray(pil, dtype=np.float32)[:, :, ::-1]        # 回到 BGR 顺序无所谓，对称
    x = (x / 255.0 - 0.5) / 0.5
    x = x.transpose(2, 0, 1)[None]                            # CHW + batch
    out = sess.run(None, {sess.get_inputs()[0].name: x})[0]   # (1, T, 6625)
    pred = out[0]
    idx = pred.argmax(axis=1)
    conf = pred.max(axis=1)
    text, scores, last = [], [], -1
    for i, k in enumerate(idx):
        if k != 0 and k != last:                              # 0=CTC blank，重复折叠
            text.append(" " if k == len(charset) + 1 else charset[k - 1])
            scores.append(conf[i])
        last = k
    return "".join(text), float(np.mean(scores)) if scores else 0.0

ok_a = ok_b = 0
print("=" * 72)
print("行 | 引擎A 名字(分) / 手写B 名字(分) | 时间列 A / B")
for i, (y1, y2) in enumerate(BANDS):
    name_bgr = row_crop(414, 746, y1, y2)
    time_bgr = row_crop(924, 1225, y1, y2)
    a_n, a_ns = engine_rec(name_bgr)
    b_n, b_ns = manual_rec(name_bgr)
    a_t, a_ts_ = engine_rec(time_bgr)
    b_t, b_ts_ = manual_rec(time_bgr)
    ok_a += int(a_n == EXPECTED_NAME[i] and a_t == EXPECTED_TIME[i])
    ok_b += int(b_n == EXPECTED_NAME[i] and b_t == EXPECTED_TIME[i])
    mark = lambda got, exp: "" if got == exp else f"  ✗期望[{exp}]"
    print(f"{i+1:>2} | A:{a_n}({a_ns:.3f}){mark(a_n, EXPECTED_NAME[i])}")
    print(f"   | B:{b_n}({b_ns:.3f}){mark(b_n, EXPECTED_NAME[i])}")
    print(f"   | A:{a_t}{mark(a_t, EXPECTED_TIME[i])}")
    print(f"   | B:{b_t}{mark(b_t, EXPECTED_TIME[i])}")
print("=" * 72)
print(f"引擎A 全对 {ok_a}/10   手写B 全对 {ok_b}/10")
