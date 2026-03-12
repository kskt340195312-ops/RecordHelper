package com.recordhelper.data

import android.content.Context
import android.content.SharedPreferences

object AppSettings {
    private const val PREFS_NAME = "record_helper_prefs"
    private const val KEY_RATIO_PERCENT = "ratio_percent"
    private const val KEY_MIN_COMMENTS = "min_comments"
    private const val KEY_IS_RUNNING = "is_running"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 比例条件：20, 30, 40, 50 表示百分比 */
    fun getRatioPercent(context: Context): Int =
        prefs(context).getInt(KEY_RATIO_PERCENT, 50)

    fun setRatioPercent(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_RATIO_PERCENT, value).apply()

    /** 最低评论数 */
    fun getMinComments(context: Context): Int =
        prefs(context).getInt(KEY_MIN_COMMENTS, 20)

    fun setMinComments(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_MIN_COMMENTS, value).apply()

    /** 服务运行状态 */
    fun isRunning(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IS_RUNNING, false)

    fun setRunning(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_IS_RUNNING, value).apply()
}
