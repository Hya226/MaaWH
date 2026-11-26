package com.maawh.app.root

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.maawh.app.IBinderRegister

/**
 * Root 模式的 binder 回投中转：root 服务进程 bindService 上来，
 * 把它在 root 进程内实现的 ShellUserService binder 注册回 App。
 * App 端拿到的引用指向 root 进程，调用与 Shizuku 模式完全同构。
 */
class RootBinderHolderService : Service() {

    private val stub = object : IBinderRegister.Stub() {
        override fun registerServiceBinder(service: IBinder?) {
            service?.let { RootServiceRegistry.onBinderArrived(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder = stub
}
