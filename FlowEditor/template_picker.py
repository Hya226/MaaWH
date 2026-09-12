# -*- coding: utf-8 -*-
"""
模板框选工具 v2：拖拽框选 → 立刻校验区分度 → 保存。

相对 v1 的改进（针对「固定坐标改成模板识别」时的准确率顾虑）：
 1. 框选后立即做「负样本校验」：在 _tools 下其它页面帧上求最高匹配分。
    该分数越低越好；若 >0.75 说明会和别的页面混淆，需要缩小选框或换更独特的区域。
 2. 给出建议阈值（= 自身分与负样本最高分之间取偏安全值），直接填进 pipeline。
 3. 右侧显示框选区域放大预览，便于确认选框是否干净（没有多框进背景/相邻元素）。
 4. 默认不再生成 xxx_2_3.png（引擎已按虚拟屏原生帧 1608x720 识别，2/3 版是历史遗留）。
 5. 保存前可改文件名；同名会提示覆盖。

用法：
  python _tools/template_picker.py [帧图路径]
  python _tools/template_picker.py [帧图路径] --neg 负样本目录
"""
import os
import sys
import glob
import tkinter as tk
from tkinter import filedialog, ttk, messagebox

try:
    import cv2
    import numpy as np
except ImportError:
    print("需要 opencv-python：pip install opencv-python")
    sys.exit(1)

from PIL import Image, ImageTk

# TOOLS_DIR = 本工具所在目录（FlowEditor/，与流程编辑器共用 pick_frame.jpg）；负样本沿用 _tools/neg_frames
TOOLS_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(TOOLS_DIR)
OUT_DIR = os.path.join(ROOT, "whmx", "image")
NEG_DIR = os.path.join(ROOT, "_tools", "neg_frames")
ADB = r"D:\android-studio\Sdk\platform-tools\adb.exe"
DEVICE = "2c92e197"
PKG = "com.maawh.app"

# ---------------- 状态 ----------------
frame_bgr = None        # 当前帧（OpenCV BGR）
frame_path = None
negatives = []          # [(名称, BGR 图)]
pil_img = None
photo = None
scale = 1.0
rect_id = None
sx = sy = 0
sel = None              # (x0,y0,x1,y1) 原图坐标
tpl_bgr = None
last_verdict = ""

root = tk.Tk()
root.title("模板框选工具 v2 · 带负样本校验")
root.geometry("1500x920")

top = ttk.Frame(root)
top.pack(fill="x")

canvas = tk.Canvas(root, bg="#202020")
canvas.pack(side="left", fill="both", expand=True)

side = ttk.Frame(root, width=380)
side.pack(side="right", fill="y")
side.pack_propagate(False)


def log(s):
    info_var.set(s)
    print(s)


info_var = tk.StringVar(value="① 打开帧图 ② 拖拽框选目标 ③ 看右侧校验结果 ④ 保存")
ttk.Label(root, textvariable=info_var, relief="sunken", anchor="w").pack(fill="x", side="bottom")

# ---------------- 帧图加载 ----------------

def load_frame(path):
    global frame_bgr, frame_path, pil_img, photo, scale
    img = cv2.imread(path)
    if img is None:
        log(f"读不到图片: {path}")
        return
    frame_bgr = img
    frame_path = path
    pil_img = Image.open(path).convert("RGB")
    dw, dh = 1080, int(1080 * pil_img.height / pil_img.width)
    if dh > 820:
        dh = 820
        dw = int(820 * pil_img.width / pil_img.height)
    scale = pil_img.width / dw
    photo = ImageTk.PhotoImage(pil_img.resize((dw, dh), Image.LANCZOS))
    canvas.delete("all")
    canvas.create_image(0, 0, anchor="nw", image=photo)
    canvas.config(scrollregion=(0, 0, dw, dh), width=dw, height=dh)
    log(f"帧图 {os.path.basename(path)}  {pil_img.width}x{pil_img.height}  "
        f"负样本 {len(negatives)} 张")


def open_image():
    p = filedialog.askopenfilename(title="选择帧图", initialdir=TOOLS_DIR,
                                   filetypes=[("图片", "*.png *.jpg *.jpeg")])
    if p:
        load_frame(p)


def recapture():
    """一键重新抓帧：确保 App 前台 → FrameSave → 取回，然后直接加载。
    等价于命令行 `bash _tools/pick.sh`，省去来回沟通。"""
    import subprocess
    import time as _t

    def adb(*args):
        return subprocess.run([ADB, "-s", DEVICE, *args],
                              capture_output=True, timeout=60)

    try:
        log("重新抓帧中…")
        root.update()
        # 清掉旧帧，避免抓到上一次的残留
        adb("shell", f"run-as {PKG} sh -c 'rm -f files/cur_frame.jpg'")
        adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
        _t.sleep(1.5)
        adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity",
            "--activity-single-top", "--es", "entry", "FrameSave")
        _t.sleep(5)
        out = adb("exec-out", "run-as", PKG, "cat", "files/cur_frame.jpg")
        data = out.stdout
        if not data or len(data) < 2000:
            log("抓帧失败：帧为空。请确认 App 在前台、虚拟屏已启动、游戏画面正常。")
            return
        path = os.path.join(TOOLS_DIR, "pick_frame.jpg")
        with open(path, "wb") as f:
            f.write(data)
        load_frame(path)
        # 顺手存进负样本目录，供后续校验区分度
        neg_dir = NEG_DIR
        os.makedirs(neg_dir, exist_ok=True)
        with open(os.path.join(neg_dir, f"cap_{int(_t.time())}.jpg"), "wb") as f:
            f.write(data)
        log(f"已重新抓帧并加载（{len(data)} bytes）")
    except Exception as e:
        log(f"抓帧异常：{e}")


