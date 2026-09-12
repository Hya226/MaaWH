package com.maawh.app;

import android.os.ParcelFileDescriptor;

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
}
