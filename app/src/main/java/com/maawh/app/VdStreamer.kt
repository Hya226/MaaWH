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

    /** 已解码帧计数（帧率角标的降级路径数据源，VdPreview 每秒差分） */
    @Volatile
    var decodedCount: Long = 0
        private set

    /** 轮询线程是否在跑（区分「降级中」和「没在显示」） */
    @Volatile
    var isRunning = false
        private set

    fun start() {
        if (running) return
        running = true
        isRunning = true
        VdPreview.onStreamerStarted()
        worker = thread(name = "vd-stream") {
            while (running) {
                try {
                    // 直渲通路健康时本线程空转（0.5s 一睁眼）：不抓帧不解码不占 CPU；
                    // 通路一旦降级立即自动恢复 JPEG 轮询，调用方无需感知切换
                    if (VdPreview.nativeActive) {
                        try { Thread.sleep(500) } catch (e: InterruptedException) { break }
                        continue
                    }
                    val bytes = ShizukuShell.grabVirtualFrame()
                    if (bytes.isNotEmpty()) {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let {
                            VdShared.frame = it
                            decodedCount++
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
        isRunning = false
        worker?.interrupt()
        worker = null
    }
}
