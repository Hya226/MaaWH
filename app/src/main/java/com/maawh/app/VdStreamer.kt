package com.maawh.app

import android.graphics.BitmapFactory
import kotlin.concurrent.thread

/**
 * 虚拟屏收帧器：独立后台线程轮询抓帧并解码，最新帧写入 VdShared.frame。
 * 页面只负责轻量显示，避免多页同时抓帧导致卡顿。
 */
object VdStreamer {
    @Volatile
    private var running = false
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "vd-stream") {
            while (running) {
                try {
                    val bytes = ShizukuShell.grabVirtualFrame()
                    if (bytes.isNotEmpty()) {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let {
                            VdShared.frame = it
                        }
                    }
                } catch (e: Throwable) {
                    // ignore
                }
                try { Thread.sleep(150) } catch (e: InterruptedException) { break }
            }
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }
}
