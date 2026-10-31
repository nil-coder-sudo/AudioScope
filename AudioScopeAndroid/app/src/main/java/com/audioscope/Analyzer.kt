package com.audioscope

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ════════════════════════════════════════════════════════════════════
//  資料結構（純 Kotlin，可在電腦上測試）
// ════════════════════════════════════════════════════════════════════
class DecodedAudio(val sampleRate: Int, val channels: Array<FloatArray>) {
    val frames: Int get() = channels[0].size
    val channelCount: Int get() = channels.size
    val duration: Double get() = frames.toDouble() / sampleRate
}

class FileMeta(
    val name: String,
    val sizeBytes: Long,
    val mime: String,
    val codecName: String,
    val lossless: Boolean,
    val bitrate: Int,
    val sourceBits: Int?,
    val decodedBits: Int,
    val tags: List<Pair<String, String>>,
)

class Row(val key: String, val value: String) {
    val warn: Boolean get() = value.startsWith("⚠")
}

class Section(val title: String) {
    val rows = ArrayList<Row>()
    fun add(k: String, v: String) {
        rows.add(Row(k, v))
    }
}

/** 完整頻譜圖：dB 以 0.75 dB 量化成 1 byte（−170 … +21 dB）。 */
class SpecData(
    val q: ByteArray, val frames: Int, val bins: Int,
    val binHz: Double, val hopSec: Double, val t0: Double, val duration: Double,
) {
    fun db(frame: Int, bin: Int): Float = (q[frame * bins + bin].toInt() and 0xff) * 0.75f - 170f

    companion object {
        fun quant(db: Double): Byte = ((db + 170) / 0.75).roundToInt().coerceIn(0, 255).toByte()
        fun dequant(v: Int): Float = v * 0.75f - 170f
    }
}

class AnalysisResult(
    val sections: List<Section>,
    val integrated: Double,
    val lra: Double,
    val truePeakDb: Double,
    val spec: SpecData,
)

class Envelope(val mn: FloatArray, val mx: FloatArray, val rms: FloatArray)

// ════════════════════════════════════════════════════════════════════
//  離線完整分析
// ════════════════════════════════════════════════════════════════════
object Analyzer {
    private val BANDS = arrayOf(
        Triple("超低頻 20–60 Hz", 20.0, 60.0), Triple("低頻 60–250 Hz", 60.0, 250.0),
        Triple("中低頻 250–500 Hz", 250.0, 500.0), Triple("中頻 500 Hz–2 kHz", 500.0, 2000.0),
        Triple("中高頻 2–4 kHz", 2000.0, 4000.0), Triple("臨場感 4–6 kHz", 4000.0, 6000.0),
        Triple("空氣感 6–20 kHz", 6000.0, 20000.0),
    )
    private val KS_MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val KS_MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    fun channelNames(ch: Int): Array<String> = when (ch) {
        1 -> arrayOf("Mono")
        2 -> arrayOf("L", "R")
        else -> Array(ch) { "Ch${it + 1}" }
    }

    fun envelope(a: DecodedAudio, buckets: Int = 1200): Envelope {
        val n = a.frames
        val b = max(1, min(buckets, n))
        val mn = FloatArray(b)
        val mx = FloatArray(b)
        val rms = FloatArray(b)
        val chs = a.channels
        val nch = chs.size
        for (i in 0 until b) {
            val s = (i.toLong() * n / b).toInt()
            val e = max(s + 1, ((i + 1).toLong() * n / b).toInt()).coerceAtMost(n)
            var lo = 1f
            var hi = -1f
            var acc = 0.0
            for (j in s until e) {
                var v = 0f
                for (c in chs) v += c[j]
                v /= nch
                if (v < lo) lo = v
                if (v > hi) hi = v
                acc += v.toDouble() * v
            }
            mn[i] = if (lo > hi) 0f else lo
            mx[i] = if (lo > hi) 0f else hi
            rms[i] = sqrt(acc / max(1, e - s)).toFloat()
        }
        return Envelope(mn, mx, rms)
    }

