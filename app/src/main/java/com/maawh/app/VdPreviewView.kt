package com.maawh.app

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView

/**
 * 虚拟屏预览视图：直渲通路的窗口提供端（TextureView 实现）。
 *
 * 为什么用 TextureView 而不是 SurfaceView + setZOrderOnTop：预览区需要三层共存——
 * 底层 ImageView（JPEG 降级位图）/ 中层视频 / 上层帧率角标等普通 View。SurfaceView 的
 * onTop 模式会把视频顶到整个窗口所有 View 之上（角标被盖住），默认模式又有挖洞穿透
 * 问题；TextureView 在窗口内做普通 View 合成，层级关系自然成立（2026-09-24 实测踩坑）。
 *
 * surfaceTextureAvailable → 向 [VdPreview] 认领渲染窗口（服务端 GPU 每帧直绘到本
 * Surface）；destroyed → 释放。直渲降级期间本视图无内容（全透明），位图路径顶上；
 * [reclaim] 用于「Surface 还活着但渲染窗口被别处接管过」的再认领（如全屏页关闭后）。
 *
 * 坐标系提示：本视图默认不消费触摸（onTouchEvent=false），下层的 ImageView 触摸
 * 逻辑（handleImageTouch / 全屏手势）原样工作，无需迁移。
 */
class VdPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr) {

    private var surface: Surface? = null

    private val listener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
            // 缓冲固定 VD 原生尺寸：与服务端帧 1:1，视图侧再缩放显示
            st.setDefaultBufferSize(MaaConst.VD_W, MaaConst.VD_H)
            val s = Surface(st)
            surface = s
            VdPreview.claim(this@VdPreviewView, s)
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
            st.setDefaultBufferSize(MaaConst.VD_W, MaaConst.VD_H)
        }

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            VdPreview.release(this@VdPreviewView)
            surface?.release()
            surface = null
            return true
        }

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
            // 每帧回调无需处理：服务端直绘即终点
        }
    }

    init {
        surfaceTextureListener = listener
        isOpaque = false
    }

    /** Surface 仍有效时的再认领（onResume / 降级自愈重试）。 */
    fun reclaim() {
        val s = surface
        if (s != null && isAvailable) {
            VdPreview.claim(this, s)
        }
    }
}
