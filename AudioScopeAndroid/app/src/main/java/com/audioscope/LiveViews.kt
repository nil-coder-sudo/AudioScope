package com.audioscope

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 共用：畫框、文字筆刷。 */
abstract class PlotView(ctx: Context) : View(ctx) {
    protected val plot = RectF()
    protected val gridPaint = Paint().apply { color = Pal.GRID; strokeWidth = ctx.dp(1f) }
    protected val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.AXIS
        textSize = ctx.dp(9.5f)
        typeface = Typeface.MONOSPACE
    }
    protected val bubbleBg = Paint().apply { color = Color.argb(215, 11, 14, 20) }
    protected val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CFE8FF")
        textSize = ctx.dp(11f)
        typeface = Typeface.MONOSPACE
    }
    protected val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.MUTED
        textSize = ctx.dp(13f)
        textAlign = Paint.Align.CENTER
    }

    open fun leftMargin() = context.dp(34f)
    open fun bottomMargin() = context.dp(16f)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        plot.set(leftMargin(), context.dp(4f), w - context.dp(6f), h - bottomMargin())
    }

    protected fun bubble(c: Canvas, text: String, x: Float, y: Float) {
        val pad = context.dp(4f)
        val tw = bubbleText.measureText(text)
        val bx = min(x, width - tw - 2 * pad)
        c.drawRect(bx, y, bx + tw + 2 * pad, y + bubbleText.textSize + 2 * pad, bubbleBg)
        c.drawText(text, bx + pad, y + pad + bubbleText.textSize * 0.85f, bubbleText)
    }

    protected fun drawFreqAxis(c: Canvas, log: Boolean, fMin: Double, fMax: Double, grid: Boolean) {
        axisText.textAlign = Paint.Align.CENTER
        var lastX = -1e9f
        for (f in FreqMapper.ticks(log, fMin, fMax)) {
            val x = plot.left + FreqMapper.pos(f, fMin, fMax, log) * plot.width()
            if (grid) c.drawLine(x, plot.top, x, plot.bottom, gridPaint)
            val label = Fmt.hz(f)
            val tw = axisText.measureText(label)
            if (x - tw / 2 > lastX + context.dp(4f) && x + tw / 2 <= width) {
                c.drawText(label, x, plot.bottom + context.dp(12f), axisText)
                lastX = x + tw / 2
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
//  即時頻譜
// ════════════════════════════════════════════════════════════════════
class SpectrumView(ctx: Context) : PlotView(ctx) {
    var dbLo = -120f
    var dbHi = 0f
    var logScale = true
    var smoothing = 0.5f
    var holdEnabled = true
    var sampleRate = 48000
    var hint = "開啟音檔，或按「麥克風」開始分析"

    private var mapper: FreqMapper? = null
    private var mapKey = ""
    private var disp = FloatArray(0)
    private var avg: FloatArray? = null
    private var hold: FloatArray? = null
    private var peakF = -1.0
    private var peakDb = 0f
    private var peakLabel = ""
    private var touchX = -1f

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Pal.ACCENT
        strokeWidth = ctx.dp(1.4f)
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Pal.HOLD
        strokeWidth = ctx.dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(ctx.dp(4f), ctx.dp(3f)), 0f)
    }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.PEAK }
    private val peakText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD1DC")
        textSize = ctx.dp(11f)
        textAlign = Paint.Align.CENTER
    }
    private val cursorPaint = Paint().apply { color = Color.argb(80, 255, 255, 255); strokeWidth = ctx.dp(1f) }
    private val path = Path()
    private val fillPath = Path()
    private val holdPath = Path()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fillPaint.shader = LinearGradient(
            0f, plot.top, 0f, plot.bottom,
            Color.argb(90, 76, 194, 255), Color.argb(8, 76, 194, 255), Shader.TileMode.CLAMP,
        )
        mapKey = ""
    }

    fun reset() {
        avg = null
        hold = null
        peakF = -1.0
        invalidate()
    }

    fun resetHold() {
        hold = null
    }

    fun submit(sdb: FloatArray, binHz: Double, advanced: Boolean, dt: Float) {
        val w = max(2, plot.width().toInt())
        val fMin = if (logScale) 20.0 else 0.0
        val fMax = sampleRate / 2.0
        val key = "$w|$logScale|$binHz|${sdb.size}|$fMax"
        if (key != mapKey) {
            mapper = FreqMapper(binHz, sdb.size, min(w, 1600), fMin, fMax, logScale)
            mapKey = key
            disp = FloatArray(min(w, 1600))
            avg = null
            hold = null
        }
        val mp = mapper ?: return
        mp.map(sdb, disp)
        val a = avg
        if (a == null) avg = disp.copyOf() else {
            val s = smoothing
            for (i in a.indices) a[i] = s * a[i] + (1 - s) * disp[i]
        }
        if (holdEnabled) {
            val h = hold
            if (h == null) hold = disp.copyOf() else {
                val dec = if (advanced) 3f * dt else 0f
                for (i in h.indices) h[i] = max(h[i] - dec, disp[i])
            }
        } else hold = null
        invalidate()
    }

    fun setPeak(f: Double, db: Float, label: String) {
        peakF = f
        peakDb = db
        peakLabel = label
    }

    fun clearPeak() {
        peakF = -1.0
    }

    private fun y(db: Float): Float = plot.bottom - (db - dbLo) / (dbHi - dbLo) * plot.height()

    override fun onDraw(c: Canvas) {
        c.drawColor(Pal.PANEL)
        val fMin = if (logScale) 20.0 else 0.0
        val fMax = sampleRate / 2.0
        // dB 格線
        axisText.textAlign = Paint.Align.RIGHT
        val step = if (plot.height() < context.dp(140f)) 20 else 10
        var d = (dbHi / step).toInt() * step
        while (d >= dbLo) {
            val yy = y(d.toFloat())
            c.drawLine(plot.left, yy, plot.right, yy, gridPaint)
            c.drawText("$d", plot.left - context.dp(4f), yy + context.dp(3f), axisText)
            d -= step
        }
        drawFreqAxis(c, logScale, fMin, fMax, true)

        val a = avg
        val mp = mapper
        if (a == null || mp == null) {
            c.drawText(hint, width / 2f, height / 2f, hintPaint)
            return
        }
        val n = a.size
        val dx = plot.width() / n
        path.reset()
        fillPath.reset()
        fillPath.moveTo(plot.left, plot.bottom)
        for (i in 0 until n) {
            val x = plot.left + (i + 0.5f) * dx
            val yy = y(max(a[i], dbLo)).coerceAtLeast(plot.top)
            if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
            fillPath.lineTo(x, yy)
        }
        fillPath.lineTo(plot.right, plot.bottom)
        fillPath.close()
        c.save()
        c.clipRect(plot)
        c.drawPath(fillPath, fillPaint)
        c.drawPath(path, curvePaint)
        val h = hold
        if (h != null) {
            holdPath.reset()
            for (i in 0 until n) {
                val x = plot.left + (i + 0.5f) * dx
                val yy = y(max(h[i], dbLo))
                if (i == 0) holdPath.moveTo(x, yy) else holdPath.lineTo(x, yy)
            }
            c.drawPath(holdPath, holdPaint)
        }
        c.restore()

        if (peakF > 0) {
            val px = plot.left + mp.pos(peakF) * plot.width()
            val py = y(peakDb).coerceIn(plot.top, plot.bottom)
            c.drawCircle(px, py, context.dp(3.5f), peakPaint)
            val tw = peakText.measureText(peakLabel) / 2
            val tx = px.coerceIn(plot.left + tw, plot.right - tw)
            c.drawText(peakLabel, tx, max(py - context.dp(7f), plot.top + context.dp(12f)), peakText)
        }
        if (touchX >= plot.left) {
            c.drawLine(touchX, plot.top, touchX, plot.bottom, cursorPaint)
            val frac = ((touchX - plot.left) / plot.width()).toDouble().coerceIn(0.0, 1.0)
            val f = mp.freqAt(frac)
            val idx = (frac * n).toInt().coerceIn(0, n - 1)
            val (note, cents) = Notes.of(f)
            bubble(
                c, "${Fmt.n(f, 1)} Hz  ${Fmt.n(a[idx].toDouble(), 1)} dB  $note ${Fmt.signed(cents, 0)}¢",
                plot.left + context.dp(4f), plot.top + context.dp(2f),
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touchX = e.x.coerceIn(plot.left, plot.right)
            }
            else -> touchX = -1f
        }
        invalidate()
        return true
    }
}

