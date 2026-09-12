"""在新基准帧上全帧匹配模板，输出分数与位置（分辨率迁移标定用）"""
import sys
import glob
import os

import cv2


def match(frame_path, templates, roi=None):
    frame = cv2.imread(frame_path)
    fh, fw = frame.shape[:2]
    print(f'帧: {os.path.basename(frame_path)} {fw}x{fh}')
    if roi:
        x, y, w, h = roi
        print(f'限定 ROI: {roi}')
    for tpl_path in templates:
        tpl = cv2.imread(tpl_path)
        if tpl is None:
            print(f'  !! 模板读不到: {tpl_path}')
            continue
        th, tw = tpl.shape[:2]
        if th > fh or tw > fw:
            print(f'  {os.path.basename(tpl_path):28} 模板({tw}x{th})大于帧，跳过')
            continue
        if roi:
            x, y, w, h = roi
            search = frame[y:y + h, x:x + w]
        else:
            search = frame
        res = cv2.matchTemplate(search, tpl, cv2.TM_CCOEFF_NORMED)
        _, maxv, _, maxloc = cv2.minMaxLoc(res)
        px, py = (maxloc[0] + x, maxloc[1] + y) if roi else maxloc
        print(f'  {os.path.basename(tpl_path):28} 分数={maxv:.3f} 位置=({px},{py}) 模板尺寸={tw}x{th}')


if __name__ == '__main__':
    frame = sys.argv[1]
    tpl_dir = os.path.join(os.path.dirname(__file__), '..', 'whmx', 'image')
    if len(sys.argv) > 2 and sys.argv[2] == 'all':
        tpls = sorted(glob.glob(os.path.join(tpl_dir, '*.png')))
        tpls = [t for t in tpls if not t.endswith('_2_3.png')]
    else:
        names = sys.argv[2:]
        tpls = []
        for n in names:
            hits = glob.glob(os.path.join(tpl_dir, n if n.endswith('.png') else n + '.png'))
            tpls += hits
    match(frame, tpls)
