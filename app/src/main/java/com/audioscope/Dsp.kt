package com.audioscope

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ════════════════════════════════════════════════════════════════════
//  音名
// ════════════════════════════════════════════════════════════════════
object Notes {
    val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** 回傳 (音名, 音分偏差)。 */
    fun of(f: Double): Pair<String, Double> {
        if (!(f > 0) || f.isInfinite()) return "—" to 0.0
        val m = 69 + 12 * log2(f / 440.0)
        val n = m.roundToInt()
        return "${NAMES[Math.floorMod(n, 12)]}${Math.floorDiv(n, 12) - 1}" to (m - n) * 100
    }
}

// ════════════════════════════════════════════════════════════════════
//  窗函數
// ════════════════════════════════════════════════════════════════════
object Windows {
    val names = arrayOf("Hann", "Blackman-Harris", "Flat-top", "Hamming", "矩形")

    fun make(name: String, n: Int): FloatArray = FloatArray(n) { i ->
        val x = 2.0 * PI * i / n
        when (name) {
            "Hann" -> 0.5 - 0.5 * cos(x)
            "Blackman-Harris" -> 0.35875 - 0.48829 * cos(x) + 0.14128 * cos(2 * x) - 0.01168 * cos(3 * x)
            "Flat-top" -> 0.21557895 - 0.41663158 * cos(x) + 0.277263158 * cos(2 * x) -
                0.083578947 * cos(3 * x) + 0.006947368 * cos(4 * x)
            "Hamming" -> 0.54 - 0.46 * cos(x)
            else -> 1.0
        }.toFloat()
    }
}

// ════════════════════════════════════════════════════════════════════
//  FFT（radix-2，原地運算，可重複使用）
// ════════════════════════════════════════════════════════════════════
class Fft(val n: Int) {
    private val cosT = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { sin(2 * PI * it / n).toFloat() }
    private val rev: IntArray
    private val re = FloatArray(n)
    private val im = FloatArray(n)

    init {
        require(n >= 2 && n and (n - 1) == 0) { "FFT 長度必須是 2 的次方" }
        val bits = Integer.numberOfTrailingZeros(n)
        rev = IntArray(n) { Integer.reverse(it) ushr (32 - bits) }
    }

    /** input：長度 n（已乘窗）；out：長度 n/2+1 的幅度。 */
    fun magnitude(input: FloatArray, out: FloatArray) {
        for (i in 0 until n) {
            re[rev[i]] = input[i]
            im[rev[i]] = 0f
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val l = j + half
                    val c = cosT[k]
                    val s = sinT[k]
                    val tr = re[l] * c + im[l] * s
                    val ti = im[l] * c - re[l] * s
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                i += size
            }
            size *= 2
        }
        for (i in 0..n / 2) out[i] = sqrt(re[i] * re[i] + im[i] * im[i])
    }
}