// ════════════════════════════════════════════════════════════════════
//  即時瀑布圖（環形點陣圖，只更新新的那一列）
// ════════════════════════════════════════════════════════════════════
class WaterfallView(ctx: Context) : PlotView(ctx) {
    var dbLo = -120f
    var dbHi = 0f
    var logScale = true
    var sampleRate = 48000
    var historySec = 10f
    var lut: IntArray = Colormaps.lut("inferno")

    private var bmp: Bitmap? = null
    private var bw = 0
    private var bh = 0
    private var head = 0
    private var mapper: FreqMapper? = null
    private var mapKey = ""
    private var row = FloatArray(0)
    private var rowPx = IntArray(0)
    private var lastT = -1L
    private var touch = -1f to -1f
    private val bmpPaint = Paint().apply { isFilterBitmap = true }
    private val src = Rect()
    private val dst = RectF()

    fun reset() {
        mapKey = ""
        lastT = -1L
        invalidate()
    }

    private fun ensure(binHz: Double, nBins: Int) {
        val w = max(2, min(plot.width().toInt(), 1024))
        val h = max(2, min(plot.height().toInt(), 600))
        val fMin = if (logScale) 20.0 else 0.0
        val fMax = sampleRate / 2.0
        val key = "$w|$h|$logScale|$binHz|$nBins|$fMax"
        if (key == mapKey && bmp != null) return
        mapKey = key
        mapper = FreqMapper(binHz, nBins, w, fMin, fMax, logScale)
        bw = w
        bh = h
        row = FloatArray(w)
        rowPx = IntArray(w)
        bmp?.recycle()
        bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(lut[0]) }
        head = 0
        lastT = -1L
    }

    fun submit(sdb: FloatArray, binHz: Double, advanced: Boolean) {
        if (plot.width() < 2) return
        ensure(binHz, sdb.size)
        val now = System.nanoTime()
        if (!advanced || lastT < 0) {
            lastT = now
            return
        }
        val rowDt = (historySec / bh * 1e9).toLong().coerceAtLeast(1)
        var rows = ((now - lastT) / rowDt).toInt()
        if (rows < 1) return
        lastT += rows * rowDt
        rows = min(rows, bh)
        val mp = mapper ?: return
        mp.map(sdb, row)
        val span = dbHi - dbLo
        for (i in 0 until bw) rowPx[i] = lut[((row[i] - dbLo) / span * 255f).toInt().coerceIn(0, 255)]
        val b = bmp ?: return
        repeat(rows) {
            head = (head - 1 + bh) % bh
            b.setPixels(rowPx, 0, bw, 0, head, bw, 1)
        }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Pal.PANEL)
        val b = bmp
        if (b != null) {
            // 最新的一列在最上面
            val topRows = bh - head
            val split = plot.top + plot.height() * topRows / bh
            src.set(0, head, bw, bh)
            dst.set(plot.left, plot.top, plot.right, split)
            c.drawBitmap(b, src, dst, bmpPaint)
            if (head > 0) {
                src.set(0, 0, bw, head)
                dst.set(plot.left, split, plot.right, plot.bottom)
                c.drawBitmap(b, src, dst, bmpPaint)
            }
        }
        axisText.textAlign = Paint.Align.RIGHT
        val step = niceStep(historySec / 5.0)
        var t = 0.0
        while (t <= historySec + 1e-6) {
            val yy = plot.top + (t / historySec * plot.height()).toFloat()
            c.drawText("−${Fmt.n(t, if (step < 1) 1 else 0)}", plot.left - context.dp(3f), yy + context.dp(3f), axisText)
            t += step
        }
        drawFreqAxis(c, logScale, if (logScale) 20.0 else 0.0, sampleRate / 2.0, false)
        val (tx, ty) = touch
        val mp = mapper
        if (tx >= plot.left && mp != null) {
            val f = mp.freqAt(((tx - plot.left) / plot.width()).toDouble().coerceIn(0.0, 1.0))
            val sec = (ty - plot.top) / plot.height() * historySec
            val (note, _) = Notes.of(f)
            bubble(c, "${Fmt.n(f, 1)} Hz  $note  −${Fmt.n(sec.toDouble(), 2)} s", plot.left + context.dp(4f), plot.top + context.dp(2f))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        touch = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                e.x.coerceIn(plot.left, plot.right) to e.y.coerceIn(plot.top, plot.bottom)
            }
            else -> -1f to -1f
        }
        invalidate()
        return true
    }
}

