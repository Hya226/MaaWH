package com.maawh.app

import android.graphics.Bitmap

/** 主界面与全屏页共享的虚拟屏帧 */
object VdShared {
    @Volatile
    var frame: Bitmap? = null
}
