package com.audioscope

/**
 * 聲道配置（純 Kotlin）。
 * 使用 WAVE_FORMAT_EXTENSIBLE 的喇叭位元：
 *   0 L, 1 R, 2 C, 3 LFE, 4 後左 BL, 5 後右 BR, 6 Lc, 7 Rc, 8 後中 BC, 9 側左 SL, 10 側右 SR …
 * Android AudioFormat 的 CHANNEL_OUT_* 正好是這些位元左移 2 位。
 */
object Layout {
    const val LFE = 3
    private val SPK = arrayOf(
        "L", "R", "C", "LFE", "Ls", "Rs", "Lc", "Rc", "Cs", "SL", "SR",
        "Tc", "TFL", "TFC", "TFR", "TBL", "TBC", "TBR",
    )

    fun defaultMask(ch: Int): Int = when (ch) {
        1 -> 0x4
        2 -> 0x3
        3 -> 0x7
        4 -> 0x33
        5 -> 0x37
        6 -> 0x3F
        7 -> 0x13F
        8 -> 0x63F
        else -> 0
    }

    /** 檔案給的遮罩與聲道數一致才採用，否則用標準預設排列。 */
    fun mask(ch: Int, m: Int): Int = if (m != 0 && Integer.bitCount(m) == ch) m else defaultMask(ch)

    /** 每個聲道對應的喇叭位元（-1 = 未知）。 */
    fun speakers(ch: Int, m: Int): IntArray {
        val mk = mask(ch, m)
        val out = IntArray(ch) { -1 }
        if (mk == 0) return out
        var k = 0
        for (b in 0 until 32) if (mk and (1 shl b) != 0 && k < ch) out[k++] = b
        return out
    }

    fun names(ch: Int, m: Int): Array<String> = when (ch) {
        1 -> arrayOf("Mono")
        2 -> arrayOf("L", "R")
        else -> speakers(ch, m).mapIndexed { i, b -> if (b in SPK.indices) SPK[b] else "Ch${i + 1}" }.toTypedArray()
    }

    /**
     * ITU-R BS.1770-4 聲道權重：LFE 不計；方位角 60°–120° 的環繞聲道 ×1.41。
     * 5.1 的 Ls/Rs 常被標成「後左/後右」，此時給 1.41；7.1 有側環繞時由側環繞拿 1.41。
     */
    fun loudnessWeights(ch: Int, m: Int): DoubleArray {
        if (ch <= 2) return DoubleArray(ch) { 1.0 }
        val sp = speakers(ch, m)
        val hasSide = sp.any { it == 9 || it == 10 }
        return DoubleArray(ch) { i ->
            when (sp[i]) {
                LFE -> 0.0
                9, 10 -> 1.41
                4, 5 -> if (hasSide) 1.0 else 1.41
                else -> 1.0
            }
        }
    }

    /** 混成單聲道用的權重：不含 LFE，總和為 1。 */
    fun mixWeights(ch: Int, m: Int): FloatArray {
        val sp = speakers(ch, m)
        val w = FloatArray(ch) { if (ch > 1 && sp[it] == LFE) 0f else 1f }
        val s = w.sum().takeIf { it > 0 } ?: 1f
        for (i in w.indices) w[i] /= s
        return w
    }

    /** 播放用的立體聲縮混係數 [聲道][0=左,1=右]，已正規化避免削波。 */
    fun stereoDownmix(ch: Int, m: Int): Array<FloatArray> {
        val sp = speakers(ch, m)
        val k = 0.7071f
        val mtx = Array(ch) { i ->
            when (sp[i]) {
                0, 6 -> floatArrayOf(1f, 0f)
                1, 7 -> floatArrayOf(0f, 1f)
                2 -> floatArrayOf(k, k)
                LFE -> floatArrayOf(0f, 0f)
                4, 9 -> floatArrayOf(k, 0f)
                5, 10 -> floatArrayOf(0f, k)
                else -> floatArrayOf(0.5f, 0.5f)
            }
        }
        val sumL = mtx.sumOf { it[0].toDouble() }
        val sumR = mtx.sumOf { it[1].toDouble() }
        val norm = maxOf(1.0, sumL, sumR).toFloat()
        for (r in mtx) {
            r[0] /= norm
            r[1] /= norm
        }
        return mtx
    }
}
