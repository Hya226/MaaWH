package com.maawh.app;

import android.os.IBinder;

// Root 服务进程 → App 的 binder 回投接口（bindService 回路，见 RootBinderHolderService）
interface IBinderRegister {

    // 注册 root 进程内实现的服务 binder（App 拿到的引用指向 root 进程）
    void registerServiceBinder(in IBinder service);
}
