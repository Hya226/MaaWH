package com.maawh.app

import android.content.Context

/**
 * 游戏静音标记（对齐 MAA-Meow 的 GameMuteCoordinator）：
 * 标记非空 = 游戏可能仍处于被 MaaWH 静音的状态、声音尚未恢复。内存外的 SharedPreferences
 * 只为进程重启后找回标记——进程被系统直接杀（无任何退出回调）时，下次启动凭标记自愈。
 *
 * 状态流转（maameow 原则）：
 * - 静音前先落标记再执行 appops deny，顺序不能反——静音生效后进程立刻崩溃，重启后仍能凭标记恢复；
 * - 恢复成功才清标记；恢复失败保留标记，等下次自愈重试；
 * - 有标记但游戏其实没被静音（比如退出钩子的孤儿 shell 已恢复过）无害：恢复是幂等空操作，顺手清标记。
 * - 游戏被静音却没有标记 = 永久静音（这正是 2026-09-18/19 两次「退出 MaaWH 游戏没声」的根源）。
 */
object GameAudioMarker {

    private const val PREFS = "maawh_game_audio"
    private const val KEY = "marked_pkg"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 静音前调用：先落标记（持久化），再去执行 appops deny */
    fun mark(context: Context, packageName: String) {
        prefs(context).edit().putString(KEY, packageName).apply()
    }

    fun marked(context: Context): String? = prefs(context).getString(KEY, null)

    private fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    /**
     * 统一恢复入口：标记非空就直接 reset（幂等，对没被静音的包是空操作），以 reset 后的
     * 校验为准——成功清标记，失败保留标记等下次自愈。
     * ⚠ 不要先查询再决定：查询（isGameAudioMuted）失败和"确实没静音"都返回 false，
     * 误把查询失败当无残留清掉标记 = 自愈永久失效（2026-09-19 实测踩中）。
     * 阻塞执行（Shizuku 命令），勿在主线程调用。返回是否执行了实际恢复。
     */
    fun restoreIfNeeded(context: Context): Boolean {
        if (marked(context) == null) return false
        if (ShizukuShell.resetGameAudio()) {
            clear(context)
            return true
        }
        return false         // reset 失败/校验仍 deny：标记保留，等下次自愈
    }
}
