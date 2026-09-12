# -*- coding: utf-8 -*-
"""
可视化流程编辑器：拖拽小任务节点 → 箭头连线 → 生成 MaaFramework pipeline JSON → 一键同步手机测试。

与 template_picker.py 的分工：
  template_picker  负责从帧上框出模板图（whmx/image/*.png，只读）
  flow_editor      负责把「模板点击 / 固定点击 / 滑动 / 分支 / 等待模板 / 公共节点 / 启动游戏」
                   拼成完整流程，生成节点名带 VF_ 前缀的 pipeline 文件并推送到手机。

不改动项目文件：流程定义存 FlowEditor/flows/，生成物存 FlowEditor/flows/build/，
同步时只向手机 files/taskpacks/whmx/pipeline/ 新增 vf_*.json；
测试运行走宿主直达入口 `--es entry VF_<流程名> --ez vd true`（自动建虚拟屏后跑该入口）。

用法：
  双击桌面「流程编辑器」快捷方式，或 FlowEditor/启动.bat
  python FlowEditor/flow_editor.py [某流程.flow.json]
  python FlowEditor/flow_editor.py --selftest
"""
import os
import re
import sys
import json
import glob
import bisect
import subprocess
import threading
import queue
import tkinter as tk
from tkinter import filedialog, ttk, messagebox

try:
    import cv2  # noqa: F401  与 template_picker 同依赖，缺失时提前报错
except ImportError:
    print("需要 opencv-python：pip install opencv-python")
    sys.exit(1)

from PIL import Image, ImageTk

# TOOLS_DIR = 本工具所在目录（FlowEditor/），ROOT = 项目根；整个文件夹挪位置仍可用
TOOLS_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(TOOLS_DIR)
FLOWS_DIR = os.path.join(TOOLS_DIR, "flows")
BUILD_DIR = os.path.join(FLOWS_DIR, "build")
IMG_DIR = os.path.join(ROOT, "whmx", "image")
NEG_DIR = os.path.join(ROOT, "_tools", "neg_frames")
ADB = r"D:\android-studio\Sdk\platform-tools\adb.exe"
DEVICE = "2c92e197"
PKG = "com.maawh.app"
GAME_PKG = "com.cipaishe.wuhua.bilibili"

# 虚拟屏帧基准（横屏 720p，实测 1280x720）：坐标校验/显示用；载入背景帧后按实际图尺寸更新
FRAME_W, FRAME_H = 1280, 720
FRAME_DISP_H = 880          # 竖屏帧的画布显示高度；横屏帧自动改用 FRAME_DISP_H_LS
FRAME_DISP_H_LS = 540       # 横屏帧的画布显示高度（按宽度适配，约 960 宽）
CARD_W, CARD_H = 240, 60    # 节点卡片尺寸

# ---------------- 主题 ----------------

THEME = {
    "bg":       "#171a23",   # 窗口/顶栏
    "panel":    "#1e222d",   # 侧栏面板
    "canvas":   "#14161d",   # 画布
    "grid":     "#1c2030",   # 画布网格线
    "field":    "#252a38",   # 输入框底
    "card":     "#262b38",   # 节点卡片底
    "card_hi":  "#2e3444",   # hover
    "card_line":"#3a4152",   # 卡片描边
    "shadow":   "#0c0e14",   # 卡片阴影
    "text":     "#e6e9f0",
    "text_dim": "#98a1b3",
    "accent":   "#5b8cff",
    "sel":      "#ffd24d",
    "arrow":    "#5a647e",
    "ok":       "#58c470",
    "err":      "#e05a5a",
    "warn":     "#e8c94d",
}

FONT      = ("Microsoft YaHei UI", 9)
FONT_B    = ("Microsoft YaHei UI", 9, "bold")
FONT_SM   = ("Microsoft YaHei UI", 8)
FONT_TITLE= ("Microsoft YaHei UI", 10, "bold")
LOG_FONT  = ("Consolas", 9)
ARROW_SHAPE = (11, 13, 4)

# 公共节点层（whmx/pipeline/common.json）可引用的收口节点
COMMON_NODES = [
    "Common_回主页",
    "Common_关弹窗",
    "Common_点中间",
    "Common_点中间回主页",
    "Common_回主页验证",
    "Common_确认",
    "Common_返回",
    "Common_开始训练",
]

# ---------------- 节点类型定义 ----------------
# fields: (props键, 标签, 控件类型)
# 控件类型: tpl模板下拉 / common公共节点下拉 / bool / float / int / str / roi / pick取点 / pick2取点(终点)

NODE_TYPES = {
    "tpl_click": {
        "label": "找模板点击", "icon": "◉", "color": "#4a7dbd", "light": "#8fb8ec",
        "summary": lambda p: p.get("template", "?"),
        "fields": [
            ("template", "模板图", "tpl"),
            ("threshold", "阈值(0.6~0.95)", "float"),
            ("roi", "ROI x,y,w,h (空=全屏)", "roi"),
            ("order_by", "精确单目标(Score)", "bool"),
            ("timeout", "等待超时ms", "int"),
            ("rate_limit", "识别间隔ms(移动目标建议200)", "int"),
            ("pre_delay", "点击前延时ms", "int"),
            ("post_delay", "点击后延时ms", "int"),
            ("repeat", "重复点击次数", "int"),
            ("repeat_delay", "重复间隔ms", "int"),
        ],
        "defaults": {"threshold": 0.8, "roi": "", "order_by": False, "timeout": 8000,
                     "rate_limit": 0, "pre_delay": 0, "post_delay": 800,
                     "repeat": 1, "repeat_delay": 350},
    },
    "tap": {
        "label": "固定坐标点击", "icon": "✛", "color": "#5f6fae", "light": "#a3b1e8",
        "summary": lambda p: f"({p.get('x', 0)},{p.get('y', 0)})",
        "fields": [
            ("x", "X", "pick"),
            ("y", "Y", "int"),
            ("pre_delay", "点击前延时ms", "int"),
            ("post_delay", "点击后延时ms", "int"),
            ("repeat", "重复点击次数", "int"),
            ("repeat_delay", "重复间隔ms", "int"),
        ],
        "defaults": {"x": 640, "y": 360, "pre_delay": 0, "post_delay": 500,
                     "repeat": 1, "repeat_delay": 350},
    },
    "swipe": {
        "label": "滑动", "icon": "⇅", "color": "#8a63b0", "light": "#c8a8ec",
        "summary": lambda p: f"({p.get('x1', 0)},{p.get('y1', 0)})→({p.get('x2', 0)},{p.get('y2', 0)})",
        "fields": [
            ("x1", "起点X", "pick"),
            ("y1", "起点Y", "int"),
            ("x2", "终点X", "pick2"),
            ("y2", "终点Y", "int"),
            ("duration", "时长ms(推荐900)", "int"),
            ("post_delay", "滑动后延时ms", "int"),
        ],
        "defaults": {"x1": 1000, "y1": 600, "x2": 280, "y2": 600,
                     "duration": 900, "post_delay": 600},
    },
    "wait_tpl": {
        "label": "等待模板出现", "icon": "⌛", "color": "#4a9d6e", "light": "#96dcb4",
        "summary": lambda p: p.get("template", "?"),
        "fields": [
            ("template", "模板图", "tpl"),
            ("threshold", "阈值", "float"),
            ("roi", "ROI x,y,w,h (空=全屏)", "roi"),
            ("timeout", "等待超时ms", "int"),
            ("rate_limit", "识别间隔ms(移动目标建议200)", "int"),
        ],
        "defaults": {"threshold": 0.8, "roi": "", "timeout": 10000, "rate_limit": 0},
    },
    "branch": {
        "label": "分支(模板在?)", "icon": "Ж", "color": "#c08a3e", "light": "#f0c68a",
        "summary": lambda p: p.get("template", "?"),
        "fields": [
            ("template", "模板图", "tpl"),
            ("threshold", "阈值", "float"),
            ("roi", "ROI x,y,w,h (空=全屏)", "roi"),
            ("timeout", "判定窗口ms", "int"),
            ("rate_limit", "识别间隔ms(移动目标建议200)", "int"),
        ],
        "defaults": {"threshold": 0.7, "roi": "", "timeout": 3000, "rate_limit": 0},
    },
    "common": {
        "label": "公共节点(收口)", "icon": "⌂", "color": "#4e8f8f", "light": "#9cdcdc",
        "summary": lambda p: p.get("node", "?"),
        "fields": [("node", "公共节点", "common")],
        "defaults": {"node": "Common_回主页"},
    },
    "startapp": {
        "label": "启动游戏", "icon": "▶", "color": "#b05f5f", "light": "#f0a8a8",
        "summary": lambda p: str(p.get("package", GAME_PKG)).split(".")[-1],
        "fields": [
            ("package", "包名", "str"),
            ("post_delay", "启动后延时ms", "int"),
        ],
        "defaults": {"package": GAME_PKG, "post_delay": 1000},
    },
}

TYPE_ORDER = ["tpl_click", "tap", "swipe", "wait_tpl", "branch", "common", "startapp"]


def _num(v, default=-1):
    try:
        return int(float(v))
    except (TypeError, ValueError):
        return default


def normalize_flow(flow):
    """把旧版本流程文件里存成字符串的数值字段转回 int"""
    for nd in flow.get("nodes", {}).values():
        spec = NODE_TYPES.get(nd.get("type"))
        if not spec:
            continue
        for key, _label, kind in spec["fields"]:
            if kind in ("int", "pick", "pick2") and key in nd.get("props", {}):
                try:
                    nd["props"][key] = int(float(nd["props"][key]))
                except (TypeError, ValueError):
                    pass
    return flow


def parse_roi(s):
    """'x,y,w,h' → [x,y,w,h]；非法返回 None"""
    try:
        parts = [int(v.strip()) for v in str(s).split(",")]
        if len(parts) != 4:
            return None
        return parts
    except (ValueError, TypeError):
        return None


def safe_name(name):
    return re.sub(r"[^\w\-]", "_", str(name)).strip("_") or "flow"


# ================= 核心校验/生成器（纯函数，GUI 无关） =================

