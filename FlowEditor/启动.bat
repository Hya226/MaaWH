@echo off
rem 双击启动流程编辑器（pythonw 无黑窗；崩溃日志见 FlowEditor\flow_editor.log）
cd /d "%~dp0"
where pythonw >nul 2>nul && (start "" pythonw flow_editor.py) || (start "" "D:\python\pythonw.exe" flow_editor.py)