// ════════════════════════════════════════════════════════════════════
//  電平表（橫向）：RMS 實心、Peak 半透明、白線＝峰值保持
// ════════════════════════════════════════════════════════════════════
class MeterView(ctx: Context) : View(ctx) {
    private val minDb = -60f
    private var labels = arrayOf("L", "R")
    private var peak = floatArrayOf(-120f, -120f)
    private var rms = floatArrayOf(-120f, -120f)
    private var hold = floatArrayOf(-120f, -120f)
    private val bg = Paint().apply { color = Color.parseColor("#141A26") }
    private val bar = Paint()
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.AXIS
        textSize = ctx.dp(8.5f)
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }
    private val lab = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.TEXT
        textSize = ctx.dp(11f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val valP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ctx.dp(11f)
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
    }
    private val holdP = Paint().apply { strokeWidth = ctx.dp(2f) }
    private var left = 0f
    private var right = 0f

    fun set(labels: Array<String>, peak: FloatArray, rms: FloatArray, hold: FloatArray) {
        this.labels = labels
        this.peak = peak
        this.rms = rms
        this.hold = hold
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        left = context.dp(22f)
        right = w - context.dp(50f)
        bar.shader = LinearGradient(
            left, 0f, right, 0f,
            intArrayOf(Pal.GREEN_DARK, Pal.GREEN, Pal.YELLOW, Pal.ORANGE, Pal.RED),
            floatArrayOf(0f, 0.7f, 0.85f, 0.93f, 1f), Shader.TileMode.CLAMP,
        )
    }

    private fun x(db: Float) = left + ((db.coerceIn(minDb, 0f) - minDb) / -minDb) * (right - left)

    override fun onDraw(c: Canvas) {
        c.drawColor(Pal.PANEL)
        val top = context.dp(12f)
        for (d in intArrayOf(0, -6, -12, -20, -30, -40, -60)) c.drawText("$d", x(d.toFloat()), context.dp(9f), tick)
        val n = max(1, labels.size)
        val gap = context.dp(5f)
        val bhh = (height - top - context.dp(3f) - gap * (n - 1)) / n
        for (i in 0 until n) {
            val y0 = top + i * (bhh + gap)
            c.drawRect(left, y0, right, y0 + bhh, bg)
            bar.alpha = 110
            c.drawRect(left, y0, x(peak[i]), y0 + bhh, bar)
            bar.alpha = 255
            c.drawRect(left, y0 + bhh * 0.2f, x(rms[i]), y0 + bhh * 0.8f, bar)
            val clip = hold[i] > -0.1f
            holdP.color = if (clip) Pal.RED else Pal.WHITE
            val hx = x(hold[i])
            c.drawLine(hx, y0, hx, y0 + bhh, holdP)
            c.drawText(labels[i], context.dp(4f), y0 + bhh / 2 + lab.textSize * 0.35f, lab)
            valP.color = if (clip) Pal.RED else Pal.WHITE
            c.drawText(
                if (hold[i] > -119f) Fmt.n(hold[i].toDouble(), 1).replace('-', '−') else "−∞",
                width - context.dp(4f), y0 + bhh / 2 + valP.textSize * 0.35f, valP,
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════
//  立體聲：向量示波器（自動增益）＋相位相關性
// ════════════════════════════════════════════════════════════════════
class StereoView(ctx: Context) : View(ctx) {
    private val pts = FloatArray(4096)
    private var npts = 0
    private var corr = 1f
    private var gain = 1f
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 60, 75, 100)
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(1f)
    }
    private val trace = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 76, 194, 255)
        strokeWidth = ctx.dp(1f)
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.MUTED
        textSize = ctx.dp(9f)
        textAlign = Paint.Align.CENTER
    }
    private val bg = Paint().apply { color = Color.parseColor("#141A26") }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val valP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = ctx.dp(12f)
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }

    fun set(l: FloatArray, r: FloatArray, off: Int, n: Int, c: Float) {
        val m = min(n, pts.size / 4)
        var mx = 0f
        val k = 0.70710677f
        for (i in 0 until m) {
            val x = (r[off + i] - l[off + i]) * k
            val y = (l[off + i] + r[off + i]) * k
            mx = max(mx, max(abs(x), abs(y)))
        }
        val target = if (mx > 1e-4f) min(8f, 0.9f / mx) else gain
        gain = if (target < gain) target else gain * 0.97f + target * 0.03f
        npts = 0
        var px = 0f
        var py = 0f
        for (i in 0 until m) {
            val x = (r[off + i] - l[off + i]) * k * gain
            val y = (l[off + i] + r[off + i]) * k * gain
            if (i > 0) {
                pts[npts++] = px
                pts[npts++] = py
                pts[npts++] = x
                pts[npts++] = y
            }
            px = x
            py = y
        }
        corr = if (c.isFinite()) c else 0f
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Pal.PANEL)
        val size = min(height.toFloat(), width * 0.55f)
        val cx = size / 2
        val cy = height / 2f
        val rad = size / 2 - context.dp(6f)
        c.drawCircle(cx, cy, rad, guide)
        c.drawLine(cx, cy - rad, cx, cy + rad, guide)
        c.drawLine(cx - rad, cy, cx + rad, cy, guide)
        val d = rad * 0.7071f
        c.drawLine(cx - d, cy - d, cx + d, cy + d, guide)
        c.drawLine(cx - d, cy + d, cx + d, cy - d, guide)
        c.drawText("M", cx, cy - rad + context.dp(9f), txt)
        c.drawText("L", cx - d - context.dp(4f), cy - d, txt)
        c.drawText("R", cx + d + context.dp(4f), cy - d, txt)
        c.save()
        c.translate(cx, cy)
        c.scale(rad, -rad)
        trace.strokeWidth = context.dp(1f) / rad
        c.drawLines(pts, 0, npts, trace)
        c.restore()
        // 相關性
        val l = size + context.dp(8f)
        val r = width - context.dp(8f)
        val ym = height / 2f
        c.drawText("相位相關性", (l + r) / 2, ym - context.dp(16f), txt)
        val track = RectF(l, ym - context.dp(5f), r, ym + context.dp(5f))
        c.drawRoundRect(track, context.dp(5f), context.dp(5f), bg)
        val mid = (l + r) / 2
        val v = corr.coerceIn(-1f, 1f)
        val xv = mid + v * (r - l) / 2
        fill.color = if (v < 0) Pal.RED else if (v < 0.3f) Pal.YELLOW else Pal.GREEN
        c.drawRect(min(mid, xv), track.top + context.dp(2f), max(mid, xv), track.bottom - context.dp(2f), fill)
        c.drawCircle(xv, ym, context.dp(4.5f), dot)
        c.drawText("−1", l + context.dp(6f), ym + context.dp(16f), txt)
        c.drawText("0", mid, ym + context.dp(16f), txt)
        c.drawText("+1", r - context.dp(6f), ym + context.dp(16f), txt)
        c.drawText(Fmt.signed(v.toDouble(), 2), (l + r) / 2, ym + context.dp(32f), valP)
    }
}