def validate_flow(flow, frame_wh=(FRAME_W, FRAME_H)):
    """返回 (errors, warnings)"""
    errs, warns = [], []
    if not flow.get("name"):
        errs.append("流程名为空")
    chain = flow.get("chain", [])
    nodes = flow.get("nodes", {})
    if not chain:
        errs.append("流程没有节点")
    W, H = frame_wh
    title = lambda nd: f"「{nd.get('title', '?')}」"
    for i, nid in enumerate(chain):
        nd = nodes.get(nid)
        if nd is None:
            errs.append(f"链上有失效节点引用: {nid}")
            continue
        t, p = nd["type"], nd.get("props", {})
        no = f"#{i+1}"
        if t in ("tpl_click", "wait_tpl", "branch"):
            tpl = p.get("template", "")
            if not tpl:
                errs.append(f"{no}{title(nd)}未选择模板图")
            elif not os.path.isfile(os.path.join(IMG_DIR, tpl)):
                errs.append(f"{no}{title(nd)}模板不存在: whmx/image/{tpl}")
            if p.get("roi"):
                roi = parse_roi(p["roi"])
                if roi is None:
                    errs.append(f"{no}{title(nd)}ROI 格式应为 x,y,w,h")
                elif roi[0] < 0 or roi[1] < 0 or roi[0] + roi[2] > W or roi[1] + roi[3] > H:
                    warns.append(f"{no}{title(nd)}ROI {p['roi']} 超出画面 {W}x{H}")
        if t == "tap":
            x, y = _num(p.get("x", -1)), _num(p.get("y", -1))
            if not (0 <= x < W and 0 <= y < H):
                errs.append(f"{no}{title(nd)}点击坐标 ({p.get('x')},{p.get('y')}) "
                            f"非法或超出画面 {W}x{H}")
        if t == "swipe":
            for k in ("x1", "y1", "x2", "y2"):
                v = _num(p.get(k, -1))
                lim = W if k.startswith("x") else H
                if not (0 <= v < lim):
                    errs.append(f"{no}{title(nd)}滑动坐标 {k}={p.get(k)} "
                                f"非法或超出画面 {W}x{H}")
        if t in ("tpl_click", "wait_tpl", "branch"):
            try:
                th = float(p.get("threshold", 0))
                if not (0.3 <= th <= 0.99):
                    raise ValueError
            except (TypeError, ValueError):
                errs.append(f"{no}{title(nd)}阈值应为 0.3~0.99 的数字")
        if t == "branch":
            for port, label in (("hit_next", "✓命中"), ("miss_next", "✗未命中")):
                tgt = nd.get(port)
                if tgt is not None and tgt not in nodes:
                    errs.append(f"{no}{title(nd)}{label}出口指向已删除节点")
                elif tgt == nid:
                    errs.append(f"{no}{title(nd)}{label}出口不能指向自己")
                elif tgt is not None and tgt in chain and chain.index(tgt) < i:
                    warns.append(f"{no}{title(nd)}{label}出口跳回前面的节点（构成循环），"
                                 f"请确保循环内有终止条件（如分支/收口节点）")
        if t == "common" and i < len(chain) - 1:
            warns.append(f"{no}{title(nd)}是公共收口节点（进入后流程即终止），"
                         f"放在链中间会导致其后的节点执行不到")
    return errs, warns


def build_pipeline(flow, frame_wh=(FRAME_W, FRAME_H)):
    """流程定义 → MaaFramework pipeline dict（VF_ 前缀命名空间）。"""
    errs, _ = validate_flow(flow, frame_wh)
    if errs:
        raise FlowValidationError(errs)
    name = flow["name"]
    chain = flow["chain"]
    nodes = flow["nodes"]
    E = f"VF_{name}"

    def jname(nid):
        return f"{E}_{chain.index(nid) + 1:02d}"

    def chain_next(i):
        return [jname(chain[i + 1])] if i + 1 < len(chain) else []

    out = {E: {"next": [jname(chain[0])] if chain else []}}
    end_needed = False

    for i, nid in enumerate(chain):
        nd = nodes[nid]
        t, p = nd["type"], nd.get("props", {})
        base = jname(nid)
        nxt = chain_next(i)

        if t == "tpl_click":
            d = {
                "recognition": "TemplateMatch",
                "template": p["template"],
                "threshold": float(p["threshold"]),
                "action": "Click",
                "timeout": int(p["timeout"]),
                "post_delay": int(p["post_delay"]),
            }
            roi = parse_roi(p.get("roi", ""))
            if roi:
                d["roi"] = roi
            if p.get("order_by"):
                d["order_by"] = "Score"
            if int(p.get("rate_limit", 0) or 0) > 0:
                d["rate_limit"] = int(p["rate_limit"])
            if int(p.get("pre_delay", 0)):
                d["pre_delay"] = int(p["pre_delay"])
            rep = int(p.get("repeat", 1) or 1)
            if rep > 1:
                d["repeat"] = rep
                d["repeat_delay"] = int(p.get("repeat_delay", 350))
            if nxt:
                d["next"] = nxt
            out[base] = d

        elif t == "tap":
            d = {
                "action": "Click",
                "target": [int(p["x"]), int(p["y"])],
                "post_delay": int(p["post_delay"]),
            }
            if int(p.get("pre_delay", 0)):
                d["pre_delay"] = int(p["pre_delay"])
            rep = int(p.get("repeat", 1) or 1)
            if rep > 1:
                d["repeat"] = rep
                d["repeat_delay"] = int(p.get("repeat_delay", 350))
            if nxt:
                d["next"] = nxt
            out[base] = d

        elif t == "swipe":
            d = {
                "action": "Swipe",
                "begin": [int(p["x1"]), int(p["y1"])],
                "end": [int(p["x2"]), int(p["y2"])],
                "duration": int(p["duration"]),
                "post_delay": int(p["post_delay"]),
            }
            if nxt:
                d["next"] = nxt
            out[base] = d

        elif t == "wait_tpl":
            d = {
                "recognition": "TemplateMatch",
                "template": p["template"],
                "threshold": float(p["threshold"]),
                "action": "DoNothing",
                "timeout": int(p["timeout"]),
            }
            roi = parse_roi(p.get("roi", ""))
            if roi:
                d["roi"] = roi
            if int(p.get("rate_limit", 0) or 0) > 0:
                d["rate_limit"] = int(p["rate_limit"])
            if nxt:
                d["next"] = nxt
            out[base] = d

        elif t == "branch":
            # 分叉容器（项目踩坑结论：on_error 只挂在容器节点上才生效）
            hit, miss = nd.get("hit_next"), nd.get("miss_next")
            hit_ref = [jname(hit)] if hit else nxt        # 未连线 → 自动链中下一个
            miss_ref = [jname(miss)] if miss else [f"{E}_End"]
            if not miss:
                end_needed = True
            out[base] = {
                "action": "DoNothing",
                "timeout": int(p["timeout"]),
                "next": [base + "_Hit"],
                "on_error": miss_ref,
            }
            hd = {
                "recognition": "TemplateMatch",
                "template": p["template"],
                "threshold": float(p["threshold"]),
                "action": "DoNothing",
            }
            roi = parse_roi(p.get("roi", ""))
            if roi:
                hd["roi"] = roi
            if int(p.get("rate_limit", 0) or 0) > 0:
                hd["rate_limit"] = int(p["rate_limit"])
            if hit_ref:
                hd["next"] = hit_ref
            out[base + "_Hit"] = hd

        elif t == "common":
            out[base] = {"next": [p["node"]]}

        elif t == "startapp":
            d = {
                "action": "StartApp",
                "package": p["package"],
                "post_delay": int(p["post_delay"]),
            }
            if nxt:
                d["next"] = nxt
            out[base] = d

    if end_needed:
        out[f"{E}_End"] = {"action": "DoNothing", "next": []}
    return out


class FlowValidationError(Exception):
    def __init__(self, errs):
        super().__init__("; ".join(errs))
        self.errors = errs


# ================= 流程文件读写 =================

def new_flow(name="测试流程"):
    return {"name": name, "chain": [], "nodes": {}}


def flow_path(name):
    return os.path.join(FLOWS_DIR, safe_name(name) + ".flow.json")


def save_flow(flow):
    os.makedirs(FLOWS_DIR, exist_ok=True)
    path = flow_path(flow["name"])
    with open(path, "w", encoding="utf-8") as f:
        json.dump(flow, f, ensure_ascii=False, indent=2)
    return path


def list_flows():
    if not os.path.isdir(FLOWS_DIR):
        return []
    return sorted(glob.glob(os.path.join(FLOWS_DIR, "*.flow.json")))


def list_templates():
    files = glob.glob(os.path.join(IMG_DIR, "*.png"))
    files.sort(key=os.path.getmtime, reverse=True)   # 越晚存入越靠前
    return [os.path.basename(p) for p in files]


def write_pipeline_json(flow, frame_wh):
    """生成并写盘 build/vf_<名>.json，返回 (path, data)"""
    os.makedirs(BUILD_DIR, exist_ok=True)
    data = build_pipeline(flow, frame_wh)
    path = os.path.join(BUILD_DIR, f"vf_{safe_name(flow['name'])}.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=4)
    return path, data


# ================= ADB =================

def adb(*args, timeout=90):
    return subprocess.run([ADB, "-s", DEVICE, *args],
                          capture_output=True, timeout=timeout)


def adb_text(r):
    return (r.stdout or b"").decode("utf-8", "replace").strip()


def jsonc_loads(text):
    """解析带 // 与 /* */ 注释的 JSON（字符串感知）"""
    out, i, n = [], 0, len(text)
    in_str = False
    while i < n:
        ch = text[i]
        if in_str:
            out.append(ch)
            if ch == "\\" and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if ch == '"':
                in_str = False
            i += 1
            continue
        if ch == '"':
            in_str = True
            out.append(ch)
            i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
            continue
        out.append(ch)
        i += 1
    return json.loads("".join(out))


def upsert_flow_task(data, flow_name):
    """往清单 task 数组注册/更新可视化流程条目（小工具分组）；保留其它 VF_ 条目"""
    entry = f"VF_{flow_name}"
    tasks = data.setdefault("task", [])
    tasks[:] = [t for t in tasks if t.get("entry") != entry]
    tasks.append({"name": flow_name, "label": flow_name, "entry": entry,
                  "group": ["tools"]})
    return data


def register_on_phone(flow_name, log):
    """把流程注册进手机端 interface.json（group=tools），重启 App 后出现在【小工具】栏。
    只改手机上的运行副本，本地 whmx/interface.json 不动；改前手机端备份 .bak。"""
    text = adb_text(adb("shell",
                        f"run-as {PKG} sh -c 'cat files/taskpacks/whmx/interface.json'"))
    if not text:
        raise RuntimeError("读取手机 interface.json 失败（任务包是否已安装?）")
    data = jsonc_loads(text)
    upsert_flow_task(data, flow_name)
    adb("shell",
        f"run-as {PKG} sh -c 'cp files/taskpacks/whmx/interface.json"
        f" files/taskpacks/whmx/interface.json.bak'")
    local = os.path.join(BUILD_DIR, "_interface.json")
    os.makedirs(BUILD_DIR, exist_ok=True)
    with open(local, "w", encoding="utf-8") as f:
        f.write(json.dumps(data, ensure_ascii=False, indent=2))
    remote = "/data/local/tmp/_vf_interface.json"
    r = adb("push", local, remote, timeout=120)
    if r.returncode != 0:
        raise RuntimeError("push interface.json 失败: " +
                           (r.stderr.decode("utf-8", "replace") or adb_text(r)))
    adb("shell", "chmod", "644", remote)
    adb("shell",
        f"run-as {PKG} sh -c 'cp {remote} files/taskpacks/whmx/interface.json'")
    check = adb_text(adb("shell",
                         f"run-as {PKG} sh -c 'cat files/taskpacks/whmx/interface.json'"))
    if f"VF_{flow_name}" not in check:
        raise RuntimeError("写回 interface.json 后验证失败（未看到新流程条目）")
    log(f"已注册到手机【小工具】清单: {flow_name} → entry VF_{flow_name}"
        f"（原清单已备份为 interface.json.bak）")


def grab_frame_to(path):
    """FrameSave 三步抓帧 → 存到 path；返回错误信息或 None"""
    import time as _t
    adb("shell", f"run-as {PKG} sh -c 'rm -f files/cur_frame.jpg'")
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    _t.sleep(1.5)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity",
        "--activity-single-top", "--es", "entry", "FrameSave")
    _t.sleep(5)
    out = adb("exec-out", "run-as", PKG, "cat", "files/cur_frame.jpg")
    data = out.stdout
    if not data or len(data) < 2000:
        return "抓帧失败：帧为空。请确认 App 在前台、虚拟屏已启动、游戏画面正常。"
    with open(path, "wb") as f:
        f.write(data)
    return None


