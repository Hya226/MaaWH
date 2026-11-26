# -*- coding: utf-8 -*-
import zipfile, hashlib, os, sys, re

APK = r"E:\MaaWH\app\build\outputs\apk\beta\MaaWH-beta-v0.4.5.apk"
ROOT = r"E:\MaaWH"

def zname(zi):
    # aapt2 写条目名不设 UTF-8 flag(EFS),zipfile 按 CP437 解码 → 中文名乱码
    if not (zi.flag_bits & 0x800):
        return zi.filename.encode('cp437').decode('utf-8')
    return zi.filename

z = zipfile.ZipFile(APK)
names = {zname(zi): zi for zi in z.infolist()}
def zread(path):
    return z.read(names[path])
ok = True
def check(cond, msg):
    global ok
    print(("PASS " if cond else "FAIL ") + msg)
    if not cond: ok = False

# 1 version.txt（bundle 构建时间戳，释放比对用）
ver = names.get("assets/whmx/version.txt")
check(ver is not None, "version.txt 存在")
if ver is not None: print("      version.txt:", zread("assets/whmx/version.txt").decode().strip())

# 2 pipeline 与仓库字节级一致（数量动态，不写死）
repo_pl = os.path.join(ROOT, "whmx", "pipeline")
repo_files = sorted(f for f in os.listdir(repo_pl) if f.endswith(".json"))
apk_pl = {n[len("assets/whmx/pipeline/"):] for n in names if n.startswith("assets/whmx/pipeline/")}
check(apk_pl == set(repo_files), f"pipeline 集一致 (repo={len(repo_files)}, apk={len(apk_pl)})")
check("vf_OCR记录测试.json" in apk_pl, "vf_OCR记录测试.json 回归上架在包（11-17 有意恢复）")
check("vf_仓库识别徽章.json" in apk_pl and "vf_仓库识别票券.json" in apk_pl, "vf_仓库识别徽章/票券 在包（三仓库导航）")
bad = []
for f in repo_files:
    a = hashlib.md5(zread("assets/whmx/pipeline/" + f)).hexdigest()
    b = hashlib.md5(open(os.path.join(repo_pl, f), "rb").read()).hexdigest()
    if a != b: bad.append(f)
check(not bad, "pipeline 内容字节级一致" + ("" if not bad else f" 不一致: {bad}"))
newp = zread("assets/whmx/pipeline/vf_派遣公司事务.json").decode("utf-8")
check("VF_派遣公司事务_60_Hit" in newp, "vf_派遣公司事务.json 含 OCR 记录节点(_60_Hit)")

# 3 interface.json 一致 + bundle version 动态校验 + removed 名单
ri = open(os.path.join(ROOT, "whmx", "interface.json"), "rb").read()
ai = zread("assets/whmx/interface.json")
check(hashlib.md5(ri).hexdigest() == hashlib.md5(ai).hexdigest(), "interface.json 与仓库一致")
s0 = ai.decode("utf-8")
_rv = re.search(r'"version":\s*"([^"]+)"', ri.decode("utf-8"))
_av = re.search(r'"version":\s*"([^"]+)"', s0)
check(_av is not None and re.match(r'^\d+\.\d+\.\d+$', _av.group(1)) is not None,
      "bundle version 格式合法 (apk=%s)" % (_av.group(1) if _av else "缺失"))
check(_rv is not None and _av is not None and _av.group(1) == _rv.group(1),
      "bundle version 与仓库一致 (repo=%s, apk=%s)" % (_rv.group(1) if _rv else "缺失", _av.group(1) if _av else "缺失"))
check('"OCR记录测试"' in s0.split('"task"')[0], "removed 名单含 OCR记录测试")
check('"仓库识别"' in s0, "interface 注册【仓库识别】任务")

# 4 image 目录数量
apk_img = [n for n in names if n.startswith("assets/whmx/image/")]
repo_img = [f for f in os.listdir(os.path.join(ROOT, "whmx", "image")) if f.endswith(".png")]
check(len(apk_img) == len(repo_img), f"模板图数量一致 (repo={len(repo_img)}, apk={len(apk_img)})")