def load_negatives(folder=None):
    global negatives
    folder = folder or filedialog.askdirectory(title="选择负样本目录", initialdir=TOOLS_DIR)
    if not folder:
        return
    negatives = []
    for p in sorted(glob.glob(os.path.join(folder, "*.jpg")) +
                    glob.glob(os.path.join(folder, "*.png"))):
        if frame_path and os.path.abspath(p) == os.path.abspath(frame_path):
            continue
        im = cv2.imread(p)
        if im is not None:
            negatives.append((os.path.basename(p), im))
    log(f"已载入负样本 {len(negatives)} 张（用于评估误匹配风险）")


# ---------------- 框选 ----------------

def on_down(e):
    global sx, sy
    sx, sy = e.x, e.y


def on_move(e):
    global rect_id
    if rect_id:
        canvas.delete(rect_id)
    rect_id = canvas.create_rectangle(sx, sy, e.x, e.y, outline="#ff4040", width=2)
    ox0, oy0 = int(sx * scale), int(sy * scale)
    ox1, oy1 = int(e.x * scale), int(e.y * scale)
    info_var.set(f"框选 ({ox0},{oy0})-({ox1},{oy1})  尺寸 {abs(ox1-ox0)}x{abs(oy1-oy0)}")


def on_up(e):
    global rect_id, sel
    if rect_id:
        canvas.delete(rect_id)
        rect_id = None
    if frame_bgr is None:
        return
    x0, x1 = sorted([int(sx * scale), int(e.x * scale)])
    y0, y1 = sorted([int(sy * scale), int(e.y * scale)])
    if x1 - x0 < 4 or y1 - y0 < 4:
        log("框选太小，请重新拖拽")
        return
    sel = (x0, y0, x1, y1)
    analyze()


def analyze():
    """核心：自身匹配 + 负样本校验 + 建议阈值。"""
    global tpl_bgr, last_verdict
    x0, y0, x1, y1 = sel
    tpl_bgr = frame_bgr[y0:y1, x0:x1].copy()

    # 右侧放大预览
    prev = Image.fromarray(cv2.cvtColor(tpl_bgr, cv2.COLOR_BGR2RGB))
    pw = 340
    ph = max(1, int(prev.height * pw / prev.width))
    if ph > 260:
        ph = 260
        pw = max(1, int(prev.width * ph / prev.height))
    prev_photo = ImageTk.PhotoImage(prev.resize((pw, ph), Image.LANCZOS))
    preview_label.config(image=prev_photo)
    preview_label.image = prev_photo

    # 自身匹配分
    res = cv2.matchTemplate(frame_bgr, tpl_bgr, cv2.TM_CCOEFF_NORMED)
    _, self_score, _, self_loc = cv2.minMaxLoc(res)

    # 负样本最高分
    worst = 0.0
    worst_name = "-"
    for name, im in negatives:
        if im.shape[0] < tpl_bgr.shape[0] or im.shape[1] < tpl_bgr.shape[1]:
            continue
        r = cv2.matchTemplate(im, tpl_bgr, cv2.TM_CCOEFF_NORMED)
        _, mx, _, _ = cv2.minMaxLoc(r)
        if mx > worst:
            worst, worst_name = mx, name

    # 建议阈值：自身分与负样本最高分之间，取偏保守（更靠近负样本侧）
    if negatives:
        sug = round(min(0.95, max(0.6, (self_score + worst) / 2)), 2)
    else:
        sug = 0.8

    if negatives and worst >= 0.8:
        verdict = "✗ 区分度不足：负样本最高分过高，请缩小选框或换更独特的区域"
    elif negatives and worst >= 0.7:
        verdict = "⚠ 区分度一般：建议缩小选框到文字/图标主体，或提高阈值"
    else:
        verdict = "✓ 区分度良好"
    last_verdict = verdict

    size_txt = f"{x1-x0}x{y1-y0}"
    result_var.set(
        f"选框 {size_txt} @({x0},{y0})\n"
        f"自身匹配分 : {self_score:.3f}  @({self_loc[0]},{self_loc[1]})\n"
        f"负样本最高 : {worst:.3f}  ({worst_name})\n"
        f"建议阈值   : {sug}\n"
        f"结论       : {verdict}"
    )
    log(f"框选 {size_txt}  自身 {self_score:.3f}  负样本最高 {worst:.3f}  建议阈值 {sug}")


# ---------------- 保存 ----------------

