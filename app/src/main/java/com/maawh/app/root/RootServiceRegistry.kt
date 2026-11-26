package com.maawh.app.root

import android.os.IBinder
import java.util.concurrent.CompletableFuture

/** Root 服务 binder 的单槽回投点：连接器 register → starter 进程上来 complete */
object RootServiceRegistry {

    @Volatile
    private var pending: CompletableFuture<IBinder>? = null

    fun register(): CompletableFuture<IBinder> {
        val f = CompletableFuture<IBinder>()
        pending = f
        return f
    }

    fun onBinderArrived(binder: IBinder) {
        pending?.complete(binder)
    }
}
