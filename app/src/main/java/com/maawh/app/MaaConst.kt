package com.maawh.app

/**
 * 全局常量收口。此前同一份定义散在 MainActivity / ShellUserService / ShizukuShell /
 * MaaBridge 各自的 companion 里（游戏包名、虚拟屏尺寸各有两份副本），改一处漏一处
 * 就是坑；现在统一从这里引用。
 *
 * ShellUserService（Java，跑在 Shizuku 服务进程）与 App 进程同 APK 同 classloader，
 * 可直接引用本 object 编译出的静态字段。
 */
object MaaConst {

    /** 《物华弥新》游戏包名（Shizuku/引擎的所有命令只针对它） */
    const val GAME_PKG = "com.cipaishe.wuhua.bilibili"

    /** 游戏 Activity 全类名（UserService 里 setClassName 用） */
    const val GAME_ACT_CLS = "com.cipaishe.wuhua.bilibili.activity.ONESDKLaunchActivity"

    /** 虚拟屏基准尺寸：必须与 whmx 模板/坐标基准帧一致（标准 16:9 720p），不可单独改 */
    const val VD_W = 1280
    const val VD_H = 720
    const val VD_DPI = 320

    // ---- MaaFramework option key（对应引擎 OptionPropertyTypes.h 的枚举值）----
    /** MaaGlobalSetOption：引擎日志目录 */
    const val OPT_GLOBAL_LOG_DIR = 1
    /** MaaControllerSetOption：截图不做缩放/旋转归一化（模板按原始分辨率制作） */
    const val OPT_CTRL_SCREENSHOT_USE_RAW_SIZE = 3

    // ---- MaaFramework 任务状态码（MaaStatus 枚举值）----
    const val STATUS_PENDING = 1000
    const val STATUS_RUNNING = 2000
    const val STATUS_SUCCEEDED = 3000
    const val STATUS_FAILED = 4000

    /** Shizuku 管理器包名（引导用户去启动服务用） */
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
}