def save_template():
    if tpl_bgr is None or sel is None:
        log("请先框选区域")
        return
    name = name_var.get().strip()
    if not name:
        log("请先填写模板文件名")
        return
    name = name.replace(".png", "")
    out = os.path.join(OUT_DIR, name + ".png")
    if os.path.exists(out):
        if not messagebox.askyesno("覆盖确认", f"{name}.png 已存在，覆盖？"):
            log("已取消")
            return
    # cv2.imwrite 不支持中文路径（文件名会变成 GBK 乱码）→ imencode + tofile
    ok, buf = cv2.imencode(".png", tpl_bgr)
    assert ok, "imencode 失败"
    buf.tofile(out)
    msg = f"已保存 whmx/image/{name}.png ({tpl_bgr.shape[1]}x{tpl_bgr.shape[0]})"
    if scale_var.get():
        small = cv2.resize(tpl_bgr, None, fx=2 / 3, fy=2 / 3, interpolation=cv2.INTER_LANCZOS4)
        cv2.imwrite(os.path.join(OUT_DIR, name + "_2_3.png"), small)
        msg += " + _2_3 版"
    log(msg + "  |  " + last_verdict)
    saved_list.insert(tk.END, f"{name}.png  {tpl_bgr.shape[1]}x{tpl_bgr.shape[0]}")


# ---------------- 右侧面板 ----------------
ttk.Label(side, text="① 帧图 / 负样本", font=("", 10, "bold")).pack(anchor="w", padx=8, pady=(8, 2))
ttk.Button(side, text="⟳ 重新抓帧（F5）", command=recapture).pack(fill="x", padx=8)
ttk.Button(side, text="打开帧图…", command=open_image).pack(fill="x", padx=8, pady=2)
ttk.Button(side, text="载入负样本目录…（_tools）",
           command=lambda: load_negatives()).pack(fill="x", padx=8, pady=2)
ttk.Label(side, text="切好页面后按 F5 抓当前画面，\n再拖框即可。",
          foreground="#666", justify="left").pack(anchor="w", padx=8)

ttk.Separator(side).pack(fill="x", pady=6)
ttk.Label(side, text="② 框选预览", font=("", 10, "bold")).pack(anchor="w", padx=8)
preview_label = ttk.Label(side, background="#303030")
preview_label.pack(padx=8, pady=4)

ttk.Separator(side).pack(fill="x", pady=6)
ttk.Label(side, text="③ 校验结果", font=("", 10, "bold")).pack(anchor="w", padx=8)
result_var = tk.StringVar(value="（框选后显示）")
ttk.Label(side, textvariable=result_var, justify="left",
          font=("Consolas", 9)).pack(anchor="w", padx=8)

ttk.Separator(side).pack(fill="x", pady=6)
ttk.Label(side, text="④ 保存", font=("", 10, "bold")).pack(anchor="w", padx=8)
row = ttk.Frame(side)
row.pack(fill="x", padx=8, pady=2)
ttk.Label(row, text="文件名").pack(side="left")
name_var = tk.StringVar()
ttk.Entry(row, textvariable=name_var).pack(side="left", fill="x", expand=True, padx=4)
scale_var = tk.BooleanVar(value=False)
ttk.Checkbutton(side, text="同时生成 _2_3 缩放版（一般不需要）",
                variable=scale_var).pack(anchor="w", padx=8)
ttk.Button(side, text="保存模板", command=save_template).pack(fill="x", padx=8, pady=4)

ttk.Separator(side).pack(fill="x", pady=6)
ttk.Label(side, text="本次已保存", font=("", 10, "bold")).pack(anchor="w", padx=8)
saved_list = tk.Listbox(side, height=8)
saved_list.pack(fill="both", expand=True, padx=8, pady=4)

canvas.bind("<ButtonPress-1>", on_down)
canvas.bind("<B1-Motion>", on_move)
canvas.bind("<ButtonRelease-1>", on_up)
root.bind("<F5>", lambda e: recapture())

# ---------------- 启动参数 ----------------
args = [a for a in sys.argv[1:] if not a.startswith("--")]
neg_arg = None
if "--neg" in sys.argv:
    i = sys.argv.index("--neg")
    if i + 1 < len(sys.argv):
        neg_arg = sys.argv[i + 1]

if args:
    # 兼容 Git Bash 风格路径（/e/MaaWH/...）→ Windows 路径
    p = args[0]
    if p.startswith("/") and len(p) > 2 and p[2] == "/":
        p = p[1].upper() + ":" + p[2:]
    root.after(200, lambda: load_frame(p))
else:
    # 未传帧图：自动加载最近抓的帧，避免出现黑屏空窗口
    for cand in ("pick_frame.jpg", "latest.jpg"):
        cp = os.path.join(TOOLS_DIR, cand)
        if os.path.exists(cp):
            root.after(200, lambda c=cp: load_frame(c))
            break
if neg_arg:
    root.after(400, lambda: load_negatives(neg_arg))
elif os.path.isdir(NEG_DIR):
    root.after(400, lambda: load_negatives(NEG_DIR))

root.mainloop()