// ════════════════════════════════════════════════════════════════════
//  ITU-R BS.1770-4 / EBU R128 響度
// ════════════════════════════════════════════════════════════════════
class Biquad(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun p(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}

object Loudness {
    /** K 加權：高架濾波 + RLB 高通，任意取樣率。 */
    fun kFilters(fs: Double): Array<Biquad> {
        val g = 4.0
        var q = 1 / sqrt(2.0)
        var fc = 1500.0
        val a = 10.0.pow(g / 40)
        var w0 = 2 * PI * fc / fs
        var al = sin(w0) / (2 * q)
        var cw = cos(w0)
        val sa = sqrt(a)
        val a0 = (a + 1) - (a - 1) * cw + 2 * sa * al
        val shelf = Biquad(
            a * ((a + 1) + (a - 1) * cw + 2 * sa * al) / a0,
            -2 * a * ((a - 1) + (a + 1) * cw) / a0,
            a * ((a + 1) + (a - 1) * cw - 2 * sa * al) / a0,
            2 * ((a - 1) - (a + 1) * cw) / a0,
            ((a + 1) - (a - 1) * cw - 2 * sa * al) / a0,
        )
        q = 0.5
        fc = 38.0
        w0 = 2 * PI * fc / fs
        al = sin(w0) / (2 * q)
        cw = cos(w0)
        val b0 = 1 + al
        val hp = Biquad((1 + cw) / 2 / b0, -(1 + cw) / b0, (1 + cw) / 2 / b0, -2 * cw / b0, (1 - al) / b0)
        return arrayOf(shelf, hp)
    }

    fun lufs(z: Double): Double = if (z > 0) -0.691 + 10 * log10(z) else Double.NEGATIVE_INFINITY

    /** 每 100 ms 一段的 K 加權能量總和（各聲道相加）。回傳 (能量, 每段樣本數)。 */
    fun segmentEnergies(chs: Array<FloatArray>, fs: Int): Pair<DoubleArray, Int> {
        val h = max(1, (0.1 * fs).roundToInt())
        val n = chs[0].size
        val nseg = n / h
        val e = DoubleArray(nseg)
        for (ch in chs) {
            val f = kFilters(fs.toDouble())
            var s = 0
            var acc = 0.0
            var cnt = 0
            for (i in 0 until nseg * h) {
                val y = f[1].p(f[0].p(ch[i].toDouble()))
                acc += y * y
                if (++cnt == h) {
                    e[s++] += acc
                    acc = 0.0
                    cnt = 0
                }
            }
        }
        return e to h
    }

    fun blockZ(seg: DoubleArray, h: Int, nb: Int): DoubleArray {
        if (seg.size < nb) return DoubleArray(0)
        val cs = DoubleArray(seg.size + 1)
        for (i in seg.indices) cs[i + 1] = cs[i] + seg[i]
        return DoubleArray(seg.size - nb + 1) { (cs[it + nb] - cs[it]) / (nb.toDouble() * h) }
    }

    fun integrated(z: DoubleArray): Double = integrated(z.asList())

    fun integrated(z: List<Double>): Double {
        val g = z.filter { lufs(it) > -70 }
        if (g.isEmpty()) return Double.NEGATIVE_INFINITY
        val rel = lufs(g.average()) - 10
        val g2 = g.filter { lufs(it) > rel }
        return if (g2.isEmpty()) Double.NEGATIVE_INFINITY else lufs(g2.average())
    }

    fun range(z: DoubleArray): Double {
        val g = z.filter { lufs(it) > -70 }
        if (g.isEmpty()) return Double.NaN
        val rel = lufs(g.average()) - 20
        val v = g.map { lufs(it) }.filter { it > rel }.sorted()
        if (v.size < 2) return 0.0
        return percentile(v, 95.0) - percentile(v, 10.0)
    }

    /** 即時用：濾 blk 最後 len 個樣本，回傳 [zM(最後 mLen), zS(全部)]。 */
    fun liveZ(blk: Array<FloatArray>, len: Int, fs: Int, mLen: Int): DoubleArray {
        var sS = 0.0
        var sM = 0.0
        for (ch in blk) {
            val f = kFilters(fs.toDouble())
            val start = ch.size - len
            for (i in 0 until len) {
                val y = f[1].p(f[0].p(ch[start + i].toDouble()))
                val e = y * y
                sS += e
                if (i >= len - mLen) sM += e
            }
        }
        return doubleArrayOf(sM / mLen, sS / len)
    }
}

fun percentile(sorted: List<Double>, p: Double): Double {
    if (sorted.isEmpty()) return Double.NaN
    val pos = p / 100 * (sorted.size - 1)
    val lo = floor(pos).toInt()
    val hi = min(lo + 1, sorted.size - 1)
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
}

// ════════════════════════════════════════════════════════════════════
//  真峰值：4 倍超取樣（多相 FIR），只在響亮樣本附近計算以節省時間
// ════════════════════════════════════════════════════════════════════
object TruePeak {
    private const val P = 4
    private const val Q = 16
    private val h: Array<FloatArray> = run {
        val l = P * Q
        val full = DoubleArray(l) { i ->
            val t = (i - (l - 1) / 2.0) / P
            val s = if (abs(t) < 1e-12) 1.0 else sin(PI * t) / (PI * t)
            val w = 0.42 - 0.5 * cos(2 * PI * i / (l - 1)) + 0.08 * cos(4 * PI * i / (l - 1))
            s * w
        }
        val scale = P / full.sum()
        Array(P) { p -> FloatArray(Q) { k -> (full[k * P + p] * scale).toFloat() } }
    }

    fun of(x: FloatArray): Float {
        var peak = 0f
        for (v in x) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        if (peak == 0f) return 0f
        val thr = peak * 0.5f
        var best = peak
        // 內插點落在 x[m-8] 與 x[m-7] 之間（群延遲 7.875 樣本）
        for (m in Q - 1 until x.size) {
            if (abs(x[m - 8]) < thr && abs(x[m - 7]) < thr) continue
            for (p in 0 until P) {
                val hp = h[p]
                var acc = 0f
                for (k in 0 until Q) acc += hp[k] * x[m - k]
                val a = abs(acc)
                if (a > best) best = a
            }
        }
        return best
    }
}

// ════════════════════════════════════════════════════════════════════
//  頻率軸映射：FFT bin → 顯示用的等寬欄位（線性或對數），高頻保留峰值
// ════════════════════════════════════════════════════════════════════
class FreqMapper(binHz: Double, nBins: Int, val w: Int, val fMin: Double, val fMax: Double, val log: Boolean) {
    val centers = DoubleArray(w)
    private val i0 = IntArray(w)
    private val fr = FloatArray(w)
    private val lo = IntArray(w)
    private val hi = IntArray(w)

    init {
        val edges = DoubleArray(w + 1) { i ->
            if (log) fMin * (fMax / fMin).pow(i.toDouble() / w) else fMin + (fMax - fMin) * i / w
        }
        for (c in 0 until w) {
            val a = edges[c]
            val b = edges[c + 1]
            centers[c] = if (log) sqrt(a * b) else (a + b) / 2
            val l = ceil(a / binHz).toInt().coerceIn(0, nBins - 1)
            val hh = floor(b / binHz).toInt().coerceIn(0, nBins - 1)
            lo[c] = l
            hi[c] = if (hh > l) hh else -1
            val ci = centers[c] / binHz
            val ii = floor(ci).toInt().coerceIn(0, nBins - 2)
            i0[c] = ii
            fr[c] = (ci - ii).toFloat().coerceIn(0f, 1f)
        }
    }

    fun map(src: FloatArray, dst: FloatArray) {
        for (c in 0 until w) {
            val h = hi[c]
            if (h >= 0) {
                var m = src[lo[c]]
                for (k in lo[c] + 1..h) if (src[k] > m) m = src[k]
                dst[c] = m
            } else {
                val i = i0[c]
                dst[c] = src[i] + (src[i + 1] - src[i]) * fr[c]
            }
        }
    }

    /** 頻率 → 軸上的 0..1 位置。 */
    fun pos(f: Double): Float =
        (if (log) ln(max(f, 1e-9) / fMin) / ln(fMax / fMin) else (f - fMin) / (fMax - fMin)).toFloat()

    fun freqAt(frac: Double): Double = if (log) fMin * (fMax / fMin).pow(frac) else fMin + (fMax - fMin) * frac

    companion object {
        fun pos(f: Double, fMin: Double, fMax: Double, log: Boolean): Float =
            (if (log) ln(max(f, 1e-9) / fMin) / ln(fMax / fMin) else (f - fMin) / (fMax - fMin)).toFloat()

        fun freqAt(frac: Double, fMin: Double, fMax: Double, log: Boolean): Double =
            if (log) fMin * (fMax / fMin).pow(frac) else fMin + (fMax - fMin) * frac

        /** 刻度：對數為 20/50/100/200/500/1k…；線性取好讀的間隔。 */
        fun ticks(log: Boolean, fMin: Double, fMax: Double): List<Double> {
            val out = ArrayList<Double>()
            if (log) {
                var dec = 1.0
                while (dec <= fMax) {
                    for (m in intArrayOf(1, 2, 5)) {
                        val f = m * dec
                        if (f >= fMin && f <= fMax) out.add(f)
                    }
                    dec *= 10
                }
            } else {
                val step = niceStep((fMax - fMin) / 6)
                var f = ceil(fMin / step) * step
                while (f <= fMax + 1e-9) {
                    out.add(f)
                    f += step
                }
            }
            return out
        }
    }
}

fun niceStep(raw: Double): Double {
    if (raw <= 0) return 1.0
    val mag = 10.0.pow(floor(log10(raw)))
    for (m in doubleArrayOf(1.0, 2.0, 5.0, 10.0)) if (m * mag >= raw) return m * mag
    return 10 * mag
}
