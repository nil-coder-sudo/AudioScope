package com.audioscope

import android.content.Context
import android.graphics.Color
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

object Pal {
    val BG = Color.parseColor("#0B0E14")
    val PANEL = Color.parseColor("#11151D")
    val BTN = Color.parseColor("#182030")
    val BTN_EDGE = Color.parseColor("#273247")
    val SEL = Color.parseColor("#163A57")
    val GRID = Color.parseColor("#1C2433")
    val AXIS = Color.parseColor("#5B6880")
    val TEXT = Color.parseColor("#D7DDE8")
    val MUTED = Color.parseColor("#7F8EA6")
    val ACCENT = Color.parseColor("#4CC2FF")
    val HOLD = Color.parseColor("#FFB454")
    val PEAK = Color.parseColor("#FF5C7A")
    val WARN = Color.parseColor("#FFB454")
    val GREEN_DARK = Color.parseColor("#1F9E6E")
    val GREEN = Color.parseColor("#3FD68D")
    val YELLOW = Color.parseColor("#F5D547")
    val ORANGE = Color.parseColor("#FF9F43")
    val RED = Color.parseColor("#FF4D5E")
    val WHITE = Color.WHITE
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

object Colormaps {
    val names = arrayOf("inferno", "magma", "turbo", "viridis", "灰階")

    private val stops: Map<String, Array<IntArray>> = mapOf(
        "inferno" to arrayOf(
            intArrayOf(0, 0, 4), intArrayOf(40, 11, 84), intArrayOf(101, 21, 110), intArrayOf(159, 42, 99),
            intArrayOf(212, 72, 66), intArrayOf(245, 125, 21), intArrayOf(250, 193, 39), intArrayOf(252, 255, 164),
        ),
        "magma" to arrayOf(
            intArrayOf(0, 0, 4), intArrayOf(28, 16, 68), intArrayOf(79, 18, 123), intArrayOf(129, 37, 129),
            intArrayOf(181, 54, 122), intArrayOf(229, 80, 100), intArrayOf(251, 135, 97), intArrayOf(254, 194, 135),
            intArrayOf(252, 253, 191),
        ),
        "turbo" to arrayOf(
            intArrayOf(48, 18, 59), intArrayOf(70, 107, 227), intArrayOf(40, 187, 236), intArrayOf(50, 241, 142),
            intArrayOf(164, 252, 59), intArrayOf(237, 208, 58), intArrayOf(251, 128, 34), intArrayOf(210, 48, 5),
            intArrayOf(122, 4, 3),
        ),
        "viridis" to arrayOf(
            intArrayOf(68, 1, 84), intArrayOf(72, 40, 120), intArrayOf(62, 74, 137), intArrayOf(49, 104, 142),
            intArrayOf(38, 130, 142), intArrayOf(31, 158, 137), intArrayOf(53, 183, 121), intArrayOf(109, 205, 89),
            intArrayOf(180, 222, 44), intArrayOf(253, 231, 37),
        ),
        "灰階" to arrayOf(intArrayOf(0, 0, 0), intArrayOf(255, 255, 255)),
    )

    fun lut(name: String): IntArray {
        val st = stops[name] ?: stops.getValue("inferno")
        return IntArray(256) { i ->
            val t = i / 255.0 * (st.size - 1)
            val a = min(floor(t).toInt(), st.size - 2)
            val f = t - a
            fun ch(k: Int) = (st[a][k] + (st[a + 1][k] - st[a][k]) * f).roundToInt().coerceIn(0, 255)
            Color.rgb(ch(0), ch(1), ch(2))
        }
    }
}
