# -*- coding: utf-8 -*-
import zipfile, hashlib, os, sys

APK = r"E:\MaaWH\app\build\outputs\apk\beta\MaaWH-beta-v0.3.5.apk"
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

# 1 版本号
ver = names.get("assets/whmx/version.txt")
import re
# versionCode/versionName 从 APK 二进制不好直接读,用 aapt 不好使就看 badging 跳过,改从构建输出确认
# 这里核对 version.txt 存在且较新
check(ver is not None, "version.txt 存在")
if ver is not None: print("      version.txt 内容时间戳:", zread("assets/whmx/version.txt").decode().strip())

# 2 assets/whmx/pipeline 与仓库字节级一致
repo_pl = os.path.join(ROOT, "whmx", "pipeline")
repo_files = sorted(f for f in os.listdir(repo_pl) if f.endswith(".json"))
apk_pl = {n[len("assets/whmx/pipeline/"):] for n in names if n.startswith("assets/whmx/pipeline/")}
check(len(repo_files) == 31 and apk_pl == set(repo_files), f"pipeline 集 31 个一致 (repo={len(repo_files)}, apk={len(apk_pl)})")
check("vf_OCR记录测试.json" not in apk_pl, "测试流程 vf_OCR记录测试.json 已不进包")
bad = []
for f in repo_files:
    a = hashlib.md5(zread("assets/whmx/pipeline/" + f)).hexdigest()
    b = hashlib.md5(open(os.path.join(repo_pl, f), "rb").read()).hexdigest()
    if a != b: bad.append(f)
check(not bad, "pipeline 内容字节级一致" + ("" if not bad else f" 不一致: {bad}"))
newp = zread("assets/whmx/pipeline/vf_派遣公司事务.json").decode("utf-8")
check("VF_派遣公司事务_60_Hit" in newp, "vf_派遣公司事务.json 含 OCR 记录节点(_60_Hit)")
si = zread("assets/whmx/interface.json").decode("utf-8")
check("顿舍体力" in si and "主页体力" in si, "interface.json 含 record 声明(顿舍体力/主页体力)")

# 3 interface.json 一致 + bundle version + removed
ri = open(os.path.join(ROOT, "whmx", "interface.json"), "rb").read()
ai = zread("assets/whmx/interface.json")
check(hashlib.md5(ri).hexdigest() == hashlib.md5(ai).hexdigest(), "interface.json 与仓库一致")
s0 = ai.decode("utf-8")
check('"version": "0.2.2"' in s0, "bundle version 0.2.2")
check('"OCR记录测试"' in s0.split('"task"')[0], "removed 名单含 OCR记录测试")

# 4 image 目录数量
apk_img = [n for n in names if n.startswith("whmx/image/") or n.startswith("assets/whmx/image/")]
repo_img = os.listdir(os.path.join(ROOT, "whmx", "image"))
apk_img = [n for n in names if n.startswith("assets/whmx/image/")]
check(len(apk_img) == len(repo_img), f"模板图数量一致 (repo={len(repo_img)}, apk={len(apk_img)})")

# 5 waiqin roster 进包且一致
rw = zread("assets/waiqin/roster.json")
check(hashlib.md5(rw).hexdigest() == hashlib.md5(open(os.path.join(ROOT,"waiqin","roster.json"),"rb").read()).hexdigest(), "waiqin/roster.json 与仓库一致")

# 6 jniLibs 正本 so 与仓库 jniLibs md5 一致
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
    print("SKIP  本地无 app/jniLibs 目录,跳过 so 对比")

print("\n== 结果:", "ALL PASS" if ok else "存在 FAIL", "==")
sys.exit(0 if ok else 1)