    fun quick(a: DecodedAudio, m: FileMeta): Section {
        val s = Section("檔案資訊")
        val sr = a.sampleRate
        s.add("檔名", m.name)
        if (m.sizeBytes > 0) s.add("檔案大小", Fmt.size(m.sizeBytes))
        s.add("編碼", m.codecName)
        s.add("MIME 類型", m.mime)
        s.add("壓縮類型", if (m.lossless) "無損 / 未壓縮" else "有損壓縮")
        s.add("位元深度", m.sourceBits?.let { "$it bit" } ?: if (m.lossless) "未知" else "—（有損格式沒有固定位元深度）")
        s.add("解碼輸出", "${m.decodedBits} bit")
        s.add("取樣率", "${Fmt.i(sr.toLong())} Hz")
        s.add("Nyquist 頻率", "${Fmt.i((sr / 2).toLong())} Hz")
        s.add("聲道數", "${a.channelCount}（${channelNames(a.channelCount).joinToString(" / ")}）")
        s.add("總樣本數", "${Fmt.i(a.frames.toLong())} / 聲道")
        s.add("長度", "${Fmt.time(a.duration)}（${Fmt.n(a.duration, 3)} 秒）")
        if (m.bitrate > 0) s.add("編碼位元率", "${Fmt.n(m.bitrate / 1000.0, 0)} kbps")
        if (m.sizeBytes > 0 && a.duration > 0) s.add("平均位元率", "${Fmt.n(m.sizeBytes * 8 / a.duration / 1000, 1)} kbps")
        val bits = m.sourceBits
        if (bits != null && m.lossless) s.add("未壓縮位元率", "${Fmt.n(sr.toDouble() * a.channelCount * bits / 1000, 1)} kbps")
        return s
    }

