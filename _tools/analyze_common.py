# -*- coding: utf-8 -*-
"""定位 whmx 中可抽取为公共节点的重复模式（纯固定坐标点击、重名节点、回主页/确认/关弹窗）。"""
import json, glob, os, collections


def strip_jsonc(s):
    out, i, n, in_str, esc = [], 0, len(s), False, False
    while i < n:
        c = s[i]
        if in_str:
            out.append(c)
            if esc:
                esc = False
            elif c == '\\':
                esc = True
            elif c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if c == '/' and i + 1 < n and s[i + 1] == '/':
            while i < n and s[i] != '\n':
                i += 1
            continue
        if c == '/' and i + 1 < n and s[i + 1] == '*':
            i += 2
            while i + 1 < n and not (s[i] == '*' and s[i + 1] == '/'):
                i += 1
            i += 2
            continue
        out.append(c)
        i += 1
    return ''.join(out)


pure = collections.defaultdict(list)
names = collections.Counter()
home_like = []
confirm_like = []

for f in sorted(glob.glob('whmx/pipeline/*.json')):
    base = os.path.basename(f)
    try:
        d = json.loads(strip_jsonc(open(f, encoding='utf-8').read()))
    except Exception as e:
        print('PARSE FAIL', base, e)
        continue
    for k, v in d.items():
        names[k] += 1
        if not isinstance(v, dict):
            continue
        keys = set(v.keys())
        act = v.get('action')
        tgt = v.get('target')
        if act == 'Click' and isinstance(tgt, list) and len(tgt) >= 2:
            if not (keys & {'recognition', 'template', 'next', 'on_error', 'repeat', 'expected', 'text'}):
                pure[(tgt[0], tgt[1])].append(f'{base}:{k}')
        tpl = v.get('template')
        if tpl and any(s in str(tpl) for s in ('cha_tanchuang', 'cha_x', 'hanghui_fanhui', 'duigou')):
            (home_like if 'cha' in str(tpl) or 'fanhui' in str(tpl) else confirm_like).append(f'{base}:{k} -> {tpl}')

print('=== 纯固定坐标点击节点（无识别/无 next，可抽公共节点） ===')
for tgt, lst in sorted(pure.items(), key=lambda x: -len(x[1])):
    print(f'{str(tgt):18s} x{len(lst)}')
    for s in lst:
        print(f'      {s}')
print()
print('=== 跨文件重名节点（引擎会硬失败） ===')
dups = [k for k, c in names.items() if c > 1]
print('无' if not dups else dups)
print()
print('=== 关弹窗/返回类模板引用 ===')
for s in sorted(home_like):
    print('  ', s)
print()
print('=== 确认(√)类模板引用 ===')
for s in sorted(confirm_like):
    print('  ', s)
