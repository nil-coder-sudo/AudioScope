package com.audioscope

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.max
import kotlin.math.min

/**
 * 整首歌的頻譜圖。
 * 兩指縮放（水平、垂直各自）、單指拖曳平移、點一下跳到該時間、點兩下還原。
 */
class SpectrogramView(ctx: Context) : PlotView(ctx) {
    var onSeek: ((Double) -> Unit)? = null
    var onReadout: ((String) -> Unit)? = null
    var lo = -110f
        private set
    var hi = -10f
        private set
    var logScale = false
        private set
    private var lutName = "inferno"

    private var spec: SpecData? = null
    private var mappedQ: ByteArray? = null // 每個像素的量化 dB（依頻率刻度重新映射）
    private var bmp: Bitmap? = null
    private var pixels = IntArray(0)
    private var bw = 0
    private var bh = 0
    private var position = 0.0
    private var building = 0
    private val main = Handler(Looper.getMainLooper())

    // 可視範圍（0..1）；y：0＝最低頻，1＝最高頻
    private var vx0 = 0f
    private var vx1 = 1f
    private var vy0 = 0f
    private var vy1 = 1f

    private val bmpPaint = Paint()
    private val head = Paint().apply { color = Color.WHITE; strokeWidth = ctx.dp(1.5f) }
    private val src = Rect()
    private val dst = RectF()
    private var hint = "開啟音檔後，這裡會顯示整首歌的頻譜圖"

    override fun leftMargin() = context.dp(40f)

    fun setHint(h: String) {
        hint = h
        invalidate()
    }

    fun setSpec(s: SpecData?) {
        spec = s
        mappedQ = null
        bmp = null
        vx0 = 0f; vx1 = 1f; vy0 = 0f; vy1 = 1f
        rebuild(true)
    }

    fun setPosition(p: Double) {
        position = p
        invalidate()
    }

    fun setLevels(lo: Float, hi: Float) {
        this.lo = lo
        this.hi = max(hi, lo + 5f)
        rebuild(false)
    }

    fun setLog(log: Boolean) {
        if (log == logScale) return
        logScale = log
        mappedQ = null
        rebuild(true)
    }

    fun setColormap(name: String) {
        lutName = name
        rebuild(false)
    }

    /** 依直方圖取 20% 與 99.8% 百分位當作顯示範圍，回傳 (lo, hi)。 */
    fun autoContrast(): Pair<Float, Float>? {
        val s = spec ?: return null
        val hist = LongArray(256)
        val step = max(1, s.q.size / 2_000_000)
        var i = 0
        var cnt = 0L
        while (i < s.q.size) {
            hist[s.q[i].toInt() and 0xff]++
            cnt++
            i += step
        }
        fun pct(p: Double): Float {
            val target = (cnt * p).toLong()
            var acc = 0L
            for (k in 0 until 256) {
                acc += hist[k]
                if (acc >= target) return SpecData.dequant(k)
            }
            return SpecData.dequant(255)
        }
        val a = pct(0.20)
        val b = pct(0.998)
        setLevels(a, max(b, a + 10))
        return lo to hi
    }

