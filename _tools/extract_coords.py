"""提取流水线固定坐标与 ROI（临时工具，坐标换算用）"""
import json
import glob
import os


def strip_comments(s: str) -> str:
    out, i, n, instr = [], 0, len(s), False
    while i < n:
        c = s[i]
        if instr:
            out.append(c)
            if c == '\\':
                out.append(s[i + 1])
                i += 2
                continue
            if c == '"':
                instr = False
            i += 1
            continue
        if c == '"':
            instr = True
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


def main():
    os.chdir(os.path.join(os.path.dirname(__file__), '..', 'whmx', 'pipeline'))
    for f in sorted(glob.glob('*.json')):
        if f.startswith('_'):
            continue
        try:
            data = json.loads(strip_comments(open(f, encoding='utf-8').read()))
        except Exception as e:
            print(f'!! {f}: {e}')
            continue
        for name, node in data.items():
            if not isinstance(node, dict):
                continue
            tgt = node.get('target')
            if isinstance(tgt, list) and all(isinstance(v, (int, float)) for v in tgt):
                print(f'{f:14} {name:30} target={tgt}')
            roi = node.get('roi')
            if isinstance(roi, list):
                print(f'{f:14} {name:30} roi={roi}')


if __name__ == '__main__':
    main()