    fun run(a: DecodedAudio, meta: FileMeta, progress: (Int, String) -> Unit): AnalysisResult {
        val sr = a.sampleRate
        val chs = a.channels
        val nch = chs.size
        val n = a.frames
        val dur = a.duration
        val names = channelNames(nch)
        val secs = ArrayList<Section>()
        secs.add(quick(a, meta))
        fun mono(i: Int): Float {
            var s = 0f
            for (c in chs) s += c[i]
            return s / nch
        }

        // ── 電平 ──
        progress(2, "計算電平")
        val peaks = DoubleArray(nch)
        val rmss = DoubleArray(nch)
        val dcs = DoubleArray(nch)
        val clips = IntArray(nch)
        val clipRuns = IntArray(nch)
        for (c in 0 until nch) {
            var pk = 0f
            var ss = 0.0
            var sum = 0.0
            var cl = 0
            var run = 0
            var rr = 0
            for (v in chs[c]) {
                val av = abs(v)
                if (av > pk) pk = av
                ss += v.toDouble() * v
                sum += v
                if (av >= 0.99997f) {
                    cl++
                    run++
                } else {
                    if (run >= 3) rr++
                    run = 0
                }
            }
            if (run >= 3) rr++
            peaks[c] = pk.toDouble()
            rmss[c] = sqrt(ss / max(n, 1))
            dcs[c] = sum / max(n, 1)
            clips[c] = cl
            clipRuns[c] = rr
        }
        val tps = DoubleArray(nch)
        for (c in 0 until nch) {
            progress(6 + 14 * c / nch, "計算真峰值（4× 超取樣）")
            tps[c] = TruePeak.of(chs[c]).toDouble()
        }
        val lv = Section("電平")
        for (c in 0 until nch) {
            val nm = names[c]
            lv.add("$nm 樣本峰值", Fmt.db(ampDb(peaks[c])))
            lv.add("$nm 真峰值", Fmt.db(ampDb(tps[c]), "dBTP"))
            lv.add("$nm RMS", Fmt.db(ampDb(rmss[c])))
            lv.add("$nm 峰值因數", if (rmss[c] > 0) "${Fmt.n(ampDb(peaks[c]) - ampDb(rmss[c]), 2)} dB" else "—")
            lv.add("$nm DC 偏移", "${Fmt.signed(dcs[c] * 100, 4)} %")
            lv.add("$nm 削波樣本", "${Fmt.i(clips[c].toLong())}（連續 ≥3 樣本段：${clipRuns[c]}）")
        }
        secs.add(lv)

        // ── 響度 ──
        progress(22, "計算響度（BS.1770 / EBU R128）")
        val (seg, h) = Loudness.segmentEnergies(chs, sr)
        val zM = Loudness.blockZ(seg, h, 4)
        val zS = Loudness.blockZ(seg, h, 30)
        val integ = Loudness.integrated(zM)
        val lra = Loudness.range(zS)
        val mMax = zM.maxOrNull()?.let { Loudness.lufs(it) } ?: Double.NEGATIVE_INFINITY
        val sMax = zS.maxOrNull()?.let { Loudness.lufs(it) } ?: Double.NEGATIVE_INFINITY
        val tpMax = ampDb(tps.maxOrNull() ?: 0.0)
        val ld = Section("響度")
        ld.add("整合響度 I", Fmt.db(integ, "LUFS"))
        ld.add("響度範圍 LRA", if (lra.isFinite()) "${Fmt.n(lra, 1)} LU" else "—")
        ld.add("最大瞬時 M", Fmt.db(mMax, "LUFS"))
        ld.add("最大短期 S", Fmt.db(sMax, "LUFS"))
        ld.add("最大真峰值", Fmt.db(tpMax, "dBTP"))
        ld.add("PLR（峰值對響度比）", if (integ.isFinite()) "${Fmt.n(tpMax - integ, 1)} dB" else "—")
        if (integ.isFinite()) {
            for ((name, target) in listOf("YouTube / Spotify" to -14.0, "Apple Music" to -16.0, "EBU R128 廣播" to -23.0)) {
                val d = integ - target
                ld.add(
                    "對 $name（${Fmt.n(target, 0).replace('-', '−')} LUFS）",
                    "${Fmt.signed(d)} LU → " + if (d > 0) "會被調低 ${Fmt.n(d, 1)} dB" else "不會被調低",
                )
            }
        }
        secs.add(ld)

        // ── 動態 ──
        progress(35, "分析動態與靜音")
        val bl = max(1, (0.05 * sr).toInt())
        val nb = n / bl
        val blkDb = DoubleArray(nb)
        for (b in 0 until nb) {
            var s = 0.0
            for (i in b * bl until (b + 1) * bl) {
                val v = mono(i)
                s += v.toDouble() * v
            }
            blkDb[b] = if (s > 0) ampDb(sqrt(s / bl)) else -200.0
        }
        val active = blkDb.filter { it > -150 }.sorted()
        var first = -1
        var last = -1
        var zc = 0L
        var prevNeg = false
        for (i in 0 until n) {
            val v = mono(i)
            if (abs(v) > 0.001f) {
                if (first < 0) first = i
                last = i
            }
            val neg = v < 0
            if (i > 0 && neg != prevNeg) zc++
            prevNeg = neg
        }
        val dy = Section("動態")
        if (active.isNotEmpty()) {
            val p5 = percentile(active, 5.0)
            val p95 = percentile(active, 95.0)
            dy.add("雜訊底（5% 百分位）", Fmt.db(p5))
            dy.add("響亮段（95% 百分位）", Fmt.db(p95))
            dy.add("動態範圍（95%−5%）", "${Fmt.n(p95 - p5, 1)} dB")
        }
        dy.add("數位靜音比例", "${Fmt.n(if (nb > 0) blkDb.count { it <= -150 } * 100.0 / nb else 0.0, 1)} %")
        dy.add("開頭靜音（< −60 dBFS）", if (first >= 0) "${Fmt.n(first.toDouble() / sr, 3)} 秒" else "整段靜音")
        dy.add("結尾靜音（< −60 dBFS）", if (last >= 0) "${Fmt.n((n - 1 - last).toDouble() / sr, 3)} 秒" else "—")
        if (dur > 0) dy.add("過零率", "${Fmt.n(zc / dur, 0)} 次/秒")
        secs.add(dy)

        // ── 立體聲 ──
        if (nch >= 2) {
            progress(40, "分析立體聲")
            val l = chs[0]
            val r = chs[1]
            var ll = 0.0
            var rrr = 0.0
            var lr = 0.0
            var maxDiff = 0f
            for (i in 0 until n) {
                val x = l[i].toDouble()
                val y = r[i].toDouble()
                ll += x * x
                rrr += y * y
                lr += x * y
                val d = abs(l[i] - r[i])
                if (d > maxDiff) maxDiff = d
            }
            val corr = if (ll > 0 && rrr > 0) lr / sqrt(ll * rrr) else Double.NaN
            val mm = (ll + 2 * lr + rrr) / 4
            val ss = (ll - 2 * lr + rrr) / 4
            val st = Section("立體聲")
            st.add("相位相關性", if (corr.isFinite()) Fmt.signed(corr, 3) else "—")
            st.add(
                "單聲道相容性",
                if (!corr.isFinite()) "—" else if (corr > 0.3) "良好" else if (corr > 0) "注意" else "⚠ 可能有反相問題",
            )
            st.add("左右平衡", if (ll > 0 && rrr > 0) "${Fmt.signed(10 * log10(ll / rrr), 2)} dB（正＝偏左）" else "—")
            st.add("側 / 中 能量比", if (ss > 1e-20 && mm > 0) "${Fmt.n(10 * log10(ss / mm), 1)} dB" else "−∞ dB")
            st.add(
                "立體聲寬度",
                if (maxDiff < 1e-6f) "左右完全相同（假立體聲 / 實為單聲道）"
                else "${Fmt.n(min(100.0, 100 * sqrt(ss / (mm + ss))), 0)} %",
            )
            secs.add(st)
        }

        // ── 頻譜（STFT，同時產生完整頻譜圖與音級分布）──
        progress(45, "計算頻譜")
        val nfft = if (sr > 96000) 8192 else 4096
        val hop = max(nfft / 4, ceil(max(1, n - nfft) / 3000.0).toInt())
        val nFrames = if (n <= nfft) 1 else 1 + (n - nfft) / hop
        val fft = Fft(nfft)
        val win = Windows.make("Hann", nfft)
        val norm = 2.0 / win.sum()
        val bins = nfft / 2 + 1
        val binHz = sr.toDouble() / nfft
        val buf = FloatArray(nfft)
        val mag = FloatArray(bins)
        val pw = DoubleArray(bins)
        val q = ByteArray(nFrames * bins)
        val pc = IntArray(bins) { k ->
            val f = k * binHz
            if (f in 55.0..5000.0) Math.floorMod((69 + 12 * log2(f / 440.0)).roundToInt(), 12) else -1
        }
        val chroma = DoubleArray(12)
        val cf = DoubleArray(12)
        for (fr in 0 until nFrames) {
            val s0 = fr * hop
            for (i in 0 until nfft) {
                val j = s0 + i
                buf[i] = if (j < n) mono(j) * win[i] else 0f
            }
            fft.magnitude(buf, mag)
            val base = fr * bins
            for (k in 0 until bins) {
                val mv = mag[k].toDouble()
                pw[k] += mv * mv
                q[base + k] = SpecData.quant(20 * log10(mv * norm + 1e-10))
            }
            cf.fill(0.0)
            for (k in 0 until bins) if (pc[k] >= 0) cf[pc[k]] += mag[k].toDouble()
            val cm = cf.maxOrNull() ?: 0.0
            if (cm > 0) for (i in 0 until 12) chroma[i] += cf[i] / cm
            if (fr % 64 == 0) progress(45 + 25 * fr / nFrames, "計算頻譜")
        }
        for (k in 0 until bins) pw[k] /= nFrames
        val spec = SpecData(q, nFrames, bins, binHz, hop.toDouble() / sr, nfft / 2.0 / sr, dur)

        val freqs = DoubleArray(bins) { it * binHz }
        var pTot = 0.0
        var pSum = 0.0
        var fp = 0.0
        for (k in 0 until bins) {
            pSum += pw[k]
            fp += freqs[k] * pw[k]
            if (k > 0) pTot += pw[k]
        }
        val cen = if (pSum > 0) fp / pSum else 0.0
        var bwAcc = 0.0
        for (k in 0 until bins) bwAcc += (freqs[k] - cen) * (freqs[k] - cen) * pw[k]
        val bw = if (pSum > 0) sqrt(bwAcc / pSum) else 0.0
        var cum = 0.0
        var roll = 0.0
        for (k in 0 until bins) {
            cum += pw[k]
            if (cum >= 0.85 * pSum) {
                roll = freqs[k]
                break
            }
        }
        var lnSum = 0.0
        for (k in 1 until bins) lnSum += ln(pw[k] + 1e-30)
        val flat = exp(lnSum / (bins - 1)) / ((pTot / (bins - 1)) + 1e-30)
        var domK = 1
        for (k in 1 until bins) if (freqs[k] >= 20 && (freqs[domK] < 20 || pw[k] > pw[domK])) domK = k
        val dom = freqs[domK]
        val (dNote, dCents) = Notes.of(dom)
        val avgDb = DoubleArray(bins) { 10 * log10(pw[it] + 1e-30) }
        val sm = DoubleArray(bins) { k ->
            var s = 0.0
            var c = 0
            for (j in max(0, k - 4)..min(bins - 1, k + 4)) {
                s += avgDb[j]
                c++
            }
            s / c
        }
        var ref = Double.NEGATIVE_INFINITY
        for (k in 0 until bins) if (freqs[k] in 100.0..5000.0 && sm[k] > ref) ref = sm[k]
        if (!ref.isFinite()) ref = sm.maxOrNull() ?: 0.0
        var cutK = -1
        for (k in 0 until bins) if (sm[k] > ref - 80) cutK = k
        val cutoff = if (cutK >= 0) freqs[cutK] else 0.0
        val nyq = sr / 2.0
        val lossy: String = if (cutoff < 0.95 * nyq) {
            fun meanIn(lo: Double, hi: Double): Double {
                var s = 0.0
                var c = 0
                for (k in 0 until bins) if (freqs[k] > lo && freqs[k] < hi) {
                    s += sm[k]
                    c++
                }
                return if (c > 0) s / c else Double.NaN
            }
            val drop = meanIn(cutoff - 1200, cutoff - 200) - meanIn(cutoff + 200, cutoff + 1200)
            if (drop.isFinite() && drop > 20 && cutoff > 10000)
                "⚠ 疑似有損轉檔 / 低通（${Fmt.n(cutoff / 1000, 1)} kHz 處陡降 ${Fmt.n(drop, 0)} dB）"
            else "頻寬自然衰減至約 ${Fmt.n(cutoff / 1000, 1)} kHz"
        } else "高頻延伸到 Nyquist，未見有損壓縮截止"

        val sp = Section("頻譜特徵")
        sp.add("主頻率（平均能量最大）", "${Fmt.n(dom, 1)} Hz（$dNote ${Fmt.signed(dCents, 0)}¢）")
        sp.add("頻譜質心", "${Fmt.n(cen, 0)} Hz")
        sp.add("頻譜頻寬", "${Fmt.n(bw, 0)} Hz")
        sp.add("85% 能量滾降點", "${Fmt.n(roll, 0)} Hz")
        sp.add("頻譜平坦度", "${Fmt.n(10 * log10(flat), 1)} dB（0＝白噪音，越低越有音調）")
        sp.add("高頻截止（−80 dB）", "${Fmt.n(cutoff, 0)} Hz")
        for ((name, lo, hi) in BANDS) {
            var s = 0.0
            for (k in 0 until bins) if (freqs[k] >= lo && freqs[k] < min(hi, nyq)) s += pw[k]
            sp.add(name, if (pTot > 0) "${Fmt.n(s / pTot * 100, 1)} %" else "—")
        }
        secs.add(sp)

        // ── 音樂：調性、速度 ──
        progress(72, "估計調性與速度")
        val mu = Section("音樂（估計值）")
        val cmax = chroma.maxOrNull() ?: 0.0
        if (cmax > 0) {
            val scores = ArrayList<Pair<Double, String>>()
            for (k in 0 until 12) {
                scores.add(pearson(chroma, rotate(KS_MAJOR, k)) to "${Notes.NAMES[k]} 大調")
                scores.add(pearson(chroma, rotate(KS_MINOR, k)) to "${Notes.NAMES[k]} 小調")
            }
            scores.sortByDescending { it.first }
            mu.add("調性", "${scores[0].second}（相關 ${Fmt.n(scores[0].first, 2)}）")
            mu.add("次可能調性", "${scores[1].second}（相關 ${Fmt.n(scores[1].first, 2)}）")
            mu.add("音級分布", (0 until 12).joinToString("  ") { "${Notes.NAMES[it]}:${Fmt.n(chroma[it] / cmax, 2)}" })
        } else mu.add("調性", "無法估計")
        val bpm = estimateBpm(chs, sr, n) { p -> progress(75 + p * 15 / 100, "估計速度") }
        mu.add("速度", bpm?.let { "${Fmt.n(it.first, 1)} BPM（信心 ${Fmt.n(it.second, 2)}）" } ?: "無法估計")
        secs.add(mu)

        // ── 品質檢測 ──
        progress(92, "品質檢測")
        val qs = Section("品質檢測")
        qs.add("有損壓縮檢測", lossy)
        val sb = meta.sourceBits
        if (sb != null && sb in 16..24 && meta.lossless && meta.decodedBits >= sb) {
            var or = 0L
            val total = n.toLong() * nch
            val stride = max(1L, total / 2_000_000L)
            var idx = 0L
            val scale = (1L shl (sb - 1)).toDouble()
            while (idx < total) {
                val v = chs[(idx % nch).toInt()][(idx / nch).toInt()]
                or = or or abs((v * scale).roundToLong())
                idx += stride
            }
            if (or != 0L) {
                val eff = sb - java.lang.Long.numberOfTrailingZeros(or)
                qs.add("有效位元深度", "$eff bit" + if (eff < sb) "（⚠ 補零放大，實際內容較低）" else "")
            }
        }
        qs.add("削波", if (clipRuns.sum() > 0) "⚠ 有連續削波" else "未偵測到連續削波")
        qs.add("真峰值超過 −1 dBTP", if (tpMax > -1) "⚠ 是（轉碼後可能失真）" else "否")
        qs.add("DC 偏移", if (dcs.maxOf { abs(it) } > 0.005) "⚠ 偏高" else "正常")
        secs.add(qs)

        if (meta.tags.isNotEmpty()) {
            val tg = Section("中繼資料 / 標籤")
            for ((k, v) in meta.tags) tg.add(k, v)
            secs.add(tg)
        }
        progress(100, "完成")
        return AnalysisResult(secs, integ, lra, tpMax, spec)
    }

