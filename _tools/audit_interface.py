# -*- coding: utf-8 -*-
"""interface.json（ProjectInterface v2 子集）校验。

补 audit_whmx.py 未覆盖的一层：任务清单与参数化引用的完整性。
 1. task.entry 必须存在于 pipeline 节点
 2. task.option / global_option 引用的 option 必须已定义
 3. option 的 cases[].pipeline_override / pipeline_override 覆盖的节点必须存在
 4. input 类型 option 的 {占位符} 必须与 inputs[].name 对应
 5. switch 类型必须恰好两个 Yes/No case
 6. 冗余模板（image/ 下未被任何节点引用）

用法：python _tools/audit_interface.py
"""
import json, os, re, sys, glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUNDLE = os.path.join(ROOT, "whmx")
IFACE = os.path.join(BUNDLE, "interface.json")
PIPE_DIR = os.path.join(BUNDLE, "pipeline")
IMG_DIR = os.path.join(BUNDLE, "image")


def strip_jsonc(s):
    out, i, n, in_str, esc = [], 0, len(s), False, False
    while i < n:
        c = s[i]
        if in_str:
            out.append(c)
            if esc: esc = False
            elif c == '\\': esc = True
            elif c == '"': in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True; out.append(c); i += 1; continue
        if c == '/' and i + 1 < n and s[i + 1] == '/':
            while i < n and s[i] != '\n': i += 1
            continue
        out.append(c); i += 1
    return ''.join(out)


def load(p):
    return json.loads(strip_jsonc(open(p, encoding='utf-8', errors='replace').read()))


nodes, tpl_used = {}, set()
for f in glob.glob(os.path.join(PIPE_DIR, "*.json")):
    try:
        d = load(f)
    except Exception as e:
        print(f"[FAIL] {os.path.basename(f)} 解析失败 {e}")
        continue
    for k, v in d.items():
        nodes[k] = v
        if isinstance(v, dict):
            tv = v.get('template')
            if isinstance(tv, str):
                tpl_used.add(tv)
            elif isinstance(tv, list):      # 多候选模板（任一命中）
                tpl_used.update(x for x in tv if isinstance(x, str))

fails, warns, infos = [], [], []

if not os.path.exists(IFACE):
    print("[FAIL] 未找到 whmx/interface.json")
    sys.exit(1)

iface = load(IFACE)
if iface.get('interface_version') != 2:
    warns.append(f"interface_version 不是 2（当前 {iface.get('interface_version')}）")

opt_defs = iface.get('option') or {}

# 1 + 2
# 宿主任务的 entry 由 QueueRunner 的 Kotlin 分支执行，不是引擎节点（对齐 QueueRunner 的分支判断）。
# 注：「外勤见闻识别」已改为小工具面板功能，不在清单里，入口只有面板与 adb 直达（--es entry 外勤见闻识别）
HOST_ENTRIES = {"关闭游戏"}

for t in iface.get('task', []):
    name = t.get('name')
    entry = t.get('entry')
    if entry not in nodes and entry not in HOST_ENTRIES:
        fails.append(f"任务 '{name}' 的 entry '{entry}' 不存在于 pipeline")
    for o in (t.get('option') or []):
        if o not in opt_defs:
            fails.append(f"任务 '{name}' 引用未定义的 option '{o}'")
for o in (iface.get('global_option') or []):
    if o not in opt_defs:
        fails.append(f"global_option 引用未定义的 option '{o}'")

# 3 + 4 + 5
for okey, o in opt_defs.items():
    typ = o.get('type', 'select')

    def check_ov(ov, where):
        if not isinstance(ov, dict):
            return
        for nk in ov.keys():
            if nk not in nodes:
                fails.append(f"option '{okey}'{where} 覆盖不存在的节点 '{nk}'")

    if typ in ('select', 'switch', 'checkbox'):
        cases = o.get('cases') or []
        if not cases:
            fails.append(f"option '{okey}'（{typ}）没有 cases")
        if typ == 'switch':
            if len(cases) != 2:
                fails.append(f"option '{okey}'（switch）必须恰好 2 个 case，当前 {len(cases)}")
            names = {c.get('name') for c in cases}
            if not (names & {'Yes', 'yes', 'Y', 'y'}) or not (names & {'No', 'no', 'N', 'n'}):
                fails.append(f"option '{okey}'（switch）缺少 Yes/No case: {names}")
        dc = o.get('default_case')
        if dc and dc not in {c.get('name') for c in cases}:
            fails.append(f"option '{okey}' default_case '{dc}' 不在 cases 中")
        for c in cases:
            check_ov(c.get('pipeline_override'), f" case '{c.get('name')}'")
    elif typ == 'input':
        declared = {i.get('name') for i in (o.get('inputs') or [])}
        if not declared:
            fails.append(f"option '{okey}'（input）没有 inputs 声明")
        # 只提取字符串值内部的占位符（JSON 转义后 { 不会出现在结构里）
        s = json.dumps(o.get('pipeline_override') or {}, ensure_ascii=False)
        for ph in re.findall(r'\{([^{}":,\s]+)\}', s):
            if ph not in declared:
                fails.append(f"option '{okey}' 的占位符 {{{ph}}} 未在 inputs 中声明")
        check_ov(o.get('pipeline_override'), "")
    else:
        warns.append(f"option '{okey}' 类型 '{typ}' 未在本项目实现（宿主会忽略）")

# 6 冗余模板
all_img = {os.path.basename(p) for p in glob.glob(os.path.join(IMG_DIR, "*.png"))}
unused = sorted(all_img - tpl_used - {'_target.png'})
if unused:
    infos.append(f"未被引用的模板图 {len(unused)} 张: {unused[:15]}{' ...' if len(unused) > 15 else ''}")

print("== interface.json 审计 ==")
print(f"任务 {len(iface.get('task', []))} 个 | option {len(opt_defs)} 个 | "
      f"pipeline 节点 {len(nodes)} | image {len(all_img)}")
for f in fails: print("[FAIL]", f)
for w in warns: print("[WARN]", w)
for i in infos: print("[INFO]", i)
print("----")
print(f"FAIL: {len(fails)}   WARN: {len(warns)}")
sys.exit(1 if fails else 0)
