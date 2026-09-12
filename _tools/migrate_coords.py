"""1608x720 → 1280x720 流水线坐标批量换算（一次性迁移工具）

规律（2026-09-13 逐页实测）：
- dy = 0（除升好_礼物 430→425 外，垂直坐标不变）
- 左上角区（返回箭头/主页图标/详情页图标）：dx = -59
- 右侧栏/底部行/右上角 X：dx = -269
- 居中弹窗层/顶部居中 tab 行：dx = -164
- 中央内容区（卡片网格重排）：逐页实测，不套规律
"""
import re
import os

# (文件, 节点名, 字段, 新值数组)
TARGETS = [
    # ===== cdzb.json =====
    ('cdzb.json', 'CDZB4_Hit', 'target', [316, 600]),      # 同享后弹窗关闭，居中层 -164
    ('cdzb.json', '升好_好感', 'target', [48, 200]),        # 实测：穆契入口心形图标
    ('cdzb.json', '升好_礼物', 'target', [1170, 425]),      # 实测：第二行右卡，送出礼物成功
    ('cdzb.json', '分_白板', 'target', [548, 175]),         # 实测：分解页6列网格第一行第二卡，选中成功
    ('cdzb.json', '分_中', 'target', [640, 360]),           # 居中
    ('cdzb.json', '领_点1', 'target', [36, 600]),           # 实测：获得物资层关闭成功
    ('cdzb.json', '领_点2', 'target', [36, 600]),           # 实测：周常层关闭成功
    ('cdzb.json', '外_点565', 'target', [36, 565]),         # 同获得物资层，低风险
    # ===== common.json =====
    ('common.json', 'Common_点中间', 'target', [640, 360]),
    ('common.json', 'Common_点中间回主页', 'target', [640, 360]),
    ('common.json', 'Common_回主页_重试', 'target', [240, 37]),  # 实测：home 图标中心
    # ===== grind.json =====
    ('grind.json', 'TapToEnterH', 'target', [640, 360]),
    ('grind.json', 'TapToEnter', 'target', [640, 360]),
    ('grind.json', '刷谷_返回战前', 'target', [107, 33]),   # 左上 -59
    # ===== libao.json =====
    ('libao.json', 'LB_ShangTing', 'target', [924, 672]),   # 实测：底部行 -269，成功进入商亭
    ('libao.json', 'LB_LiBao', 'target', [698, 125]),       # 实测：tab 行 -164，成功切礼包
    ('libao.json', 'LB_XunShi', 'target', [109, 421]),      # 实测：左区 -59，成功进循时补给
    ('libao.json', 'LB_MianFei', 'target', [874, 665]),     # 实测：卡片重排，免费卡移至第3卡（mianfei 模板命中 837,644）
    ('libao.json', 'LB_GouMai', 'target', [778, 518]),      # 确认弹窗居中 -164（今日免费已购，未实测）
    # ===== login.json =====
    ('login.json', 'TapEnter', 'target', [640, 360]),
    # ===== pqgs.json =====
    ('pqgs.json', 'PQ_R1', 'target', [1086, 400]),          # 实测：获得物资层关闭成功
    ('pqgs.json', 'PQ_R2', 'target', [1186, 400]),          # 实测：获得物资层关闭成功
    ('pqgs.json', 'PQ_MID', 'target', [836, 360]),          # 同层居中 -164
]

