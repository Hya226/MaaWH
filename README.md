# MaaWH

物华弥新 · 安卓自动化助手 —— Shizuku 虚拟屏 + MaaFramework

MaaWH 是一个《物华弥新》游戏自动化宿主，架构对标 MAA-Meow：App 通过 Shizuku 创建一块虚拟屏并把游戏投进去运行，MaaFramework 引擎在虚拟屏画面上做模板识别与点击注入，从而实现**游戏在后台挂机、手机前台照常使用**。

## 功能特性

- **一键长草队列**：勾选任务 → 顺序执行，失败不中断队列，结束给出失败汇总
- **多套配置**：任务勾选/参数/顺序可保存为多份配置，随时切换（对标 MAA-Meow）
- **额外队列**：独立于主队列的任务队列 + adb 直达入口，支持参数编辑
- **小工具**：工具型快捷操作入口，目前提供「抽卡识别」一键开始/停止（与抽卡页共用状态）
- **抽卡记录**：基于 OCR 的招集记录抓取——多账号独立存档、UP 标注、器者名单纠错、垫抽/出金/UP 平均统计，数据永久累积
- **参数化任务**：次数、目标角色等参数由 `interface.json` 的 option 声明，改 JSON 即生效，无需重编译
- **虚拟屏预览 + 后台悬浮窗**：主页实时预览虚拟屏画面；切后台自动弹出可拖动悬浮窗（实时画面 + 进度 + 停止）
- **通知栏进度**：前台服务常驻保活，通知显示「正在 2/5：任务名」，锁屏也能停止任务
- **游戏静音**：挂机静音/退出自动恢复，进程被强杀也有延时孤儿兜底恢复
- **任务日志**：引擎逐节点事件实时上屏，识别失败/超时自动归因（缺哪个模板、阈值多少）；跨会话任务历史可回溯
- **新手引导**：8 步主引导 + 分场景首访引导（抽卡页 / 配置管理）

## 内置任务

启动（虚拟屏投游戏 + 收口主页）· 征集 · 行会签到 · 每日免费礼包购买 · 派遣公司事务 · 领取奖励 · 外勤 · 装备分解 · 装卸装备 · 升好感度 · 刷活动关 · 关闭游戏

任务清单声明在 [`whmx/interface.json`](whmx/interface.json)（MAA 生态 PI v2 风格），加任务 = 改 JSON + 放模板图，App 无需重新编译。

## 环境要求

- Android 9.0（API 28）及以上；开发与测试环境为 Android 14，9~13 未经充分验证
- [Shizuku](https://shizuku.rikka.app/)：通过无线调试或 USB 调试启动（虚拟屏与点击注入都经由 Shizuku 授权的 shell 权限完成）
- 悬浮窗权限（可选）：后台悬浮面板需要「显示在其他应用上层」

## 使用

1. 安装 Release 里的 APK，启动 Shizuku
2. 打开 MaaWH，按新手引导走一遍（可随时在设置页重看）
3. 主页点「启动虚拟屏」——游戏被投入虚拟屏并收口到主页
4. 勾选要跑的任务（可编辑参数），点「开始任务」
5. 切到别的 App：悬浮窗会自动浮出显示实时画面和进度

## 从源码构建

```
git clone https://github.com/Hya226/MaaWH.git
# Android Studio 打开，或命令行：
./gradlew :app:assembleDebug      # 调试包
./gradlew :app:assembleRelease    # 发布签名包
```

- 需要 JDK 17 与 Android SDK（compileSdk 36）
- 发布签名：在仓库根放 `keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`）与对应 keystore 文件；没有签名配置时仍可构建 debug 包
- 构建时 `whmx/` 任务包会自动同步进 APK assets，装机后按版本戳自动释放到应用内部存储

## 任务包与流程编辑

- `whmx/pipeline/*.json`：流水线（`vf_*.json` 由可视化流程编辑器生成）
- `whmx/image/*.png`：模板图（虚拟屏原生帧 1280×720 上裁切）
- 流程编辑器/模板框选工具在独立仓库 **[MaaWH-Studio](https://github.com/Hya226/MaaWH-Studio)**：拖节点连线生成流水线、负样本校验的模板框选、一键同步到手机

## 免责声明

- 本项目仅供学习交流使用，请勿用于商业用途
- 仓库内的模板截图等《物华弥新》游戏相关素材版权归游戏官方所有
- 使用本工具产生的任何游戏账号后果（封禁等）由使用者自行承担
- 本项目与游戏官方及 MAA 系其他项目均无隶属关系

## 致谢

- [MaaFramework](https://github.com/MaaAssistantArknights/MaaFramework)（LGPL-3.0）—— 自动化引擎
- [Shizuku](https://github.com/RikkaApps/Shizuku)（Apache-2.0）—— 特权 API 框架
- OpenCV、ONNX Runtime、FastDeploy OCR（Apache-2.0）—— 引擎内置的视觉/OCR 组件
- [MAA-Meow](https://github.com/Aliothmoon/MAA-Meow) —— 悬浮窗/日志/引导等交互设计参考（本项目为独立实现，未使用其代码）

## 交流

官方 QQ 群：**1105246744**（问题反馈 / 使用交流）

## 赞助

<a href="https://afdian.com/a/maawh">
  <img width="200" src="https://pic1.afdiancdn.com/static/img/welcome/button-sponsorme.png" alt="在爱发电支持我" />
</a>

## License

[MIT](LICENSE)