    private fun rebuild(remap: Boolean) {
        val s = spec
        if (s == null) {
            invalidate()
            return
        }
        val my = ++building
        val log = logScale
        val l = lo
        val h = hi
        val lut = Colormaps.lut(lutName)
        val oldMapped = if (remap) null else mappedQ
        Thread {
            val w = s.frames
            val hh = if (log) 1024 else min(s.bins, 1024)
            val mapped = oldMapped ?: ByteArray(w * hh).also { out ->
                val fMax = s.bins.minus(1) * s.binHz
                val mp = FreqMapper(s.binHz, s.bins, hh, if (log) 20.0 else 0.0, fMax, log)
                val col = FloatArray(s.bins)
                val res = FloatArray(hh)
                for (f in 0 until w) {
                    val base = f * s.bins
                    for (k in 0 until s.bins) col[k] = (s.q[base + k].toInt() and 0xff).toFloat()
                    mp.map(col, res)
                    for (r in 0 until hh) out[(hh - 1 - r) * w + f] = res[r].toInt().coerceIn(0, 255).toByte()
                }
            }
            // 量化值 → 顏色的對照表
            val qLut = IntArray(256) { q ->
                val db = SpecData.dequant(q)
                lut[((db - l) / (h - l) * 255f).toInt().coerceIn(0, 255)]
            }
            val px = IntArray(w * hh)
            for (i in px.indices) px[i] = qLut[mapped[i].toInt() and 0xff]
            main.post {
                if (my != building) return@post
                mappedQ = mapped
                if (bmp == null || bw != w || bh != hh) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888)
                    bw = w
                    bh = hh
                }
                pixels = px
                bmp?.setPixels(px, 0, w, 0, 0, w, hh)
                invalidate()
            }
        }.start()
    }

    private fun fMax(): Double = spec?.let { (it.bins - 1) * it.binHz } ?: 24000.0
    private fun fMin(): Double = if (logScale) 20.0 else 0.0
    private fun dur(): Double = spec?.duration ?: 0.0

    override fun onDraw(c: Canvas) {
        c.drawColor(Pal.PANEL)
        val b = bmp
        val s = spec
        if (b == null || s == null) {
            c.drawText(hint, width / 2f, height / 2f, hintPaint)
            return
        }
        src.set(
            (vx0 * bw).toInt(), ((1 - vy1) * bh).toInt(),
            max((vx0 * bw).toInt() + 1, (vx1 * bw).toInt()), max(((1 - vy1) * bh).toInt() + 1, ((1 - vy0) * bh).toInt()),
        )
        dst.set(plot)
        bmpPaint.isFilterBitmap = src.width() > plot.width()
        c.drawBitmap(b, src, dst, bmpPaint)

        // 時間軸
        val d = dur()
        val t0 = vx0 * d
        val t1 = vx1 * d
        val tStep = niceStep((t1 - t0) / 5)
        axisText.textAlign = Paint.Align.CENTER
        var t = kotlin.math.ceil(t0 / tStep) * tStep
        while (t <= t1 + 1e-9) {
            val x = plot.left + ((t - t0) / (t1 - t0) * plot.width()).toFloat()
            c.drawText(if (tStep >= 1) Fmt.n(t, 0) else Fmt.n(t, 2), x, plot.bottom + context.dp(12f), axisText)
            t += tStep
        }
        // 頻率軸
        axisText.textAlign = Paint.Align.RIGHT
        val fmin = fMin()
        val fmax = fMax()
        val ticks = if (logScale) FreqMapper.ticks(true, fmin, fmax)
        else {
            val f0 = FreqMapper.freqAt(vy0.toDouble(), fmin, fmax, false)
            val f1 = FreqMapper.freqAt(vy1.toDouble(), fmin, fmax, false)
            FreqMapper.ticks(false, f0, f1)
        }
        var lastY = 1e9f
        for (f in ticks) {
            val fy = FreqMapper.pos(f, fmin, fmax, logScale)
            if (fy < vy0 || fy > vy1) continue
            val y = plot.bottom - (fy - vy0) / (vy1 - vy0) * plot.height()
            if (lastY - y < context.dp(14f)) continue
            c.drawText(Fmt.hz(f), plot.left - context.dp(4f), y + context.dp(3f), axisText)
            c.drawLine(plot.left - context.dp(2f), y, plot.left, y, gridPaint)
            lastY = y
        }
        // 播放位置
        if (d > 0) {
            val fx = (position / d).toFloat()
            if (fx in vx0..vx1) {
                val x = plot.left + (fx - vx0) / (vx1 - vx0) * plot.width()
                c.drawLine(x, plot.top, x, plot.bottom, head)
            }
        }
    }

    private fun readout(x: Float, y: Float) {
        val s = spec ?: return
        val fx = vx0 + (x - plot.left) / plot.width() * (vx1 - vx0)
        val fy = vy0 + (plot.bottom - y) / plot.height() * (vy1 - vy0)
        val t = fx * dur()
        val f = FreqMapper.freqAt(fy.toDouble().coerceIn(0.0, 1.0), fMin(), fMax(), logScale)
        val fr = ((t - s.t0) / s.hopSec).toInt().coerceIn(0, s.frames - 1)
        val bin = (f / s.binHz).toInt().coerceIn(0, s.bins - 1)
        val (note, _) = Notes.of(f)
        onReadout?.invoke("${Fmt.time(t)}  ${Fmt.n(f, 0)} Hz $note  ${Fmt.n(s.db(fr, bin).toDouble(), 1)} dB")
    }

    private fun clampView() {
        val w = (vx1 - vx0).coerceIn(0.01f, 1f)
        vx0 = vx0.coerceIn(0f, 1f - w)
        vx1 = vx0 + w
        val h = (vy1 - vy0).coerceIn(0.01f, 1f)
        vy0 = vy0.coerceIn(0f, 1f - h)
        vy1 = vy0 + h
    }

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val sx = if (d.previousSpanX > 10) d.currentSpanX / d.previousSpanX else 1f
            val sy = if (d.previousSpanY > 10) d.currentSpanY / d.previousSpanY else 1f
            val fx = vx0 + (d.focusX - plot.left) / plot.width() * (vx1 - vx0)
            val fy = vy0 + (plot.bottom - d.focusY) / plot.height() * (vy1 - vy0)
            val nw = ((vx1 - vx0) / sx).coerceIn(0.01f, 1f)
            val nh = ((vy1 - vy0) / sy).coerceIn(0.01f, 1f)
            vx0 = fx - (fx - vx0) * nw / (vx1 - vx0)
            vx1 = vx0 + nw
            vy0 = fy - (fy - vy0) * nh / (vy1 - vy0)
            vy1 = vy0 + nh
            clampView()
            invalidate()
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val d = dur()
            if (d > 0 && plot.contains(e.x, e.y)) {
                onSeek?.invoke(((vx0 + (e.x - plot.left) / plot.width() * (vx1 - vx0)) * d).toDouble())
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            vx0 = 0f; vx1 = 1f; vy0 = 0f; vy1 = 1f
            invalidate()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (e2.pointerCount > 1) return false
            val w = vx1 - vx0
            val h = vy1 - vy0
            vx0 += dx / plot.width() * w
            vx1 = vx0 + w
            vy0 -= dy / plot.height() * h
            vy1 = vy0 + h
            clampView()
            readout(e2.x.coerceIn(plot.left, plot.right), e2.y.coerceIn(plot.top, plot.bottom))
            invalidate()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (spec == null) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) gestures.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_DOWN && plot.contains(e.x, e.y)) readout(e.x, e.y)
        return true
    }
}
