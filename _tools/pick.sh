#!/usr/bin/env bash
# 抓当前虚拟屏帧 → 打开模板框选工具（带负样本校验）
# 注：框选工具已随流程编辑器独立到 E:\MaaWH Studio，本脚本仍留在仓库里负责抓帧与负样本累积
# 用法: bash _tools/pick.sh            # 抓帧并开工具
#       bash _tools/pick.sh 帧文件名     # 用已有帧开工具
set -e
export MSYS2_ARG_CONV_EXCL="*"
ADB=/d/android-studio/Sdk/platform-tools/adb.exe
DEV=2c92e197
ROOT=/e/MaaWH
EDITOR="/e/MaaWH Studio"

if [ -n "$1" ]; then
  FRAME="$ROOT/_tools/$1"
else
  # 可靠抓帧三步：确保前台 → FrameSave → 取回
  "$ADB" -s $DEV shell "am start -n com.maawh.app/.MainActivity" >/dev/null 2>&1
  sleep 1
  "$ADB" -s $DEV shell "am start -n com.maawh.app/.MainActivity --activity-single-top --es entry FrameSave" >/dev/null 2>&1
  sleep 5
  FRAME="$ROOT/_tools/pick_frame.jpg"
  "$ADB" -s $DEV exec-out run-as com.maawh.app cat files/cur_frame.jpg > "$FRAME" 2>/dev/null
fi

if [ ! -s "$FRAME" ]; then
  echo "抓帧失败（帧为空）。请确认：App 在前台、虚拟屏已启动、游戏画面正常。"
  exit 1
fi

# 把刚抓的帧也加进负样本目录（下次框选时能校验区分度）
mkdir -p "$ROOT/_tools/neg_frames"
cp "$FRAME" "$ROOT/_tools/neg_frames/$(basename "$FRAME")" 2>/dev/null || true

echo "帧: $FRAME ($(stat -c%s "$FRAME") bytes)"
if [ ! -f "$EDITOR/template_picker.py" ]; then
  echo "找不到框选工具 $EDITOR/template_picker.py（工具目录可能被挪走了，改本脚本的 EDITOR 变量）"
  exit 1
fi
# 转成 Windows 路径再交给 Python（Git Bash 的 /e/... 路径 Python 读不了）
WIN_FRAME=$(cygpath -w "$FRAME" 2>/dev/null || echo "$FRAME")
WIN_NEG=$(cygpath -w "$ROOT/_tools/neg_frames" 2>/dev/null || echo "$ROOT/_tools/neg_frames")
cd "$EDITOR"
nohup python template_picker.py "$WIN_FRAME" --neg "$WIN_NEG" > "$EDITOR/picker.log" 2>&1 &
sleep 4
echo "框选工具已打开。框完保存后把文件名告诉我。"
