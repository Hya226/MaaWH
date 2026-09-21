package com.maawh.app

import android.app.Application
import android.content.Context

/**
 * 自定义 Application：把界面整体缩放挂到 applicationContext 上。
 *
 * 必须在这里也包一层——Activity 的 attachBaseContext 只管自己，而悬浮窗
 * （FloatingPanel）是用 `applicationContext` 建视图的，只在 Activity 层做的话
 * 会出现「主界面缩了、悬浮窗没缩」的错位。
 */
class MaaApp : Application() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(DisplayScale.wrap(newBase))
    }
}
