# -*- coding: utf-8 -*-
"""cdzb.json 重构：
1) 固定坐标「回主页(300,40)」点击节点 → 转发到公共节点 Common_回主页
2) 固定坐标「点中间(804,360)」点击节点 → 转发到公共节点 Common_点中间
3) 角色模板 _target.png → 默认 蛙锣.png（由 interface.json 的「目标角色」option 覆盖）
"""
import json, re, io, sys

P = r'E:\MaaWH\whmx\pipeline\cdzb.json'
raw = open(P, encoding='utf-8').read()


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


d = json.loads(strip_jsonc(raw))
changed_home, changed_mid, changed_tpl = [], [], []

for k, v in d.items():
    if not isinstance(v, dict):
        continue
    # 1/2) 纯固定坐标点击 → 转发公共节点
    if v.get('action') == 'Click' and 'recognition' not in v:
        tgt = v.get('target')
        if isinstance(tgt, list) and len(tgt) >= 2:
            nxt = v.get('next')
            if tgt[:2] == [300, 40]:
                v['action'] = 'DoNothing'
                v['next'] = ['Common_回主页']
                v.pop('target', None)
                changed_home.append(k)
            elif tgt[:2] == [804, 360]:
                v['action'] = 'DoNothing'
                v['next'] = ['Common_点中间'] + ([nxt] if isinstance(nxt, str) else (nxt or []))
                v.pop('target', None)
                changed_mid.append(k)
    # 3) 角色模板
    if v.get('template') == '_target.png':
        v['template'] = '蛙锣.png'
        changed_tpl.append(k)

out = json.dumps(d, ensure_ascii=False, indent=4)
open(P, 'w', encoding='utf-8', newline='\n').write(out + '\n')

print('回主页转发:', changed_home)
print('点中间转发:', changed_mid)
print('角色模板改默认:', changed_tpl)
print('节点总数:', len(d))
