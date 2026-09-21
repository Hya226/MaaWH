package com.maawh.app

import android.content.Context
import android.content.res.Configuration
import kotlin.math.roundToInt

/**
 * 界面整体缩放（对标 MAA-Meow 设置页的「页面缩放」）。
 *
 * 只改 [Configuration.densityDpi]：dp 与 sp 同比例缩放，字号、间距、控件一起变——
 * 与「只调字体」不同（那个只动 sp，会破坏字与间距的比例）。用法是在最外层
 * attachBaseContext 里把 Context 包一层（见 MaaApp / 三个 Activity）。
 *
 * ⚠ 这是 App 界面自己的密度，与虚拟屏尺寸、引擎的 `MaaConst.VD_DPI`、模板像素无关，
 * 缩放多少都不影响识别与点击。
 */
object DisplayScale {

    private const val PREF = "maawh_display"
    private const val KEY = "scalePercent"

    /** 自动档存这个值 */
    const val AUTO = 0
    const val MIN = 80
    const val MAX = 110

    /** 自动档要对齐的参考屏宽（dp）：常见手机 1080px / density 2.75 ≈ 392dp */
    private const val REF_DP = 392f

    /**
     * 自动档的值，attachBaseContext 时按**原始**配置算出（原始配置不受本设置影响，
     * 不会自己套自己）。进程生命周期内有效，供 [percent] 与悬浮窗尺寸计算用。
     */
    @Volatile
    private var autoPercent = 100

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 存档值：[AUTO] = 自动 */
    fun savedPercent(ctx: Context): Int = prefs(ctx).getInt(KEY, AUTO)

    fun setSaved(ctx: Context, percent: Int) {
        prefs(ctx).edit().putInt(KEY, percent).apply()
    }

    fun isAuto(ctx: Context): Boolean = savedPercent(ctx) == AUTO

    /** 实际生效的百分比：自动档取按屏宽算出的值，越界的存档值也回落到自动 */
    fun percent(ctx: Context): Int =
        savedPercent(ctx).takeIf { it in MIN..MAX } ?: autoPercent

    /**
     * 包一层带缩放的 Context。**只能传原始的 base**（在 attachBaseContext 里调用），
     * 已经包过的 Context 再包一次会叠加缩放。
     */
    fun wrap(base: Context): Context {
        autoPercent = computeAuto(base)
        val p = percent(base)
        if (p == 100) return base
        val dm = base.resources.displayMetrics
        val cfg = Configuration(base.resources.configuration)
        cfg.densityDpi = (cfg.densityDpi * p / 100f).roundToInt().coerceAtLeast(72)
        // 配置里的 dp 尺寸不会跟着 densityDpi 自己重算，同步一下，免得读到的仍是旧值
        cfg.screenWidthDp = (dm.widthPixels * 160f / cfg.densityDpi).roundToInt()
        cfg.screenHeightDp = (dm.heightPixels * 160f / cfg.densityDpi).roundToInt()
        return base.createConfigurationContext(cfg)
    }

    /** 自动档 = 把屏幕宽度折算到参考 dp：窄屏（或系统字体被放大挤窄）→ 缩小，宽屏 → 放大 */
    private fun computeAuto(base: Context): Int {
        val dm = base.resources.displayMetrics
        val density = if (dm.density > 0f) dm.density else 1f
        return ((dm.widthPixels / density / REF_DP) * 100f).roundToInt().coerceIn(MIN, MAX)
    }
}