ROIS = [
    # ===== common.json =====
    ('common.json', 'Common_回主页_Hit', 'roi', [191, 8, 130, 70]),        # home 实测 (223,19) 在框内
    ('common.json', 'Common_主页校验', 'roi', [580, 100, 700, 460]),        # 右缘贴边，yanxun 实测 (918,260) 在框内
    ('common.json', 'Common_关弹窗_X细', 'roi', [822, 20, 458, 220]),       # 右缘贴边，cha_x 实测 (1156,77) 在框内
    ('common.json', 'Common_关弹窗_X粗', 'roi', [822, 20, 458, 220]),
    ('common.json', 'Common_弹窗_Hit', 'roi', [971, 60, 160, 100]),         # 右上 -269
    # ===== grind.json =====
    ('grind.json', 'CloseAnno2H', 'roi', [822, 20, 458, 220]),
    ('grind.json', 'CloseAnno2', 'roi', [822, 20, 458, 220]),
    ('grind.json', 'ToEnterPageH', 'roi', [191, 120, 1000, 480]),           # 登录页大区域，左 -59
    ('grind.json', 'ToEnterPage', 'roi', [191, 120, 1000, 480]),
    ('grind.json', 'YanXunDone', 'roi', [580, 100, 700, 460]),
    ('grind.json', 'YanXun', 'roi', [580, 100, 700, 460]),
    ('grind.json', 'DongGuBiOcr', 'roi', [236, 150, 1000, 520]),            # 结算中央区 -164
    ('grind.json', 'DongGuBiTpl', 'roi', [436, 200, 800, 400]),
    ('grind.json', 'SuTongClick', 'roi', [922, 600, 358, 120]),             # 结算右下贴边 -269
    ('grind.json', '刷谷_调次数', 'roi', [566, 300, 390, 85]),              # 调次数弹窗居中 -164
    ('grind.json', '刷谷_加次数', 'roi', [566, 300, 390, 85]),
    ('grind.json', 'ConfirmBattle', 'roi', [736, 330, 240, 160]),           # 开战确认弹窗 -164
    ('grind.json', 'FavYes', 'roi', [656, 360, 240, 160]),                  # 收藏弹窗 -164
    ('grind.json', 'WaitClaim', 'roi', [636, 300, 644, 420]),               # 结算领取区贴边
    ('grind.json', 'ClaimFixed', 'roi', [772, 570, 508, 150]),              # 完成按钮右下贴边 -269
    # ===== login.json =====
    ('login.json', 'CloseAnnounce', 'roi', [822, 20, 458, 220]),
    # ===== zhengji.json =====
    ('zhengji.json', 'ZJ_DuiGou2', 'roi', [696, 380, 200, 120]),            # 勾选弹窗 -164
]

# 滑动起终点：cdzb.json 列表滑动链 begin [1200,650] → [1000,650]
SWIPE_REPLACE = {
    'cdzb.json': [('"begin": [\n            1200,\n            650\n        ]', '"begin": [\n            1000,\n            650\n        ]')],
}


def locate_node(text: str, node: str):
    m = re.search(r'"' + re.escape(node) + r'"\s*:\s*\{', text)
    return m.start() if m else -1


def replace_field(text: str, node: str, field: str, new: list) -> str:
    start = locate_node(text, node)
    if start < 0:
        return text
    # 节点体范围：从节点开始到下一个顶层节点（粗略：到文件尾也行，取最近 2500 字符）
    seg_end = min(len(text), start + 2500)
    seg = text[start:seg_end]
    pat = re.compile(r'"' + field + r'"\s*:\s*\[[^\]]*\]')
    mm = pat.search(seg)
    if not mm:
        print(f'  !! 未找到 {node}.{field}')
        return text
    new_arr = '[' + ', '.join(str(v) for v in new) + ']'
    new_seg = seg[:mm.start()] + f'"{field}": {new_arr}' + seg[mm.end():]
    print(f'  {node}.{field} -> {new_arr}')
    return text[:start] + new_seg + text[seg_end:]


def main():
    base = os.path.join(os.path.dirname(__file__), '..', 'whmx', 'pipeline')
    os.chdir(base)
    files = sorted({f for f, *_ in TARGETS + ROIS} | set(SWIPE_REPLACE))
    for f in files:
        text = open(f, encoding='utf-8').read()
        orig = text
        print(f'== {f} ==')
        for file, node, field, new in TARGETS:
            if file == f:
                text = replace_field(text, node, field, new)
        for file, node, field, new in ROIS:
            if file == f:
                text = replace_field(text, node, field, new)
        for old, new in SWIPE_REPLACE.get(f, []):
            if old in text:
                text = text.replace(old, new)
                print(f'  swipe begin 1200->1000 (x{ text.count(new) })')
        if text != orig:
            open(f, 'w', encoding='utf-8', newline='\n').write(text)
            print(f'  已写回 {f}')
        else:
            print(f'  无变化 {f}')


if __name__ == '__main__':
    main()