    private fun Double.roundToLong(): Long = Math.round(this)

    private fun rotate(a: DoubleArray, k: Int) = DoubleArray(12) { a[Math.floorMod(it - k, 12)] }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average()
        val mb = b.average()
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in a.indices) {
            num += (a[i] - ma) * (b[i] - mb)
            da += (a[i] - ma) * (a[i] - ma)
            db += (b[i] - mb) * (b[i] - mb)
        }
        return if (da > 0 && db > 0) num / sqrt(da * db) else 0.0
    }

    /** 頻譜通量起音包絡 + 自相關，偏好 120 BPM 附近。回傳 (BPM, 信心)。 */
    fun estimateBpm(chs: Array<FloatArray>, sr: Int, n: Int, progress: (Int) -> Unit): Pair<Double, Double>? {
        val nch = chs.size
        val d = max(1, (sr / 11025.0).roundToInt())
        val fs2 = sr.toDouble() / d
        val len2 = min(n.toLong(), 240L * sr).toInt() / d
        if (len2 < 4096) return null
        val y = FloatArray(len2) { j ->
            var s = 0f
            for (i in j * d until j * d + d) for (c in chs) s += c[i]
            s / (d * nch)
        }
        val nf = 1024
        val hop = 128
        val fft = Fft(nf)
        val w = Windows.make("Hann", nf)
        val m = FloatArray(nf / 2 + 1)
        val prev = FloatArray(nf / 2 + 1)
        val b = FloatArray(nf)
        val nFrames = 1 + (len2 - nf) / hop
        val onset = DoubleArray(nFrames)
        for (f in 0 until nFrames) {
            val s0 = f * hop
            for (i in 0 until nf) b[i] = y[s0 + i] * w[i]
            fft.magnitude(b, m)
            var flux = 0.0
            for (k in m.indices) {
                val cur = ln(1 + 100 * m[k])
                if (f > 0 && cur > prev[k]) flux += (cur - prev[k]).toDouble()
                prev[k] = cur
            }
            onset[f] = flux
            if (f % 2000 == 0) progress(50 * f / nFrames)
        }
        val fps = fs2 / hop
        val k = max(1, fps.toInt())
        val half = k / 2
        val cs = DoubleArray(nFrames + 1)
        for (i in 0 until nFrames) cs[i + 1] = cs[i] + onset[i]
        val o = DoubleArray(nFrames) { i ->
            val lo = max(0, i - half)
            val hi = min(nFrames, i - half + k)
            max(0.0, onset[i] - (cs[hi] - cs[lo]) / k)
        }
        val mean = o.average()
        for (i in o.indices) o[i] -= mean
        val maxLag = ceil(60 * fps / 50).toInt() + 2
        if (nFrames < maxLag * 3) return null
        val ac = DoubleArray(maxLag + 1)
        for (lag in 0..maxLag) {
            var s = 0.0
            for (i in 0 until nFrames - lag) s += o[i] * o[i + lag]
            ac[lag] = s
        }
        progress(90)
        if (ac[0] <= 0) return null
        var best = 0.0
        var bestBpm = 0.0
        var bestLag = 0.0
        var bpm = 50.0
        while (bpm <= 220.0) {
            val lag = 60 * fps / bpm
            val i0 = lag.toInt()
            if (i0 + 1 <= maxLag) {
                val v = ac[i0] + (ac[i0 + 1] - ac[i0]) * (lag - i0)
                val wgt = exp(-0.5 * (log2(bpm / 120) / 0.9).let { it * it })
                if (v * wgt > best) {
                    best = v * wgt
                    bestBpm = bpm
                    bestLag = lag
                }
            }
            bpm += 0.1
        }
        if (bestBpm == 0.0) return null
        return bestBpm to max(0.0, ac[bestLag.roundToInt().coerceIn(0, maxLag)] / ac[0])
    }
}
