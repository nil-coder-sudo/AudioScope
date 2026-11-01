package com.audioscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

/** 純 Kotlin 的 DSP / 分析核心測試（不需要 Android 裝置）。 */
class DspTest {
    private fun sine(f: Double, amp: Double, sr: Int, sec: Double, phase: Double = 0.0) =
        FloatArray((sr * sec).toInt()) { (amp * sin(2 * PI * f * it / sr + phase)).toFloat() }

    @Test
    fun fftPeakLandsOnTheRightBin() {
        val n = 4096
        val sr = 48000
        val x = sine(1000.0, 1.0, sr, n.toDouble() / sr)
        val w = Windows.make("Hann", n)
        val buf = FloatArray(n) { x[it] * w[it] }
        val mag = FloatArray(n / 2 + 1)
        Fft(n).magnitude(buf, mag)
        val k = mag.indices.maxByOrNull { mag[it] }!!
        assertEquals(1000.0, k * sr.toDouble() / n, sr.toDouble() / n)
        // Hann 窗的振幅正規化後應接近 0 dBFS
        assertEquals(0.0, 20 * log10(mag[k] * 2.0 / w.sum()), 1.5)
    }

    @Test
    fun noteNames() {
        assertEquals("A4", Notes.of(440.0).first)
        assertEquals(0.0, Notes.of(440.0).second, 1e-9)
        assertEquals("C4", Notes.of(261.63).first)
        assertEquals("A0", Notes.of(27.5).first)
    }

    @Test
    fun stereoSineLoudnessMatchesReference() {
        // 參考值由 pyloudnorm 算出：997 Hz、峰值 0.1、雙聲道、48 kHz → −20.04 LUFS
        val sr = 48000
        val x = sine(997.0, 0.1, sr, 10.0)
        val (seg, h) = Loudness.segmentEnergies(arrayOf(x, x), sr, doubleArrayOf(1.0, 1.0))
        val i = Loudness.integrated(Loudness.blockZ(seg, h, 4))
        assertEquals(-20.04, i, 0.02)
    }

    @Test
    fun streamingMeterMatchesOffline() {
        val sr = 44100
        val n = sr * 20
        // 振幅慢慢起伏的訊號，讓門限真的有作用
        val l = FloatArray(n) { (0.3 * sin(2 * PI * 440 * it / sr) * (0.2 + 0.8 * abs(sin(PI * it / (sr * 7.0))))).toFloat() }
        val r = FloatArray(n) { (l[it] * 0.7f + 0.05f * sin(2 * PI * 3000 * it / sr).toFloat()) }
        val w = doubleArrayOf(1.0, 1.0)
        val (seg, h) = Loudness.segmentEnergies(arrayOf(l, r), sr, w)
        val offline = Loudness.integrated(Loudness.blockZ(seg, h, 4))
        val m = LoudnessMeter(sr, w)
        var pos = 0
        val rnd = java.util.Random(1)
        while (pos < n) {
            val k = minOf(n - pos, 200 + rnd.nextInt(3000)) // 模擬每格長短不一
            m.push(arrayOf(l, r), pos, k)
            pos += k
        }
        assertEquals(offline, m.integrated(), 0.01)
        val lastM = Loudness.lufs(Loudness.blockZ(seg, h, 4).last())
        assertEquals(lastM, m.momentary(), 0.01)
    }

    @Test
    fun surroundWeightsFollowBs1770() {
        val w51 = Layout.loudnessWeights(6, 0)
        assertEquals(listOf(1.0, 1.0, 1.0, 0.0, 1.41, 1.41), w51.toList())
        val w71 = Layout.loudnessWeights(8, 0) // L R C LFE BL BR SL SR
        assertEquals(listOf(1.0, 1.0, 1.0, 0.0, 1.0, 1.0, 1.41, 1.41), w71.toList())
        assertEquals(listOf("L", "R", "C", "LFE", "Ls", "Rs"), Layout.names(6, 0).toList())
    }

    @Test
    fun surroundChannelIsWeightedAndLfeIgnored() {
        val sr = 48000
        val x = sine(997.0, 0.1, sr, 10.0)
        val z = FloatArray(x.size)
        val lfe = sine(40.0, 0.5, sr, 10.0)
        val a = DecodedAudio(sr, arrayOf(z, z, z, lfe, x, z))
        val (seg, h) = Loudness.segmentEnergies(a.channels, sr, a.loudnessWeights)
        val i = Loudness.integrated(Loudness.blockZ(seg, h, 4))
        // 單一聲道權重 1.0 時為 −23.05 LUFS（pyloudnorm）；×1.41 → +1.49 dB
        assertEquals(-23.05 + 10 * log10(1.41), i, 0.05)
    }

    @Test
    fun truePeakFindsInterSamplePeak() {
        // fs/4 的正弦、相位 45°：每個樣本都只有 0.707，真正的波峰在樣本之間
        val sr = 48000
        val x = sine(sr / 4.0, 0.5, sr, 1.0, PI / 4)
        val samplePeak = x.maxOf { abs(it) }
        assertEquals(0.5 * 0.7071, samplePeak.toDouble(), 1e-3)
        assertEquals(0.5, TruePeak.of(x).toDouble(), 0.01)
    }

    @Test
    fun sideSpectrogramRevealsOutOfPhaseContent() {
        // 5 kHz 只存在於 Side（L 與 R 反相），混合聲道會把它完全抵消
        val sr = 44100
        val common = sine(300.0, 0.3, sr, 3.0)
        val hidden = sine(5000.0, 0.1, sr, 3.0)
        val l = FloatArray(common.size) { common[it] + hidden[it] }
        val r = FloatArray(common.size) { common[it] - hidden[it] }
        val a = DecodedAudio(sr, arrayOf(l, r))
        fun levelAt(mode: Int, f: Double): Float {
            val s = Analyzer.spectrogram(a, mode, {})
            val bin = (f / s.binHz).toInt()
            return (0 until s.frames).maxOf { fr -> (bin - 1..bin + 1).maxOf { s.db(fr, it) } }
        }
        assertTrue("混合聲道不該有 5 kHz", levelAt(0, 5000.0) < -100f)
        assertTrue("Side 聲道應看到 5 kHz", levelAt(3, 5000.0) > -30f)
        assertTrue("Side 聲道不該有 300 Hz", levelAt(3, 300.0) < -100f)
    }

    @Test
    fun tempoOfClickTrack() {
        val sr = 44100
        val n = sr * 30
        val x = FloatArray(n)
        var t = 0
        while (t < n) {
            for (i in 0 until 2000) if (t + i < n) x[t + i] = (0.8 * sin(2 * PI * 80 * i / sr) * Math.exp(-i / 300.0)).toFloat()
            t += sr / 2 // 每 0.5 秒一下 = 120 BPM
        }
        val bpm = Analyzer.estimateBpm(arrayOf(x), sr, n, floatArrayOf(1f)) {}
        assertTrue(bpm != null)
        assertEquals(120.0, bpm!!.first, 1.0)
    }

    @Test
    fun downmixNeverClips() {
        for (ch in 3..8) {
            val m = Layout.stereoDownmix(ch, 0)
            assertTrue(m.sumOf { it[0].toDouble() } <= 1.0 + 1e-6)
            assertTrue(m.sumOf { it[1].toDouble() } <= 1.0 + 1e-6)
        }
    }
}
