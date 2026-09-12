@echo off
rem 独立启动模板框选工具（也可从流程编辑器里点「框选模板…」唤起）
cd /d "%~dp0"
where pythonw >nul 2>nul && (start "" pythonw template_picker.py) || (start "" "D:\python\pythonw.exe" template_picker.py)
