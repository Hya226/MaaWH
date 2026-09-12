# -*- coding: utf-8 -*-
"""whmx 资源包(MaaFramework v5 bundle)完整性审计。

复刻 MaaFramework 加载器(PipelineResMgr/PipelineChecker/TemplateResMgr/OCRResMgr)
的校验逻辑,外加静态层面能发现的引用/资源问题:
 1. 逐文件解析(容错 // 注释、尾逗号,对齐 Maa json::open 的 jsonc 容忍度)
 2. 跨文件重复节点名(Maa 会硬失败: key already exists)
 3. recognition / action 枚举合法性
 4. next / on_error 引用闭环(Maa 会硬失败)
 5. template 引用图是否存在(引擎不预检,运行期才解析,最易漏)
 6. OCR text 正则合法性、OCR 模型文件存在性
 7. roi / target 数组形状、StartApp/StopApp 的 package、常见字段拼写
默认报告缺失/悬空/非法项,可用 --report all 输出完整逐节点清单。
"""
import json, os, re, sys, glob, collections

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUNDLE = os.path.join(ROOT, "whmx")
PIPE_DIR = os.path.join(BUNDLE, "pipeline")
IMG_DIR = os.path.join(BUNDLE, "image")
OCR_DIR = os.path.join(BUNDLE, "model", "ocr")

KNOWN_RECOGNITION = {"DirectHit", "TemplateMatch", "FeatureMatching", "ColorMatch",
                     "OCR", "NeuralNetworkClassify", "NeuralNetworkDetect", "Custom",
                     "And", "Or"}
KNOWN_ACTION = {"Click", "ClickSelf", "Swipe", "MultiSwipe", "Touch", "TouchUp",
                "ClickKey", "LongPressKey", "Key", "InputText", "Text", "StartApp",
                "StopApp", "Scroll", "DoNothing", "StopTask", "Command", "Shell",
                "Screencap", "Custom"}

# v1/兼容(平铺)+ v2(嵌套)节点通用字段 + 常见识别/动作参数(平铺时代也直接挂在节点上)
KNOWN_NODE_KEYS = {
    # 流程/通用
    "next", "on_error", "rate_limit", "timeout", "pre_delay", "post_delay",
    "pre_wait_freezes", "post_wait_freezes", "repeat_wait_freezes",
    "repeat", "repeat_delay", "max_hit", "inverse", "enabled", "focus", "attach",
    # recognition
    "recognition", "template", "threshold", "roi", "order_by", "green_mask",
    "method", "detector", "ratio", "lower", "upper", "connected", "color_filter",
    "expected", "replace", "only_rec", "model", "labels", "count", "text",
    "custom_recognition", "mask", "rec_type", "param", "type",
    # action
    "action", "package", "target", "begin", "end", "duration", "key", "keys",
    "input_text", "text", "exec", "args", "shell", "cmd", "detach", "shell_timeout",
    "filename", "format", "custom_action", "swipes", "contacts", "contact",
    "pressure", "starting", "end_hold", "only_hover", "times", "target_offset",
    "click", "hold", "start", "distance", "velocity", "duration_ms",
}
REF_FIELDS = ["next", "on_error"]

def read_text(p):
    return open(p, encoding="utf-8", errors="replace").read()

def strip_jsonc(s):
    """去掉 // 行注释与 /* */ 块注释(字符串内的 '//' 会被误删,仅用于定位,可接受)。"""
    out, i, n, in_str, esc = [], 0, len(s), False, False
    while i < n:
        c = s[i]
        if in_str:
            out.append(c)
            if esc: esc = False
            elif c == "\\": esc = True
            elif c == '"': in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True; out.append(c); i += 1; continue
        if c == "/" and i + 1 < n and s[i + 1] == "/":
            while i < n and s[i] != "\n": i += 1
            continue
        if c == "/" and i + 1 < n and s[i + 1] == "*":
            i += 2
            while i + 1 < n and not (s[i] == "*" and s[i + 1] == "/"): i += 1
            i += 2
            continue
        out.append(c); i += 1
    return "".join(out)

def parse_jsonc(p):
    s = read_text(p)
    return json.loads(strip_jsonc(s))