def sync_pipeline_file(json_path, log):
    """推送生成物到手机 pipeline 目录（只新增 vf_*.json，不动项目文件）。"""
    base = os.path.basename(json_path)
    remote = "/data/local/tmp/" + base
    log(f"推送 {base} …")
    r = adb("push", json_path, remote, timeout=120)
    if r.returncode != 0:
        raise RuntimeError("push 失败: " +
                           (r.stderr.decode("utf-8", "replace") or adb_text(r)))
    adb("shell", "chmod", "644", remote)
    adb("shell",
        f"run-as {PKG} sh -c 'cp {remote} files/taskpacks/whmx/pipeline/{base}'",
        timeout=60)
    names = adb_text(adb("shell",
                         f"run-as {PKG} sh -c 'ls files/taskpacks/whmx/pipeline/'")).splitlines()
    if base not in names:
        raise RuntimeError("同步后未在手机上找到该文件（cp 失败，App 是否有 run-as 权限?）")
    log(f"已同步到 files/taskpacks/whmx/pipeline/{base}")


def flow_templates(flow):
    """收集流程引用的全部模板图文件名"""
    return sorted({nd["props"]["template"] for nd in flow["nodes"].values()
                   if nd.get("type") in ("tpl_click", "wait_tpl", "branch")
                   and nd["props"].get("template")})


def sync_templates(flow, log, progress=None):
    """把流程引用的模板图推到手机 image 目录（缺哪张补哪张，最后验证）"""
    tpls = flow_templates(flow)
    if not tpls:
        return
    missing_pc = [t for t in tpls if not os.path.isfile(os.path.join(IMG_DIR, t))]
    if missing_pc:
        raise RuntimeError("模板文件不存在，无法同步: " + ", ".join(missing_pc))
    have = set(adb_text(adb("shell",
                f"run-as {PKG} sh -c 'ls files/taskpacks/whmx/image/'")).splitlines())
    todo = [t for t in tpls if t not in have]
    if not todo:
        log(f"模板图 {len(tpls)} 张手机上已齐，跳过推送")
        return
    log(f"同步模板图 {len(todo)}/{len(tpls)} 张…")
    for i, name in enumerate(todo):
        if progress:
            progress(30 + 25 * i // len(todo),
                     f"同步模板图 {i + 1}/{len(todo)}：{name}")
        remote = "/data/local/tmp/_vt_" + name
        r = adb("push", os.path.join(IMG_DIR, name), remote, timeout=60)
        if r.returncode != 0:
            raise RuntimeError(f"push 模板 {name} 失败")
        adb("shell", "chmod", "644", remote)
        adb("shell", f"run-as {PKG} sh -c 'cp {remote} files/taskpacks/whmx/image/{name}'")
    names = adb_text(adb("shell",
                         f"run-as {PKG} sh -c 'ls files/taskpacks/whmx/image/'")).splitlines()
    missing = [t for t in tpls if t not in names]
    if missing:
        raise RuntimeError("手机 image 目录仍缺模板: " + ", ".join(missing))
    log(f"✓ 模板图已同步 {len(todo)} 张")


def launch_on_phone(entry_name, log, status, ask=True):
    """重启 App（引擎重新加载 pipeline）并用直达入口运行；vd=true 自动先建虚拟屏。"""
    if ask and not messagebox.askyesno(
            "同步并运行",
            "将 force-stop 重启 App 以加载新流程（虚拟屏会被清掉，运行时会自动重建），并立即运行：\n\n"
            f"  {entry_name}\n\n继续？"):
        return
    import time as _t
    log("重启 App（force-stop 会清虚拟屏，稍后自动重建）…")
    status("重启 App 中…")
    adb("shell", "am", "force-stop", PKG)
    _t.sleep(1.5)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    _t.sleep(2)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--activity-single-top",
        "--es", "entry", entry_name, "--ez", "vd", "true")
    log(f"已下发直达入口: entry={entry_name} vd=true（任务进入小工具队列自动执行，虚拟屏会先重建）")
    log("查看运行日志：手机端 App 日志区，或 adb logcat -s MaaWH")


# ================= GUI =================

def _round_rect(c, x0, y0, x1, y1, r, tags=(), **kw):
    """圆角矩形（smooth polygon）"""
    pts = [x0 + r, y0, x1 - r, y0, x1, y0, x1, y0 + r,
           x1, y1 - r, x1, y1, x1 - r, y1, x0 + r, y1,
           x0, y1, x0, y1 - r, x0, y0 + r, x0, y0]
    return c.create_polygon(pts, smooth=True, tags=tags, **kw)


