package com.maawh.app;

import android.os.ParcelFileDescriptor;
import android.view.Surface;

interface IUserService {

    // 保留方法：Shizuku 销毁服务时调用（事务码 16777114）
    void destroy() = 16777114;

    // 执行 shell 命令，stdout 写入管道；返回进程退出码
    int exec(in String[] cmd, in ParcelFileDescriptor stdout) = 1;

    // M4 探测：尝试创建虚拟显示器并返回结果摘要
    String virtualProbe() = 2;

    // M4-②：创建并保活虚拟屏，尝试把游戏启动到该屏；返回摘要
    String startVirtualGame() = 3;

    // 抓取虚拟屏最新一帧（JPEG 字节，无帧则空）
    byte[] grabVirtualFrame() = 4;

    // 释放虚拟屏
    void stopVirtual() = 5;

    // 向虚拟屏注入一次点击（VD 原生分辨率坐标）
    String injectTapVD(int x, int y) = 6;

    // 向虚拟屏注入一次滑动（VD 原生分辨率坐标）
    String injectSwipeVD(int x1, int y1, int x2, int y2, int duration) = 7;

    // 虚拟屏是否存活（服务端权威状态，供引擎路由判定）
    boolean isVdAlive() = 8;

    // 读取流音量（STREAM_MUSIC=3）；规避 shell 命令设置音量被系统忽略的问题
    int getStreamVolume(int stream) = 9;

    // 设置流音量（服务端 AudioManager.setStreamVolume）
    void setStreamVolume(int stream, int index) = 10;

    // 流式触摸注入（实时手势，仿 maameow down/move/up 拆分）
    void touchDown(int x, int y) = 11;
    void touchMove(int x, int y) = 12;
    void touchUp(int x, int y) = 13;

    // ===== 虚拟屏预览 GPU 零拷贝直渲（对标 MAA-Meow 的 bridge_preview） =====

    // 挂载预览 Surface（App 进程 SurfaceView 的画面接收端）：服务端每帧 GPU 直绘到它；
    // 返回 false = 服务端不支持（native 库缺失/拿不到窗口），App 端降级回 JPEG 轮询
    boolean setPreviewSurface(in Surface surface) = 14;

    // 摘除预览 Surface（幂等；App 端预览窗口销毁/切换时调用）
    void releasePreviewSurface() = 15;

    // 预览已成功渲染的帧计数：App 端用「挂载后计数是否增长」判定直渲通路健康，
    // 不健康（旧系统拿不到 HardwareBuffer / EGL 失败）就保持 JPEG 轮询降级
    long previewFrameCount() = 16;

    // 已从帧池消费的帧数（看门狗判据：consumed 涨而 drawn 停 = 渲染器真挂需降级；
    // 两者都停 = 游戏画面静止，正常，不降级）
    long previewConsumedCount() = 17;
}