# 5 仓库识别模板：assets/warehouse/resource/ 与仓库字节级一致（0.4.x 新增功能）
repo_wh = os.path.join(ROOT, "app", "src", "main", "assets", "warehouse", "resource")
repo_wh_files = sorted(f for f in os.listdir(repo_wh) if f.endswith(".png"))
apk_wh = {n[len("assets/warehouse/resource/"):] for n in names if n.startswith("assets/warehouse/resource/")}
check(apk_wh == set(repo_wh_files), f"仓库识别模板集一致 (repo={len(repo_wh_files)}, apk={len(apk_wh)})")
badw = []
for f in repo_wh_files:
    a = hashlib.md5(zread("assets/warehouse/resource/" + f)).hexdigest()
    b = hashlib.md5(open(os.path.join(repo_wh, f), "rb").read()).hexdigest()
    if a != b: badw.append(f)
check(not badw, "仓库识别模板字节级一致" + ("" if not badw else f" 不一致: {badw[:6]}"))

# 5b 徽章/票券模板目录（0.4.x 三仓库识别）
for cat in ("badge", "ticket"):
    repo_c = os.path.join(ROOT, "app", "src", "main", "assets", "warehouse", cat)
    repo_c_files = sorted(f for f in os.listdir(repo_c) if f.endswith(".png"))
    apk_c = {n[len("assets/warehouse/%s/" % cat):] for n in names if n.startswith("assets/warehouse/%s/" % cat)}
    check(apk_c == set(repo_c_files), f"{cat} 模板集一致 (repo={len(repo_c_files)}, apk={len(apk_c)})")
    badc = []
    for f in repo_c_files:
        a = hashlib.md5(zread("assets/warehouse/%s/" % cat + f)).hexdigest()
        b = hashlib.md5(open(os.path.join(repo_c, f), "rb").read()).hexdigest()
        if a != b: badc.append(f)
    check(not badc, f"{cat} 模板字节级一致" + ("" if not badc else f" 不一致: {badc}"))
check("颜料-矿物颜料.png" in repo_wh_files and "颜料-矿石颜料.png" not in repo_wh_files, "资源改名生效（矿石→矿物颜料）")

# 6 抽卡 OCR 模型进包且一致
for f in ("ch_PP-OCRv4_rec_infer.onnx", "ppocr_keys_v1.txt"):
    key = "assets/gacha/ocr_models/" + f
    rp = os.path.join(ROOT, "app", "src", "main", "assets", "gacha", "ocr_models", f)
    if key in names and os.path.isfile(rp):
        check(hashlib.md5(zread(key)).hexdigest() == hashlib.md5(open(rp, "rb").read()).hexdigest(), f"gacha/ocr_models/{f} 一致")
    else:
        check(False, f"gacha/ocr_models/{f} 缺失 (apk={key in names}, repo={os.path.isfile(rp)})")

# 7 waiqin roster 进包且一致
rw = zread("assets/waiqin/roster.json")
check(hashlib.md5(rw).hexdigest() == hashlib.md5(open(os.path.join(ROOT, "waiqin", "roster.json"), "rb").read()).hexdigest(), "waiqin/roster.json 与仓库一致")

# 8 jniLibs 正本 so md5 一致（keepDebugSymbols 守护）
repo_jni = os.path.join(ROOT, "app", "src", "main", "jniLibs", "arm64-v8a")
if os.path.isdir(repo_jni):
    mism, missing = [], []
    apk_so = [n for n in names if n.startswith("lib/arm64-v8a/") and n.endswith(".so")]
    for f in sorted(os.listdir(repo_jni)):
        key = "lib/arm64-v8a/" + f
        if key not in names: missing.append(f); continue
        if hashlib.md5(zread(key)).hexdigest() != hashlib.md5(open(os.path.join(repo_jni, f), "rb").read()).hexdigest():
            mism.append(f)
    check(not missing and not mism, f"jniLibs 正本 so md5 一致 ({len(os.listdir(repo_jni))} 个)" + (f" 缺:{missing} 异:{mism}" if (missing or mism) else ""))
    print(f"      APK 内 so 总数: {len(apk_so)}")
else:
    print("SKIP  本地无 jniLibs 目录,跳过 so 对比")

print("\n== 结果:", "ALL PASS" if ok else "存在 FAIL", "==")
sys.exit(0 if ok else 1)
