package io.github.shimo.smartclickocr.overlay

import android.content.Context

/**
 * Remembers where the user parked the floating button, so it reappears there
 * after a restart (spec §6.1 "合理的 UI 配置").
 */
object FloatingPositionStore {

    private const val PREFS = "smartclickocr_floating"

    fun load(context: Context): Pair<Int, Int> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Pair(p.getInt("x", -1), p.getInt("y", -1))
    }

    fun save(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt("x", x)
            .putInt("y", y)
            .apply()
    }
}