// ════════════════════════════════════════════════════════════════════
//  波形總覽：點或拖放後放開即跳轉
// ════════════════════════════════════════════════════════════════════
class WaveformView(ctx: Context) : View(ctx) {
    var onSeek: ((Double) -> Unit)? = null
    private var env: Envelope? = null
    private var duration = 0.0
    private var position = 0.0
    private var dragX = -1f
    private val wave = Paint().apply { color = Color.argb(150, 76, 194, 255); strokeWidth = 1f }
    private val rmsP = Paint().apply { color = Color.argb(200, 191, 233, 255); strokeWidth = 1f }
    private val head = Paint().apply { color = Pal.PEAK; strokeWidth = ctx.dp(2f) }
    private val ghost = Paint().apply { color = Color.argb(150, 255, 255, 255); strokeWidth = ctx.dp(1f) }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.TEXT
        textSize = ctx.dp(10f)
        typeface = Typeface.MONOSPACE
    }
    private var lines = FloatArray(0)
    private var rmsLines = FloatArray(0)

    fun setEnvelope(e: Envelope, dur: Double) {
        env = e
        duration = dur
        rebuild()
    }

    fun setPosition(p: Double) {
        position = p
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = rebuild()

    private fun rebuild() {
        val e = env ?: return
        if (width == 0) return
        val n = e.mx.size
        val mid = height / 2f
        val amp = height / 2f - 2
        lines = FloatArray(n * 4)
        rmsLines = FloatArray(n * 4)
        for (i in 0 until n) {
            val x = (i + 0.5f) / n * width
            lines[i * 4] = x
            lines[i * 4 + 1] = mid - e.mx[i] * amp
            lines[i * 4 + 2] = x
            lines[i * 4 + 3] = mid - e.mn[i] * amp
            rmsLines[i * 4] = x
            rmsLines[i * 4 + 1] = mid - e.rms[i] * amp
            rmsLines[i * 4 + 2] = x
            rmsLines[i * 4 + 3] = mid + e.rms[i] * amp
        }
        wave.strokeWidth = max(1f, width.toFloat() / n)
        rmsP.strokeWidth = wave.strokeWidth
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Pal.PANEL)
        c.drawLines(lines, wave)
        c.drawLines(rmsLines, rmsP)
        if (duration > 0) {
            val x = (position / duration * width).toFloat()
            c.drawLine(x, 0f, x, height.toFloat(), head)
        }
        if (dragX >= 0) {
            c.drawLine(dragX, 0f, dragX, height.toFloat(), ghost)
            c.drawText(Fmt.time(dragX / width * duration), min(dragX + 6, width - context.dp(70f)), txt.textSize + 2, txt)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (duration <= 0) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragX = e.x.coerceIn(0f, width.toFloat())
            }
            MotionEvent.ACTION_UP -> {
                val t = e.x.coerceIn(0f, width.toFloat()) / width * duration
                dragX = -1f
                onSeek?.invoke(t)
            }
            else -> dragX = -1f
        }
        invalidate()
        return true
    }
}