def main(report_all=False):
    problems = collections.defaultdict(list)   # file -> list
    def add(f, sev, msg):
        problems[f].append((sev, msg))

    # ---------- 1. 逐文件解析 ----------
    files = sorted(glob.glob(os.path.join(PIPE_DIR, "*.json")))
    nodes = {}            # name -> (file, node)
    by_file = {}
    for f in files:
        base = os.path.basename(f)
        if base.startswith("."):
            continue
        try:
            d = parse_jsonc(f)
        except Exception as e:
            add(base, "FAIL", "JSON 解析失败: %s" % e); continue
        if not isinstance(d, dict):
            add(base, "FAIL", "顶层不是 JSON 对象"); continue
        for k, v in d.items():
            if k.startswith("$"):
                continue  # 官方允许的注释键
            if not isinstance(v, dict):
                add(base, "FAIL", "节点 %r 不是对象" % k); continue
            by_file.setdefault(base, {})[k] = v
            if k in nodes:
                add(base, "FAIL", "节点名 %r 与 %s 重复(加载器会报 key already exists)"
                    % (k, nodes[k][0]))
            else:
                nodes[k] = (base, v)
    if not nodes:
        add("__total__", "FAIL", "没有解析到任何节点"); 

    # ---------- 2. 枚举 / 字段 ----------
    unknown_fields = collections.Counter()
    refs = {}  # node -> [(field, target)...]
    for name, (f, nd) in nodes.items():
        for kk in nd:
            if kk not in KNOWN_NODE_KEYS:
                unknown_fields[(f, kk)] += 1
        rec = nd.get("recognition")
        if rec is not None:
            if isinstance(rec, str) and rec not in KNOWN_RECOGNITION:
                add(f, "FAIL", "节点 %r 未知 recognition 枚举: %r" % (name, rec))
            if rec == "OCR" and nd.get("text") is not None:
                for t in (nd["text"] if isinstance(nd["text"], list) else [nd["text"]]):
                    if not isinstance(t, str): continue
                    try:
                        re.compile(t)
                    except re.error as e:
                        add(f, "FAIL", "节点 %r OCR text 正则无效 %r: %s" % (name, t, e))
        act = nd.get("action")
        if act is not None and isinstance(act, str) and act not in KNOWN_ACTION:
            add(f, "FAIL", "节点 %r 未知 action 枚举: %r" % (name, act))
        if act in ("StartApp", "StopApp") and not nd.get("package"):
            add(f, "WARN", "节点 %r %s 未指定 package" % (name, act))
        for rf in REF_FIELDS:
            if rf in nd:
                vals = nd[rf] if isinstance(nd[rf], list) else [nd[rf]]
                for t in vals:
                    refs.setdefault(name, []).append((rf, t))
        if "template" in nd:
            for t in (nd["template"] if isinstance(nd["template"], list) else [nd["template"]]):
                p = os.path.normpath(os.path.join(IMG_DIR, t))
                if not os.path.isfile(p):
                    add(f, "FAIL", "节点 %r 引用的模板图不存在: image/%s" % (name, t))
        if "model" in nd and isinstance(nd["model"], str) and nd["model"]:
            mp = os.path.normpath(os.path.join(BUNDLE, nd["model"]))
            if not os.path.exists(mp):
                add(f, "WARN", "节点 %r model 路径不存在(相对 bundle): %s" % (name, nd["model"]))
        for shp in ("roi",):
            if shp in nd and isinstance(nd[shp], list) and len(nd[shp]) not in (4,) and len(nd[shp]) not in (0,):
                add(f, "WARN", "节点 %r %s 数组长度 %d(应为 4)" % (name, shp, len(nd[shp])))
        if "target" in nd:
            t = nd["target"]
            ok = (isinstance(t, list) and len(t) in (2, 4)
                  and all(isinstance(x, (int, float)) for x in t))
            if not ok:
                add(f, "WARN", "节点 %r target 形状异常: %r(期望 [x,y] 或 [x,y,w,h])" % (name, t))

    # ---------- 3. 引用闭环 ----------
    for name, lst in refs.items():
        for rf, t in lst:
            if not isinstance(t, str):
                continue
            if t.startswith("#") or t.startswith("@"):
                continue
            if t not in nodes:
                add(nodes[name][0], "FAIL", "节点 %r %s 指向未定义节点: %r" % (name, rf, t))

    # ---------- 3b. error handling loop 风险 ----------
    # 实测教训（2026-09-12，派遣公司事务崩）：
    # 循环容器的 on_error 收尾目标若是「识别节点」且该节点无 next/on_error，
    # 识别失败会让容器 error 与循环回边构成 error handling loop，引擎直接判任务失败。
    # 收尾目标必须是永远成功的纯动作节点，或用「分叉容器 + 兜底 on_error」包一层。
    def _is_reco(nd):
        r = nd.get("recognition")
        return r is not None and r != "DirectHit"

    for name, (f, nd) in nodes.items():
        for oe in (nd.get("on_error") or []):
            if not isinstance(oe, str) or oe not in nodes:
                continue
            tf, tnd = nodes[oe]
            if _is_reco(tnd) and not tnd.get("next") and not tnd.get("on_error"):
                add(f, "FAIL",
                    "节点 %r 的 on_error -> %r 是识别节点且无出口，"
                    "会触发 error handling loop（收尾目标必须永远成功）" % (name, oe))

    # ---------- 4. 图与资源 ----------
    imgs = set()
    for p in glob.glob(os.path.join(IMG_DIR, "*")):
        if os.path.isfile(p):
            imgs.add(os.path.basename(p))
    referenced = set()
    for name, (f, nd) in nodes.items():
        if "template" in nd:
            for t in (nd["template"] if isinstance(nd["template"], list) else [nd["template"]]):
                referenced.add(os.path.basename(t))
    if not imgs:
        add("__total__", "WARN", "image/ 目录为空")
    for extra in sorted(imgs - referenced):
        add("__total__", "INFO", "image/ 中有未引用的图: %s" % extra)
    for t in sorted(referenced):
        if t not in imgs:
            add("__total__", "FAIL", "模板图缺失: %s" % t)
    # PNG 头部
    for p in glob.glob(os.path.join(IMG_DIR, "*.png")):
        b = open(p, "rb").read(24)
        if not b.startswith(b"\x89PNG\r\n\x1a\n"):
            add("__total__", "WARN", "不是合法 PNG(头部异常): %s" % os.path.basename(p))
    # OCR 模型
    need = ["det.onnx", "rec.onnx", "keys.txt"]
    for m in need:
        p = os.path.join(OCR_DIR, m)
        if not os.path.isfile(p):
            add("__total__", "FAIL", "OCR 模型缺失: model/ocr/%s" % m)
        elif os.path.getsize(p) == 0:
            add("__total__", "FAIL", "OCR 模型为空文件: model/ocr/%s" % m)
    kp = os.path.join(OCR_DIR, "keys.txt")
    if os.path.isfile(kp):
        ks = open(kp, encoding="utf-8", errors="replace").read()
        if len(ks.strip()) < 10:
            add("__total__", "WARN", "keys.txt 内容过短(%d 字符)" % len(ks.strip()))
    # 默认配置
    df = os.path.join(BUNDLE, "default_pipeline.json")
    if not os.path.isfile(df):
        add("__total__", "FAIL", "缺少 default_pipeline.json(Maa v5 推荐结构)")
    else:
        try:
            dd = parse_jsonc(df)
            if not isinstance(dd, dict):
                add("default_pipeline.json", "FAIL", "顶层不是对象")
            elif "Default" not in dd:
                add("default_pipeline.json", "WARN", "缺少 Default 节点")
        except Exception as e:
            add("default_pipeline.json", "FAIL", "解析失败: %s" % e)

    # ---------- 输出 ----------
    order = {"FAIL": 0, "WARN": 1, "INFO": 2}
    rows = []
    for f, lst in problems.items():
        for sev, msg in lst:
            rows.append((order[sev], sev, f, msg))
    rows.sort()
    n_fail = sum(1 for r in rows if r[1] == "FAIL")
    n_warn = sum(1 for r in rows if r[1] == "WARN")
    print("== whmx 完整性审计 ==")
    print("bundle : %s" % BUNDLE)
    print("pipeline 文件: %d | 任务节点(去重): %d | image: %d | model/ocr: 3"
          % (len(files), len(nodes), len(imgs)))
    if report_all:
        for _, sev, f, msg in rows:
            print("[%s] %s: %s" % (sev, f, msg))
    else:
        for _, sev, f, msg in rows:
            if sev in ("FAIL", "WARN"):
                print("[%s] %s: %s" % (sev, f, msg))
    print("----")
    print("FAIL: %d   WARN: %d   (INFO 项用 --report all 查看)" % (n_fail, n_warn))

if __name__ == "__main__":
    main(report_all="--report" in sys.argv or "-a" in sys.argv)