class SyncDialog(tk.Toplevel):
    """同步进度小窗：步骤文字 + 进度条；完成（成功 2.5s 自动关）/失败停留"""

    def __init__(self, parent, title):
        super().__init__(parent)
        self.title(title)
        self.configure(bg=THEME["panel"])
        self.resizable(False, False)
        self.transient(parent)
        self.attributes("-topmost", True)
        self.geometry("+%d+%d" % (parent.winfo_rootx() + parent.winfo_width() // 2 - 190,
                                  parent.winfo_rooty() + parent.winfo_height() // 2 - 60))
        ttk.Label(self, text=title, style="Title.TLabel").pack(pady=(14, 4))
        self.var_text = tk.StringVar(value="准备中…")
        ttk.Label(self, textvariable=self.var_text, style="Dim.TLabel").pack()
        self.bar = ttk.Progressbar(self, length=320, maximum=100, value=2,
                                   mode="determinate")
        self.bar.pack(padx=26, pady=14)
        self.btn = ttk.Button(self, text="关闭", command=self.destroy, state="disabled")
        self.btn.pack(pady=(0, 14))
        self._done = False
        self.protocol("WM_DELETE_WINDOW", self._try_close)
        self.lift()
        self.focus_force()

    def set_progress(self, pct, text):
        self.var_text.set(text)
        self.bar.configure(value=pct)

    def finish(self, ok, text):
        self._done = True
        self.var_text.set(text)
        self.bar.configure(value=100)
        self.btn.config(state="normal")
        if ok:
            self.after(2500, self.destroy)

    def _try_close(self):
        if self._done:
            self.destroy()


class FlowEditor:
    def __init__(self, root, load_path=None):
        self.root = root
        root.title("流程编辑器 · MaaWH")
        root.geometry("1640x960")
        root.configure(bg=THEME["bg"])
        self._setup_style()

        self.flow = new_flow()
        self.frame_wh = (FRAME_W, FRAME_H)
        self.bg_pil = None          # 背景帧 PIL（调暗后）
        self.bg_photo = None
        self.bg_disp = None         # (ox, oy, w, h) 帧显示区域
        self.show_bg = tk.BooleanVar(value=True)
        self.sel = None
        self.templates = list_templates()
        self._frame_file = None     # 当前背景帧文件路径（联动框选工具）
        self.pick_target = None     # 取坐标模式: "x" | "x1" | "x2"
        self.drag = None
        self.wire = None
        self.prop_widgets = {}
        self._tpl_img_cache = {}   # 模板名 -> (mtime, PhotoImage, dw, dh)
        self._tpl_pop = None       # 模板自动补全弹出列表
        self._nid = 0
        self._syncing = False

        self._build_toolbar()
        self._build_statusbar()
        self._build_palette()
        self._build_canvas()
        self._build_props()
        self.redraw()

        if load_path:
            self.root.after(200, lambda: self.open_flow_file(load_path))
        elif list_flows():
            self.root.after(200, lambda: self.open_flow_file(list_flows()[0]))

    # ---------- 主题 ----------

    def _setup_style(self):
        s = ttk.Style(self.root)
        s.theme_use("clam")
        T = THEME
        s.configure(".", background=T["panel"], foreground=T["text"], font=FONT)
        s.configure("TFrame", background=T["panel"])
        s.configure("Bar.TFrame", background=T["bg"])
        s.configure("TLabel", background=T["panel"], foreground=T["text"])
        s.configure("Title.TLabel", font=FONT_TITLE, foreground=T["text"])
        s.configure("Dim.TLabel", foreground=T["text_dim"], font=FONT_SM)
        s.configure("TEntry", fieldbackground=T["field"], foreground=T["text"],
                    insertcolor=T["text"], bordercolor=T["card_line"],
                    lightcolor=T["field"], darkcolor=T["field"])
        s.map("TEntry", bordercolor=[("focus", T["accent"])],
              lightcolor=[("focus", T["field"])], darkcolor=[("focus", T["field"])])
        s.configure("TCombobox", fieldbackground=T["field"], foreground=T["text"],
                    arrowcolor=T["text_dim"], bordercolor=T["card_line"],
                    lightcolor=T["field"], darkcolor=T["field"],
                    selectbackground=T["accent"], selectforeground=T["text"])
        s.map("TCombobox",
              fieldbackground=[("readonly", T["field"])],
              foreground=[("readonly", T["text"])],
              bordercolor=[("focus", T["accent"])])
        self.root.option_add("*TCombobox*Listbox.background", T["field"])
        self.root.option_add("*TCombobox*Listbox.foreground", T["text"])
        self.root.option_add("*TCombobox*Listbox.selectBackground", T["accent"])
        self.root.option_add("*TCombobox*Listbox.selectForeground", T["text"])
        s.configure("TSpinbox", fieldbackground=T["field"], foreground=T["text"],
                    arrowcolor=T["text_dim"], bordercolor=T["card_line"],
                    lightcolor=T["field"], darkcolor=T["field"])
        s.configure("TCheckbutton", background=T["panel"], foreground=T["text"],
                    focuscolor=T["panel"])
        s.map("TCheckbutton",
              background=[("active", T["panel"])],
              indicatorcolor=[("selected", T["accent"]), ("!selected", T["field"])])
        s.configure("TSeparator", background="#323949")
        s.configure("Horizontal.TProgressbar", background=T["accent"],
                    troughcolor=T["field"], bordercolor=T["panel"],
                    lightcolor=T["accent"], darkcolor=T["accent"])

    def _flat_btn(self, parent, text, cmd, bg=None, fg=None, hover=None,
                  font=FONT, padx=12):
        T = THEME
        bg = bg or T["card"]
        fg = fg or T["text"]
        hover = hover or T["card_hi"]
        b = tk.Button(parent, text=text, command=cmd, bg=bg, fg=fg,
                      activebackground=hover, activeforeground=fg,
                      relief="flat", bd=0, padx=padx, pady=4,
                      font=font, cursor="hand2", highlightthickness=0)
        b.bind("<Enter>", lambda e: b.config(bg=hover))
        b.bind("<Leave>", lambda e: b.config(bg=bg))
        return b

    def _vsep(self, parent):
        bar = tk.Frame(parent, width=1, bg="#333a4a")
        bar.pack(side="left", fill="y", padx=9, pady=6)
        return bar

    # ---------- 布局构建 ----------

    def _build_toolbar(self):
        bar = ttk.Frame(self.root, style="Bar.TFrame")
        bar.pack(fill="x")
        ttk.Label(bar, text="流程名", style="Dim.TLabel",
                  background=THEME["bg"]).pack(side="left", padx=(12, 4))
        self.name_var = tk.StringVar(value=self.flow["name"])
        ent = ttk.Entry(bar, textvariable=self.name_var, width=15, font=FONT)
        ent.pack(side="left", ipady=3)
        ent.bind("<KeyRelease>", lambda e: self._on_name_change())

        self._flat_btn(bar, "✚ 新建", self.on_new).pack(side="left", padx=(12, 3))
        self.flow_combo = ttk.Combobox(bar, width=18, state="readonly", font=FONT,
                                       values=[os.path.basename(p) for p in list_flows()])
        self.flow_combo.pack(side="left", padx=3, ipady=2)
        self._flat_btn(bar, "打开 ▶", self.on_open_selected).pack(side="left", padx=3)
        self._flat_btn(bar, "保存", self.on_save).pack(side="left", padx=3)

        self._vsep(bar)
        self._flat_btn(bar, "✓ 校验", self.on_validate).pack(side="left", padx=3)
        self._flat_btn(bar, "⤓ 生成 JSON", self.on_build).pack(side="left", padx=3)

        self._vsep(bar)
        self._btn_sync = self._flat_btn(
            bar, "⇲ 同步到手机", lambda: self.on_sync(run_after=False),
            bg="#2c4a86", fg="#eaf1ff", hover="#3a5da8", font=FONT_B)
        self._btn_sync.pack(side="left", padx=3, ipady=1)
        self._btn_run = self._flat_btn(
            bar, "▶ 同步并运行", lambda: self.on_sync(run_after=True),
            bg="#2c6e48", fg="#eafff2", hover="#3a8a5c", font=FONT_B)
        self._btn_run.pack(side="left", padx=3, ipady=1)
        ttk.Label(bar, text="  同步后需重启 App 生效",
                  style="Dim.TLabel", background=THEME["bg"]).pack(side="left")

    def _build_palette(self):
        left = ttk.Frame(self.root, width=172)
        left.pack(side="left", fill="y")
        left.pack_propagate(False)
        ttk.Label(left, text="  节 点 库", style="Title.TLabel").pack(
            anchor="w", padx=8, pady=(10, 4))
        ttk.Label(left, text="  点击添加到流程末尾", style="Dim.TLabel").pack(
            anchor="w", padx=8, pady=(0, 6))
        for t in TYPE_ORDER:
            spec = NODE_TYPES[t]
            b = tk.Button(left, text=f" {spec['icon']}  {spec['label']}",
                          command=lambda tt=t: self.add_node(tt),
                          bg=THEME["panel"], fg=spec["light"],
                          activebackground=THEME["card_hi"],
                          activeforeground=spec["light"],
                          relief="flat", bd=0, anchor="w", padx=14, pady=6,
                          font=FONT, cursor="hand2", highlightthickness=0)
            b.pack(fill="x", padx=8, pady=1)
            b.bind("<Enter>", lambda e, bb=b: bb.config(bg=THEME["card_hi"]))
            b.bind("<Leave>", lambda e, bb=b: bb.config(bg=THEME["panel"]))
        self._flat_btn(left, "✥  整理布局", self.tidy_layout,
                       font=FONT_SM).pack(fill="x", padx=8, pady=(12, 0))

        ttk.Separator(left).pack(fill="x", pady=12, padx=8)
        ttk.Label(left, text="  背景帧 · 对照坐标", style="Title.TLabel").pack(
            anchor="w", padx=8, pady=(0, 4))
        self._flat_btn(left, "⟳  抓帧 (F5)", self.on_capture).pack(fill="x", padx=8, pady=1)
        self._flat_btn(left, "✛  框选模板…", self.on_pick_template).pack(fill="x", padx=8, pady=1)
        self._flat_btn(left, "📂  打开帧图…", self.on_open_frame).pack(fill="x", padx=8, pady=1)
        ttk.Checkbutton(left, text="显示背景帧", variable=self.show_bg,
                        command=self.redraw).pack(anchor="w", padx=12, pady=3)
        self.frame_lbl = ttk.Label(left, text="", style="Dim.TLabel", justify="left")
        self.frame_lbl.pack(anchor="w", padx=12, pady=2)
        self._update_frame_label()

    def _build_canvas(self):
        self.canvas = tk.Canvas(self.root, bg=THEME["canvas"], highlightthickness=0)
        self.canvas.pack(side="left", fill="both", expand=True)
        self.canvas.bind("<ButtonPress-1>", self.on_down)
        self.canvas.bind("<B1-Motion>", self.on_motion)
        self.canvas.bind("<ButtonRelease-1>", self.on_up)
        self.canvas.bind("<MouseWheel>", self._on_mousewheel)
        self.canvas.bind("<Delete>", self.on_delete_key)
        self.root.bind("<F5>", lambda e: self.on_capture())

    def _build_props(self):
        right = ttk.Frame(self.root, width=344)
        right.pack(side="right", fill="y")
        right.pack_propagate(False)
        ttk.Label(right, text="  节点属性", style="Title.TLabel").pack(
            anchor="w", padx=8, pady=(10, 2))
        self.props_inner = ttk.Frame(right)
        self.props_inner.pack(fill="x", padx=12)
        prow = ttk.Frame(right)
        prow.pack(fill="x", padx=10, pady=6)
        self._flat_btn(prow, "↑ 上移", lambda: self.move_node(-1), padx=8).pack(side="left", padx=2)
        self._flat_btn(prow, "↓ 下移", lambda: self.move_node(1), padx=8).pack(side="left", padx=2)
        self._flat_btn(prow, "⇥ 挪到侧列", lambda: self.align_node(), padx=8,
                       font=FONT_SM).pack(side="left", padx=2)
        self._flat_btn(prow, "✖ 删除", self.delete_selected, padx=8,
                       bg="#5a2733", fg="#ffc9d2", hover="#74323f",
                       font=FONT_SM).pack(side="left", padx=(12, 0))

        ttk.Separator(right).pack(fill="x", pady=6, padx=8)
        ttk.Label(right, text="  分支出口（画布拖端口或下拉改接）",
                  style="Title.TLabel").pack(anchor="w", padx=8)
        brow = ttk.Frame(right)
        brow.pack(fill="x", padx=12, pady=4)
        ttk.Label(brow, text="✓", foreground=THEME["ok"],
                  background=THEME["panel"]).pack(side="left")
        self.hit_combo = ttk.Combobox(brow, width=19, state="disabled", font=FONT_SM)
        self.hit_combo.pack(side="left", padx=(2, 8))
        self.hit_combo.bind("<<ComboboxSelected>>", lambda e: self.on_branch_combo("hit_next"))
        ttk.Label(brow, text="✗", foreground=THEME["err"],
                  background=THEME["panel"]).pack(side="left")
        self.miss_combo = ttk.Combobox(brow, width=19, state="disabled", font=FONT_SM)
        self.miss_combo.pack(side="left", padx=2)
        self.miss_combo.bind("<<ComboboxSelected>>", lambda e: self.on_branch_combo("miss_next"))
        self.branch_hint = ttk.Label(right, text="", style="Dim.TLabel",
                                     justify="left", wraplength=310)
        self.branch_hint.pack(anchor="w", padx=12, pady=(2, 0))

        ttk.Separator(right).pack(fill="x", pady=8, padx=8)
        ttk.Label(right, text="  日志", style="Title.TLabel").pack(anchor="w", padx=8)
        logf = tk.Frame(right, bg=THEME["card_line"])
        logf.pack(fill="both", expand=True, padx=10, pady=(4, 10))
        self.log_text = tk.Text(logf, height=14, bg="#12141c", fg="#c6cede",
                                font=LOG_FONT, state="disabled", relief="flat",
                                padx=8, pady=6, selectbackground=THEME["accent"],
                                insertbackground=THEME["text"])
        self.log_text.tag_config("err", foreground="#ff8a8a")
        self.log_text.tag_config("warn", foreground=THEME["warn"])
        self.log_text.tag_config("ok", foreground=THEME["ok"])
        self.log_text.pack(fill="both", expand=True, padx=1, pady=1)

    def _build_statusbar(self):
        self.status_var = tk.StringVar(value="就绪 · F5 抓帧 ｜ 拖动节点排序 ｜ 拖分支端口连线 ｜ Delete 删除选中节点")
        tk.Label(self.root, textvariable=self.status_var, bg=THEME["bg"],
                 fg=THEME["text_dim"], anchor="w", padx=10, pady=3,
                 font=FONT_SM).pack(fill="x", side="bottom")

    # ---------- 日志/状态 ----------

    def log(self, msg, tag=None):
        self.log_text.config(state="normal")
        self.log_text.insert("end", msg + "\n", tag or ())
        self.log_text.see("end")
        self.log_text.config(state="disabled")
        print(msg)

    def status(self, s):
        self.status_var.set(s)

    # ---------- 流程文件操作 ----------

    def _on_name_change(self):
        self.flow["name"] = self.name_var.get().strip() or "未命名"

    def on_new(self):
        self.flow = new_flow("测试流程")
        self.name_var.set(self.flow["name"])
        self.sel = None
        self.build_prop_panel()
        self.redraw()
        self.log("已新建空白流程")

    def on_open_selected(self, *_):
        sel = self.flow_combo.get()
        if sel:
            self.open_flow_file(os.path.join(FLOWS_DIR, sel))

    def open_flow_file(self, path):
        try:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
            if "chain" not in data or "nodes" not in data:
                raise ValueError("不是流程定义文件")
            self.flow = normalize_flow(data)
            self.flow.setdefault("chain", [])
            self.flow.setdefault("nodes", {})
            self.name_var.set(self.flow.get("name", "未命名"))
            self.sel = None
            self.flow_combo.set(os.path.basename(path))
            self.build_prop_panel()
            self.redraw()
            self.canvas.yview_moveto(0)
            self.canvas.xview_moveto(0)
            self.log(f"已打开 {os.path.basename(path)}（{len(self.flow['chain'])} 个节点）")
        except Exception as e:
            messagebox.showerror("打开失败", str(e))

    def on_save(self):
        self._on_name_change()
        path = save_flow(self.flow)
        self.flow_combo.config(values=[os.path.basename(p) for p in list_flows()])
        self.flow_combo.set(os.path.basename(path))
        self.log("✓ 已保存 " + path, "ok")

    # ---------- 节点增删改 ----------

    def add_node(self, ntype, props=None, hit_next=None, miss_next=None, pos=None):
        self._nid += 1
        nid = f"n{self._nid:03d}{os.urandom(2).hex()}"
        spec = NODE_TYPES[ntype]
        p = dict(spec["defaults"])
        if props:
            p.update(props)
        if pos:
            x, y = pos
        else:
            last = self.flow["chain"][-1] if self.flow["chain"] else None
            if last:
                nd = self.flow["nodes"][last]
                x, y = nd["x"], nd["y"] + CARD_H + 30
            else:
                x, y = self._chain_col_x(), 60
        node = {"type": ntype, "x": x, "y": y, "title": spec["label"], "props": p}
        if ntype == "branch":
            node["hit_next"] = hit_next
            node["miss_next"] = miss_next
        self.flow["nodes"][nid] = node
        self.flow["chain"].append(nid)
        self.sel = nid
        self.build_prop_panel()
        self.redraw()
        self._scroll_to(y)
        return nid

    def delete_selected(self):
        nid = self.sel
        if not nid:
            return
        self.flow["nodes"].pop(nid, None)
        if nid in self.flow["chain"]:
            self.flow["chain"].remove(nid)
        for other in self.flow["nodes"].values():
            if other.get("hit_next") == nid:
                other["hit_next"] = None
            if other.get("miss_next") == nid:
                other["miss_next"] = None
        self.sel = None
        self.build_prop_panel()
        self.redraw()
        self.log("已删除节点")

    def on_delete_key(self, _e):
        self.delete_selected()

    def move_node(self, d):
        nid = self.sel
        if not nid or nid not in self.flow["chain"]:
            return
        ch = self.flow["chain"]
        i = ch.index(nid)
        j = i + d
        if 0 <= j < len(ch):
            ch[i], ch[j] = ch[j], ch[i]
            self.build_prop_panel()
            self.redraw()

    def align_node(self):
        nid = self.sel
        if not nid:
            return
        self.flow["nodes"][nid]["x"] = self._side_col_x()
        self.redraw()

    def tidy_layout(self):
        x = self._chain_col_x()
        y = 50
        for nid in self.flow["chain"]:
            nd = self.flow["nodes"][nid]
            nd["x"] = x
            nd["y"] = y
            y += CARD_H + 30
        self.redraw()

    def _chain_col_x(self):
        fw = self.bg_disp[2] if self.bg_disp else 750
        return fw + 130

    def _side_col_x(self):
        return self._chain_col_x() + CARD_W + 100

    def _scroll_to(self, y):
        sr = self.canvas.cget("scrollregion").split()
        h = float(sr[3]) if len(sr) == 4 else 2000
        self.canvas.yview_moveto(max(0.0, (y - 120) / max(1.0, h)))

    def _on_mousewheel(self, e):
        self.canvas.yview_scroll(int(-e.delta / 120), "units")

    # ---------- 背景帧 ----------

    def on_capture(self):
        self.status("抓帧中…")
        self.root.update()
        path = os.path.join(TOOLS_DIR, "pick_frame.jpg")
        err = grab_frame_to(path)
        if err:
            self.log(err, "err")
            self.status("抓帧失败")
            return
        self.load_frame(path)
        self.log(f"已抓帧 {path}")

    def on_pick_template(self):
        """唤起模板框选工具（带负样本校验）；保存到 whmx/image 后模板下拉即可选到"""
        tool = os.path.join(TOOLS_DIR, "template_picker.py")
        if not os.path.isfile(tool):
            messagebox.showerror("找不到工具", tool)
            return
        os.makedirs(NEG_DIR, exist_ok=True)
        args = [sys.executable, tool]
        if self._frame_file and os.path.isfile(self._frame_file):
            args.append(self._frame_file)          # 联动当前背景帧
        args += ["--neg", NEG_DIR]
        logf = open(os.path.join(TOOLS_DIR, "template_picker.log"), "a", encoding="utf-8")
        subprocess.Popen(args, stdout=logf, stderr=logf, cwd=TOOLS_DIR)
        self.log("已打开模板框选工具。框完保存后，选中节点在「模板图」下拉里直接选新模板。")

    def on_open_frame(self):
        p = filedialog.askopenfilename(title="选择帧图", initialdir=TOOLS_DIR,
                                       filetypes=[("图片", "*.png *.jpg *.jpeg")])
        if p:
            self.load_frame(p)

    def load_frame(self, path):
        img = Image.open(path).convert("RGB")
        self._frame_file = path
        self.frame_wh = img.size
        dark = Image.new("RGB", img.size, (24, 26, 34))
        self.bg_pil = Image.blend(dark, img, 0.62)
        self._update_frame_label()
        self.redraw()
        self.log(f"背景帧 {os.path.basename(path)}  {img.size[0]}x{img.size[1]}")

    def _update_frame_label(self):
        self.frame_lbl.config(text=f"  当前 {self.frame_wh[0]}×{self.frame_wh[1]}"
                                   f"（基准 {FRAME_W}×{FRAME_H}）")

    # ---------- 绘制 ----------

    def redraw(self):
        c = self.canvas
        c.delete("all")
        ox, oy = 44, 44
        # 网格
        max_x, max_y = 1400, 1600
        if self.bg_pil is not None and self.show_bg.get():
            max_y = max(max_y, oy + FRAME_DISP_H + 80)
        for nd in self.flow["nodes"].values():
            max_x = max(max_x, nd["x"] + CARD_W + 160)
            max_y = max(max_y, nd["y"] + CARD_H + 140)
        step = 46
        gx = step
        while gx < max_x:
            c.create_line(gx, 0, gx, max_y, fill=THEME["grid"])
            gx += step
        gy = step
        while gy < max_y:
            c.create_line(0, gy, max_x, gy, fill=THEME["grid"])
            gy += step
        # 背景帧
        if self.bg_pil is not None and self.show_bg.get():
            disp_h = (FRAME_DISP_H if self.bg_pil.height >= self.bg_pil.width
                      else FRAME_DISP_H_LS)
            scale = disp_h / self.bg_pil.height
            dw = int(self.bg_pil.width * scale)
            self.bg_disp = (ox, oy, dw, disp_h)
            self.bg_photo = ImageTk.PhotoImage(
                self.bg_pil.resize((dw, disp_h), Image.LANCZOS))
            _round_rect(c, ox - 2, oy - 2, ox + dw + 2, oy + disp_h + 2, 8,
                        fill=THEME["card_line"], outline="")
            c.create_image(ox, oy, anchor="nw", image=self.bg_photo)
            c.create_text(ox + 10, oy + 10, anchor="nw", fill="#cfd6e6",
                          font=FONT_SM,
                          text=f" 参考帧 {self.frame_wh[0]}×{self.frame_wh[1]} ")
            self._draw_overlays()
        else:
            self.bg_disp = None
        # 主链箭头（按 chain 顺序纵向连接）
        ch = self.flow["chain"]
        for i in range(len(ch) - 1):
            a, b = self.flow["nodes"][ch[i]], self.flow["nodes"][ch[i + 1]]
            x1, y1 = a["x"] + CARD_W / 2, a["y"] + CARD_H
            x2, y2 = b["x"] + CARD_W / 2, b["y"]
            mid = (y1 + y2) / 2
            c.create_line(x1, y1, x1, mid, x2, mid, x2, y2 - 2, smooth=True,
                          width=2, arrow=tk.LAST, fill=THEME["arrow"],
                          arrowshape=ARROW_SHAPE, splinesteps=24)
        # 分支出口连线
        for nid in ch:
            nd = self.flow["nodes"][nid]
            if nd["type"] != "branch":
                continue
            for port, target, color in (("hit_next", nd.get("hit_next"), THEME["ok"]),
                                        ("miss_next", nd.get("miss_next"), THEME["err"])):
                sx, sy = self._port_pos(nd, port)
                if target and target in self.flow["nodes"]:
                    t = self.flow["nodes"][target]
                    ex, ey = t["x"] + CARD_W / 2, t["y"]
                    if abs(ex - sx) < 8:
                        c.create_line(sx, sy, ex, ey - 2, width=2, fill=color,
                                      arrow=tk.LAST, smooth=True,
                                      arrowshape=ARROW_SHAPE, splinesteps=24)
                    else:
                        mx = max(sx, ex) + 52
                        c.create_line(sx, sy, mx, sy, mx, ey, ex, ey - 2, smooth=True,
                                      width=2, fill=color, arrow=tk.LAST,
                                      arrowshape=ARROW_SHAPE, splinesteps=24)
                else:
                    if port == "hit_next":
                        i = ch.index(nid)
                        auto = ch[i + 1] if i + 1 < len(ch) else None
                        txt = "自动→下一个" if auto else "→结束"
                    else:
                        txt = "→结束"
                    c.create_line(sx, sy, sx + 26, sy, fill=color, width=2)
                    tw = 12 * len(txt) + 14
                    _round_rect(c, sx + 28, sy - 11, sx + 28 + tw, sy + 11, 5,
                                fill="#14161d", outline=THEME["card_line"])
                    c.create_text(sx + 35, sy, anchor="w", fill=color,
                                  font=FONT_SM, text=txt)
                max_x = max(max_x, sx + 190)
        # 节点卡片
        for nid in ch:
            self._draw_node(nid)
        self._draw_branch_labels()
        if ch:
            first = self.flow["nodes"][ch[0]]
            entry_txt = f"▶ 入口 VF_{self.flow['name']}"
            bw = 40 + 12 * len(entry_txt)
            _round_rect(c, first["x"] + 4, first["y"] - 30, first["x"] + bw,
                        first["y"] - 8, 9, fill="#3a3418", outline="#d8c86a")
            c.create_text(first["x"] + 4 + bw / 2, first["y"] - 19, fill="#ffe9a0",
                          font=FONT_SM, text=entry_txt)
        # 连线拖动临时线
        if self.wire:
            sx, sy = self._port_pos(self.flow["nodes"][self.wire["from"]], self.wire["port"])
            c.create_line(sx, sy, self.wire["mx"], self.wire["my"],
                          fill="#e8d44d", width=2, arrow=tk.LAST,
                          arrowshape=ARROW_SHAPE)
        c.config(scrollregion=(0, 0, max_x, max_y))

    def _draw_node(self, nid):
        c = self.canvas
        nd = self.flow["nodes"][nid]
        spec = NODE_TYPES[nd["type"]]
        x, y = nd["x"], nd["y"]
        x1, y1 = x + CARD_W, y + CARD_H
        selected = (nid == self.sel)
        tags = ("node", f"node:{nid}")
        # 阴影
        _round_rect(c, x + 3, y + 5, x1 + 3, y1 + 5, 12,
                    fill=THEME["shadow"], outline="")
        # 主体
        _round_rect(c, x, y, x1, y1, 12, fill=THEME["card"],
                    outline=THEME["sel"] if selected else THEME["card_line"],
                    width=2 if selected else 1, tags=tags)
        # 左侧类型色条
        _round_rect(c, x + 3, y + 5, x + 9, y1 - 5, 3,
                    fill=spec["color"], outline="", tags=tags)
        idx = self.flow["chain"].index(nid) + 1
        c.create_text(x + 20, y + 7, anchor="nw", fill="white", font=FONT_B,
                      text=f"{idx}. {nd.get('title', spec['label'])}",
                      tags=tags)
        c.create_text(x + 20, y + 32, anchor="nw", fill=THEME["text_dim"],
                      font=FONT_SM, text=spec["summary"](nd["props"])[:12], tags=tags)
        if nd["props"].get("template"):
            got = self._get_tpl_photo(nd["props"]["template"])
            if got:
                _, photo, dw, dh = got
                ix = x1 - 10 - dw
                iy = y + (CARD_H - dh) // 2
                c.create_rectangle(ix - 2, iy - 2, ix + dw + 2, iy + dh + 2,
                                   fill="#1a1d26", outline=THEME["card_line"],
                                   tags=tags)
                c.create_image(ix, iy, image=photo, anchor="nw", tags=tags)
        if selected:
            _round_rect(c, x - 3, y - 3, x1 + 3, y1 + 3, 14, outline=THEME["sel"],
                        width=1, fill="")
        if nd["type"] == "branch":
            for port, color in (("hit_next", THEME["ok"]), ("miss_next", THEME["err"])):
                hx, hy = self._port_pos(nd, port)
                c.create_oval(hx - 10, hy - 10, hx + 10, hy + 10,
                              fill="#1c2b1f" if port == "hit_next" else "#2e1c1e",
                              outline="")
                c.create_oval(hx - 6, hy - 6, hx + 6, hy + 6, fill=color,
                              outline="#ffffff", width=1,
                              tags=("port", f"port:{nid}:{port}"))

    def _port_pos(self, nd, port):
        y = nd["y"] + (CARD_H * 0.32 if port == "hit_next" else CARD_H * 0.68)
        return nd["x"] + CARD_W, y

    def _draw_branch_labels(self):
        c = self.canvas
        for nid in self.flow["chain"]:
            nd = self.flow["nodes"][nid]
            if nd["type"] != "branch":
                continue
            hx, hy = self._port_pos(nd, "hit_next")
            mx, my = self._port_pos(nd, "miss_next")
            c.create_text(hx - 11, hy, anchor="e", fill=THEME["ok"],
                          font=FONT_SM, text="✓命中")
            c.create_text(mx - 11, my, anchor="e", fill=THEME["err"],
                          font=FONT_SM, text="✗未中")

    def _draw_overlays(self):
        """背景帧上叠加显示选中节点的 ROI / 点击点 / 滑动线"""
        if not self.bg_disp:
            return
        nid = self.sel
        if not nid or nid not in self.flow["nodes"]:
            return
        c = self.canvas
        ox, oy, dw, dh = self.bg_disp
        W, H = self.frame_wh
        nd = self.flow["nodes"][nid]
        p = nd["props"]

        def px(x):
            return ox + x * dw / W

        def py(y):
            return oy + y * dh / H

        if nd["type"] in ("tpl_click", "wait_tpl", "branch") and p.get("roi"):
            roi = parse_roi(p["roi"])
            if roi:
                c.create_rectangle(px(roi[0]), py(roi[1]),
                                   px(roi[0] + roi[2]), py(roi[1] + roi[3]),
                                   outline=THEME["warn"], width=2, dash=(5, 3))
                c.create_text(px(roi[0]), py(roi[1]) - 8, anchor="sw",
                              fill=THEME["warn"], font=FONT_SM, text="ROI")
        if nd["type"] == "tap":
            c.create_line(px(p["x"]) - 12, py(p["y"]), px(p["x"]) + 12, py(p["y"]),
                          fill="#ff6a6a")
            c.create_line(px(p["x"]), py(p["y"]) - 12, px(p["x"]), py(p["y"]) + 12,
                          fill="#ff6a6a")
            c.create_oval(px(p["x"]) - 7, py(p["y"]) - 7, px(p["x"]) + 7, py(p["y"]) + 7,
                          outline="#ff6a6a", width=2)
        if nd["type"] == "swipe":
            c.create_line(px(p["x1"]), py(p["y1"]), px(p["x2"]), py(p["y2"]),
                          fill="#cf9ae8", width=2, arrow=tk.LAST,
                          arrowshape=ARROW_SHAPE)
            for (kx, ky) in ((p["x1"], p["y1"]), (p["x2"], p["y2"])):
                c.create_oval(px(kx) - 5, py(ky) - 5, px(kx) + 5, py(ky) + 5,
                              outline="#cf9ae8", width=2)

    def _close_tpl_pop(self):
        if self._tpl_pop is not None:
            try:
                self._tpl_pop.destroy()
            except tk.TclError:
                pass
            self._tpl_pop = None

    def _show_tpl_pop(self, cmb, items, on_pick):
        """输入框下方弹出过滤列表（焦点留在输入框，可继续输入）；点选或回车选中"""
        self._close_tpl_pop()
        if not items:
            return
        top = tk.Toplevel(self.root)
        top.wm_overrideredirect(True)
        lb = tk.Listbox(top, bg=THEME["field"], fg=THEME["text"],
                        selectbackground=THEME["accent"], selectforeground=THEME["text"],
                        relief="flat", highlightthickness=1,
                        highlightbackground=THEME["card_line"], font=FONT_SM,
                        activestyle="none", exportselection=False)
        for it in items[:14]:
            lb.insert("end", it)
        lb.selection_set(0)
        lb.pack(fill="both", expand=True)
        w = max(cmb.winfo_width(), 240)
        h = min(len(items), 14) * 21 + 6
        top.wm_geometry("%dx%d+%d+%d" % (w, h,
                     cmb.winfo_rootx(), cmb.winfo_rooty() + cmb.winfo_height()))

        def pick(_e=None):
            sel = lb.curselection()
            picked = lb.get(sel[0]) if sel else (items[0] if items else None)
            self._tpl_pop = None
            top.destroy()
            if picked:
                on_pick(picked)

        lb.bind("<ButtonRelease-1>", pick)
        lb.bind("<Double-Button-1>", pick)
        top.bind("<Destroy>", lambda _e: setattr(self, "_tpl_pop", None)
                 if self._tpl_pop is top else None)
        top.lift()
        top.attributes("-topmost", True)
        self._tpl_pop = top

    def _commit_tpl(self, var, picked):
        """模板最终值写回节点；非法输入恢复原值"""
        nid = self.sel
        if not nid or nid not in self.flow["nodes"]:
            return
        if picked in self.templates:
            if self.flow["nodes"][nid]["props"].get("template") != picked:
                self.flow["nodes"][nid]["props"]["template"] = picked
                self.redraw()
        else:
            var.set(self.flow["nodes"][nid]["props"].get("template", ""))

    def _get_tpl_photo(self, name):
        """模板缩略图（节点卡片显示用）；按 mtime 缓存"""
        path = os.path.join(IMG_DIR, name)
        if not os.path.isfile(path):
            return None
        try:
            mtime = os.path.getmtime(path)
        except OSError:
            return None
        cached = self._tpl_img_cache.get(name)
        if cached and cached[0] == mtime:
            return cached
        try:
            im = Image.open(path).convert("RGB")
            w, h = im.size
            scale = min(42 / h, 88 / w)
            dw, dh = max(1, int(w * scale)), max(1, int(h * scale))
            photo = ImageTk.PhotoImage(im.resize((dw, dh), Image.NEAREST))
        except Exception:
            return None
        self._tpl_img_cache[name] = (mtime, photo, dw, dh)
        return self._tpl_img_cache[name]

    # ---------- 画布交互 ----------

    def _hit_test(self, cx, cy):
        """返回 ("port", nid, port) / ("node", nid) / None"""
        for it in reversed(self.canvas.find_overlapping(cx - 2, cy - 2, cx + 2, cy + 2)):
            for tag in self.canvas.gettags(it):
                if tag.startswith("port:"):
                    _, nid, port = tag.split(":")
                    return ("port", nid, port)
                if tag.startswith("node:"):
                    return ("node", tag.split(":")[1])
        return None

    def _canvas_to_frame(self, cx, cy):
        """画布坐标 → 帧原图坐标；不在帧内返回 None"""
        if not self.bg_disp:
            return None
        ox, oy, dw, dh = self.bg_disp
        if not (ox <= cx <= ox + dw and oy <= cy <= oy + dh):
            return None
        W, H = self.frame_wh
        return (int((cx - ox) * W / dw), int((cy - oy) * H / dh))

    def on_down(self, e):
        cx = self.canvas.canvasx(e.x)
        cy = self.canvas.canvasy(e.y)
        if self.pick_target:
            pt = self._canvas_to_frame(cx, cy)
            self.pick_target = None          # 本次点击后一律退出取点模式
            if pt is not None:
                self._apply_pick(pt)
                return
            self.status("已取消取点（点击帧画面外即取消）")
            # 落到下面的正常选中/拖动逻辑，避免取点模式卡死节点编辑
        hit = self._hit_test(cx, cy)
        if hit is None:
            if self.sel:
                self.sel = None
                self.build_prop_panel()
                self.redraw()
            return
        if hit[0] == "port":
            self.wire = {"from": hit[1], "port": hit[2], "mx": cx, "my": cy}
            self.status(f"拖到目标节点设置 {hit[2]} 出口；拖到空白处=断开")
            return
        nid = hit[1]
        if self.sel != nid:
            self.sel = nid
            self.build_prop_panel()
        nd = self.flow["nodes"][nid]
        self.drag = {"id": nid, "dx": cx - nd["x"], "dy": cy - nd["y"]}
        self.redraw()

    def on_motion(self, e):
        cx = self.canvas.canvasx(e.x)
        cy = self.canvas.canvasy(e.y)
        if self.wire:
            self.wire["mx"], self.wire["my"] = cx, cy
            self.redraw()
            return
        if not self.drag:
            return
        nd = self.flow["nodes"][self.drag["id"]]
        nd["x"] = cx - self.drag["dx"]
        nd["y"] = cy - self.drag["dy"]
        self._reorder_on_drag(self.drag["id"])
        self.redraw()

    def _reorder_on_drag(self, drag_id):
        """拖动中按纵向位置实时重排主链（排序即拖动）"""
        ch = self.flow["chain"]
        others = sorted(self.flow["nodes"][n]["y"] + CARD_H / 2 for n in ch if n != drag_id)
        dy = self.flow["nodes"][drag_id]["y"] + CARD_H / 2
        idx = bisect.bisect_left(others, dy)
        cur = ch.index(drag_id)
        if cur != idx:
            ch.remove(drag_id)
            ch.insert(min(idx, len(ch)), drag_id)

    def on_up(self, _e):
        if self.wire:
            hit = self._hit_test(self.wire["mx"], self.wire["my"])
            src = self.flow["nodes"][self.wire["from"]]
            port = self.wire["port"]
            if hit and hit[0] == "node" and hit[1] != self.wire["from"]:
                src[port] = hit[1]
                self.log(f"已连接 {port} → {self.flow['nodes'][hit[1]].get('title', hit[1])}")
            else:
                if src.get(port):
                    self.log(f"已断开 {port}")
                src[port] = None
            self.wire = None
            self.build_prop_panel()
            self.redraw()
            self.status("就绪")
            return
        if self.drag:
            self.drag = None
            self.build_prop_panel()   # 刷新面板里的 #序号
            self.redraw()

    # ---------- 属性面板 ----------

    def build_prop_panel(self):
        for w in self.props_inner.winfo_children():
            w.destroy()
        self.prop_widgets.clear()
        nid = self.sel
        if not nid or nid not in self.flow["nodes"]:
            ttk.Label(self.props_inner, text="点击画布上的节点，在这里编辑参数",
                      style="Dim.TLabel").grid(row=0, column=0, columnspan=2,
                                               sticky="w", pady=(2, 0))
            self._sync_branch_ui()
            return
        nd = self.flow["nodes"][nid]
        spec = NODE_TYPES[nd["type"]]
        idx = self.flow["chain"].index(nid) + 1
        head = ttk.Frame(self.props_inner)
        head.grid(row=0, column=0, columnspan=2, sticky="w", pady=(2, 6))
        tk.Label(head, text=f"{spec['icon']} #{idx} {spec['label']}",
                 bg=spec["color"], fg="white", font=FONT_B, padx=8, pady=2).pack(side="left")
        row = 1
        for key, label, kind in spec["fields"]:
            ttk.Label(self.props_inner, text=label, style="Dim.TLabel").grid(
                row=row, column=0, sticky="w", pady=2)
            var = self._make_var(nd["props"], key, kind)
            self._make_widget(var, kind, key).grid(row=row, column=1, sticky="we",
                                                   padx=(8, 0), pady=2)
            self.prop_widgets[key] = var
            row += 1
        self.props_inner.columnconfigure(1, weight=1)
        self._sync_branch_ui()

    def _make_var(self, props, key, kind):
        v = props.get(key)
        if kind == "bool":
            var = tk.BooleanVar(value=bool(v))
        else:
            var = tk.StringVar(value="" if v is None else str(v))
        var.trace_add("write", lambda *_: self._prop_changed(key, var, kind))
        return var

    def _make_widget(self, var, kind, key):
        if kind == "tpl":
            self.templates = list_templates()   # 实时刷新（框选工具新保存的模板立即可选）
            cmb = ttk.Combobox(self.props_inner, textvariable=var,
                               values=self.templates, width=26, font=FONT_SM)

            def _on_key(e, cmb=cmb, var=var):
                if e.keysym == "Return":
                    items = [t for t in self.templates
                             if t.lower().startswith(var.get().lower())]
                    self._close_tpl_pop()
                    if items:
                        var.set(items[0])
                        self._commit_tpl(var, items[0])
                    return
                if e.keysym == "Escape":
                    self._close_tpl_pop()
                    return
                if e.keysym in ("Down", "Up", "Left", "Right", "Tab"):
                    return
                typed = var.get().lower()
                items = [t for t in self.templates
                         if t.lower().startswith(typed)]     # 前缀匹配
                self._show_tpl_pop(
                    cmb, items,
                    lambda picked, var=var: (var.set(picked),
                                             self._commit_tpl(var, picked)))

            def _on_focus_out(var=var):
                self.root.after(150, self._close_tpl_pop)   # 等列表点击事件先到
                self._commit_tpl(var, None)

            cmb.bind("<KeyRelease>", _on_key)
            cmb.bind("<<ComboboxSelected>>",
                     lambda e, var=var: self._commit_tpl(var, var.get()))
            cmb.bind("<FocusOut>", _on_focus_out)
            return cmb
        if kind == "common":
            return ttk.Combobox(self.props_inner, textvariable=var,
                                values=COMMON_NODES, width=22, state="readonly",
                                font=FONT_SM)
        if kind == "bool":
            return ttk.Checkbutton(self.props_inner, variable=var, text="")
        if kind == "float":
            return ttk.Spinbox(self.props_inner, textvariable=var,
                               from_=0.3, to=0.99, increment=0.05, width=10,
                               font=FONT_SM)
        if kind in ("pick", "pick2"):
            fr = ttk.Frame(self.props_inner)
            ttk.Entry(fr, textvariable=var, width=8, font=FONT_SM).pack(side="left", ipady=2)
            self._flat_btn(fr, "✛ 取点", lambda: self._start_pick(key),
                           padx=6, font=FONT_SM).pack(side="left", padx=4)
            return fr
        return ttk.Entry(self.props_inner, textvariable=var, width=22, font=FONT_SM)

    def _prop_changed(self, key, var, kind):
        nid = self.sel
        if not nid or nid not in self.flow["nodes"]:
            return
        nd = self.flow["nodes"][nid]
        try:
            if kind == "bool":
                nd["props"][key] = bool(var.get())
            elif kind == "float":
                nd["props"][key] = float(var.get())
            elif kind == "tpl":
                if var.get() in self.templates:
                    nd["props"][key] = var.get()
            elif kind in ("int", "pick", "pick2"):
                nd["props"][key] = int(float(var.get() or 0))
            else:
                nd["props"][key] = var.get()
        except (ValueError, tk.TclError):
            return
        self.redraw()

    # ---------- 分支出口 UI ----------

    def _branch_target_label(self, nid, target, is_hit):
        ch = self.flow["chain"]
        if target is None:
            return "(自动→下一个)" if is_hit else "(流程结束)"
        i = ch.index(target) + 1 if target in ch else "?"
        return f"#{i} {self.flow['nodes'][target].get('title', target)}"

    def _sync_branch_ui(self):
        nid = self.sel
        def _disable_combos():
            self.hit_combo.set("")
            self.miss_combo.set("")
            self.hit_combo.config(values=[], state="disabled")
            self.miss_combo.config(values=[], state="disabled")
        if not nid or nid not in self.flow["nodes"]:
            _disable_combos()
            self.branch_hint.config(text="选中分支节点后可在此改接出口；"
                                         "✗ 跳回前面的节点 = 循环。")
            return
        nd = self.flow["nodes"][nid]
        if nd["type"] != "branch":
            _disable_combos()
            self.branch_hint.config(text="该节点类型没有分支出口（只有「分支(模板在?)」节点有 ✓/✗ 两个出口）。")
            return
        ch = self.flow["chain"]
        options = [f"#{i+1} {self.flow['nodes'][n].get('title', n)}" for i, n in enumerate(ch)]
        self.hit_combo.config(values=["(自动→下一个)"] + options, state="readonly")
        self.miss_combo.config(values=["(流程结束)"] + options, state="readonly")
        self.hit_combo.set(self._branch_target_label(nid, nd.get("hit_next"), True))
        self.miss_combo.set(self._branch_target_label(nid, nd.get("miss_next"), False))
        self.branch_hint.config(text="拖动分支卡片右侧 ✓/✗ 圆点到目标节点即可连线；"
                                     "拖到空白处断开。✗ 跳回前面的节点 = 循环。")

    def on_branch_combo(self, port):
        nid = self.sel
        if not nid or nid not in self.flow["nodes"]:
            return
        nd = self.flow["nodes"][nid]
        if nd["type"] != "branch":
            return
        combo = self.hit_combo if port == "hit_next" else self.miss_combo
        text = combo.get()
        m = re.match(r"#(\d+)", text)
        if m and 1 <= int(m.group(1)) <= len(self.flow["chain"]):
            target = self.flow["chain"][int(m.group(1)) - 1]
            if target == nid:
                self.log("分支出口不能指向自己", "warn")
                self._sync_branch_ui()
                return
            nd[port] = target
        else:
            nd[port] = None
        self.redraw()
        self._sync_branch_ui()

    # ---------- 取坐标 ----------

    def _start_pick(self, key):
        if not self.sel:
            return
        if self.bg_disp is None:
            self.status("取点需要先有背景帧：先抓帧（F5）或打开帧图")
            self.log("⚠ 取点失败：画布上没有背景帧。先点「⟳ 抓帧 (F5)」再取点。", "warn")
            return
        self.pick_target = key
        mode = {"x": "点击取点击坐标", "x1": "点击取滑动起点", "x2": "点击取滑动终点"}[key]
        self.status(f"取点模式：{mode}（在左侧帧画面上点击，Esc 取消）")
        self.root.bind("<Escape>", self._cancel_pick)

    def _cancel_pick(self, _e):
        self.pick_target = None
        self.status("已取消取点")

    def _apply_pick(self, pt):
        key = self.pick_target
        x, y = pt
        props = self.flow["nodes"][self.sel]["props"]
        pairs = {"x": ("x", "y"), "x1": ("x1", "y1"), "x2": ("x2", "y2")}
        ka, kb = pairs[key]
        props[ka], props[kb] = x, y
        self.pick_target = None
        self.status(f"已取坐标 ({x},{y})")
        self.build_prop_panel()
        self.redraw()

    # ---------- 校验/生成/同步 ----------

    def _collect_issues(self):
        errs, warns = validate_flow(self.flow, self.frame_wh)
        for e in errs:
            self.log("✗ " + e, "err")
        for w in warns:
            self.log("⚠ " + w, "warn")
        if not errs:
            self.log("✓ 校验通过" + (f"（{len(warns)} 条警告）" if warns else ""), "ok")
        return errs, warns

    def on_validate(self):
        self._collect_issues()

    def on_build(self):
        self._on_name_change()
        errs, _ = self._collect_issues()
        if errs:
            messagebox.showerror("校验未通过", "请先修复错误（见日志）")
            return
        try:
            path, data = write_pipeline_json(self.flow, self.frame_wh)
        except FlowValidationError as ex:
            messagebox.showerror("生成失败", "\n".join(ex.errors))
            return
        self.log(f"✓ 已生成 {path}（{len(data)} 个节点）", "ok")
        self.status("生成完成: " + os.path.basename(path))
        return path

    def on_sync(self, run_after):
        self._on_name_change()
        errs, _ = self._collect_issues()
        if errs:
            messagebox.showerror("校验未通过", "请先修复错误（见日志）")
            return
        if self._syncing:
            return
        if run_after and not messagebox.askyesno(
                "同步并运行",
                "将 force-stop 重启 App 以加载新流程（虚拟屏会自动重建），"
                "然后立即运行 VF_" + self.flow["name"] + "。继续？"):
            return
        # adb 在后台线程执行；tk 只能主线程碰 → 线程只往队列放消息，主线程轮询刷新
        q = queue.Queue()
        dlg = SyncDialog(self.root, "同步并运行" if run_after else "同步到手机")
        self._syncing = True
        self._btn_sync.config(state="disabled")
        self._btn_run.config(state="disabled")
        threading.Thread(target=self._sync_worker,
                         args=(run_after, dlg, q), daemon=True).start()
        self._poll_sync(q, dlg)

    def _poll_sync(self, q, dlg):
        """主线程轮询同步消息队列：prog/log/status/done；窗口销毁后停止并恢复按钮"""
        try:
            while True:
                kind, a, b = q.get_nowait()
                if kind == "prog":
                    dlg.set_progress(a, b)
                elif kind == "log":
                    self.log(a, b or None)
                elif kind == "status":
                    self.status(a)
                elif kind == "done":
                    dlg.finish(a, b)
                    self._syncing = False
                    self._btn_sync.config(state="normal")
                    self._btn_run.config(state="normal")
                    return
        except queue.Empty:
            pass
        if dlg.winfo_exists():
            self.root.after(60, lambda: self._poll_sync(q, dlg))
            return
        self._syncing = False
        self._btn_sync.config(state="normal")
        self._btn_run.config(state="normal")

    def _sync_worker(self, run_after, dlg, q):
        def prog(pct, text):
            q.put(("prog", pct, text))

        def tlog(msg, tag=""):
            q.put(("log", msg, tag))

        def tstatus(s):
            q.put(("status", s, ""))

        try:
            prog(8, "生成 pipeline JSON…")
            path, _ = write_pipeline_json(self.flow, self.frame_wh)
            tlog("✓ 已生成 " + path)
            prog(25, "推送到手机…")
            sync_pipeline_file(path, tlog)
            sync_templates(self.flow, tlog,
                           lambda pct, text: prog(pct, text))
            prog(60, "注册到【小工具】清单…")
            try:
                register_on_phone(self.flow["name"], tlog)
            except Exception as ex:
                tlog("⚠ 注册【小工具】清单失败（不影响直达入口运行）: " + str(ex), "warn")
            entry = "VF_" + self.flow["name"]
            if run_after:
                prog(80, "重启 App 并运行（虚拟屏会自动重建）…")
                launch_on_phone(entry, tlog, tstatus, ask=False)
                prog(96, "已下发运行指令")
                q.put(("done", True, "✓ 已同步并在手机上启动：" + entry))
            else:
                q.put(("done", True,
                       "✓ 同步完成，重启 App 后在【小工具】栏可见：" + self.flow["name"]))
        except Exception as ex:
            tlog("✗ 同步失败: " + str(ex), "err")
            q.put(("done", False, "✗ 同步失败：" + str(ex)))


# ================= selftest =================

def selftest():
    """无 GUI 校验核心生成逻辑"""
    flow = {"name": "自测流程", "chain": [], "nodes": {}}

    def add(nid, t, props, **kw):
        nd = {"type": t, "x": 0, "y": 0, "title": t, "props": props}
        nd.update(kw)
        flow["nodes"][nid] = nd
        flow["chain"].append(nid)

    add("a", "startapp", {"package": "x.y", "post_delay": 1000})
    add("b", "tpl_click", {"template": "qizhe.png", "threshold": 0.8, "roi": "",
                           "timeout": 8000, "pre_delay": 0, "post_delay": 800,
                           "repeat": 1, "repeat_delay": 350, "order_by": True})
    add("c", "branch", {"template": "sutong_dialog.png", "threshold": 0.7,
                        "roi": "100,200,300,400", "timeout": 4000},
        hit_next=None, miss_next="e")
    add("d", "swipe", {"x1": 1000, "y1": 600, "x2": 280, "y2": 600,
                       "duration": 900, "post_delay": 600})
    add("e", "tap", {"x": 640, "y": 360, "pre_delay": 0, "post_delay": 500,
                     "repeat": 3, "repeat_delay": 350})
    add("f", "common", {"node": "Common_回主页"})

    errs, warns = validate_flow(flow, (1280, 720))
    assert not errs, f"校验意外报错: {errs}"
    out = build_pipeline(flow, (1280, 720))
    E = "VF_自测流程"
    assert out[E] == {"next": [f"{E}_01"]}, out.get(E)
    assert out[f"{E}_02"]["order_by"] == "Score"
    assert "rate_limit" not in out[f"{E}_02"], "rate_limit=0 不应写入"
    assert out[f"{E}_02"]["next"] == [f"{E}_03"]
    # 分支：hit 自动→04（swipe），miss 显式→e(05 tap)
    assert out[f"{E}_03"]["on_error"] == [f"{E}_05"], out[f"{E}_03"]
    assert out[f"{E}_03"]["next"] == [f"{E}_03_Hit"]
    assert out[f"{E}_03_Hit"]["next"] == [f"{E}_04"]
    assert out[f"{E}_03_Hit"]["roi"] == [100, 200, 300, 400]
    assert out[f"{E}_05"]["repeat"] == 3
    assert out[f"{E}_05"]["next"] == [f"{E}_06"]
    assert out[f"{E}_06"] == {"next": ["Common_回主页"]}
    assert f"{E}_End" not in out, "miss 已显式连线则不需要 End"
    # miss 未连线的分支要生成 End 收口
    flow["nodes"]["c"]["miss_next"] = None
    out2 = build_pipeline(flow, (1280, 720))
    assert out2[f"{E}_03"]["on_error"] == [f"{E}_End"]
    assert out2[f"{E}_End"] == {"action": "DoNothing", "next": []}
    # 坐标越界报错（1280 宽画布 x=1400）
    flow["nodes"]["d"]["props"]["x1"] = 1400
    errs, _ = validate_flow(flow, (1280, 720))
    assert errs and "滑动坐标" in errs[0], errs
    # 模板不存在报错
    flow["nodes"]["d"]["props"]["x1"] = 600
    flow["nodes"]["b"]["props"]["template"] = "不存在.png"
    errs, _ = validate_flow(flow, (1280, 720))
    assert errs and "模板不存在" in errs[0], errs
    # JSON 可序列化
    json.dumps(out2, ensure_ascii=False)
    print("selftest OK：校验/生成逻辑全部通过")

    # GUI 构建烟测（有显示环境时）
    if "--no-gui" not in sys.argv:
        try:
            root = tk.Tk()
            root.withdraw()
            editor = FlowEditor(root)
            editor.add_node("tap")
            editor.add_node("swipe")
            editor.add_node("branch")
            editor.redraw()
            root.update()
            root.destroy()
            print("GUI 构建烟测通过")
        except tk.TclError as e:
            print(f"GUI 烟测跳过（无显示环境）: {e}")


# ================= main =================

def main():
    # pythonw（无控制台）下 stdout/stderr 为 None，print 会崩 → 重定向到日志文件
    if sys.stdout is None or sys.stderr is None:
        logf = open(os.path.join(TOOLS_DIR, "flow_editor.log"), "a", encoding="utf-8")
        sys.stdout = sys.stdout or logf
        sys.stderr = sys.stderr or logf
    os.makedirs(FLOWS_DIR, exist_ok=True)
    if "--selftest" in sys.argv:
        selftest()
        return
    root = tk.Tk()
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    load = None
    if args:
        p = args[0]
        if p.startswith("/") and len(p) > 2 and p[2] == "/":
            p = p[1].upper() + ":" + p[2:]   # Git Bash 风格路径 → Windows
        load = p
    FlowEditor(root, load_path=load)
    root.mainloop()


if __name__ == "__main__":
    main()
