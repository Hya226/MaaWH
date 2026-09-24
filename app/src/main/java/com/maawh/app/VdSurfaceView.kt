package com.maawh.app

import android.content.Context
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * 虚拟屏预览 SurfaceView：直渲通路的窗口提供端。
 *
 * surfaceCreated → 向 [VdPreview] 认领渲染窗口（服务端 GPU 每帧直绘到本 Surface）；
 * surfaceDestroyed → 释放（必须先于框架回收 Surface 完成认领记账，服务端下次
 * 渲染前会安全摘除窗口）。直渲降级期间本视图 GONE，位图路径（ImageView）顶上；
 * [reclaim] 用于「Surface 还活着但渲染窗口被别处接管过」的再认领（如全屏页关闭后）。
 *
 * 坐标系提示：本视图默认不消费触摸（onTouchEvent=false），下层的 ImageView 触摸
 * 逻辑（handleImageTouch / 全屏手势）原样工作，无需迁移。
 */
class VdSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr) {

    private val cb = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            VdPreview.claim(this@VdSurfaceView, holder.surface)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            // 尺寸变化无需重新挂载：native 端每帧按 ANativeWindow 实际尺寸自适应
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            VdPreview.release(this@VdSurfaceView)
        }
    }

    init {
        // Surface 必须盖在窗口内容之上：主页/悬浮窗/全屏页都靠它压住下面的 ImageView。
        // setZOrderOnTop 须在 surface 创建前调用（init 里正合适）。
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(cb)
    }

    /** Surface 仍有效时的再认领（onResume / 降级自愈重试）。 */
    fun reclaim() {
        val s: Surface? = holder.surface
        if (s != null && s.isValid) {
            VdPreview.claim(this, s)
        }
    }
}
