package com.audioscope

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/** 數字格式化（純 Kotlin，不依賴 Android）。 */
object Fmt {
    fun time(sec: Double): String {
        val s = max(0.0, sec)
        val m = (s / 60).toInt()
        return String.format(Locale.US, "%02d:%06.3f", m, s - 60 * m)
    }

    fun db(v: Double, unit: String = "dBFS", digits: Int = 2): String =
        if (!v.isFinite()) "−∞ $unit"
        else (String.format(Locale.US, "%.${digits}f", v) + " " + unit).replace('-', '−')

    fun hz(f: Double): String = if (f >= 1000) {
        val k = f / 1000
        if (abs(k - k.roundToInt()) < 1e-6) "${k.roundToInt()}k" else String.format(Locale.US, "%.1fk", k)
    } else "${f.roundToInt()}"

    fun size(bytes: Long): String {
        var v = bytes.toDouble()
        for (u in arrayOf("B", "KB", "MB", "GB")) {
            if (v < 1024) return if (u == "B") "${v.toLong()} B" else String.format(Locale.US, "%.2f %s", v, u)
            v /= 1024
        }
        return String.format(Locale.US, "%.2f TB", v)
    }

    fun n(v: Double, d: Int = 1): String = String.format(Locale.US, "%,.${d}f", v)
    fun i(v: Long): String = String.format(Locale.US, "%,d", v)
    fun signed(v: Double, d: Int = 1): String = String.format(Locale.US, "%+.${d}f", v).replace('-', '−')
}

fun ampDb(x: Double): Double = if (x > 0) 20 * log10(x) else Double.NEGATIVE_INFINITY
