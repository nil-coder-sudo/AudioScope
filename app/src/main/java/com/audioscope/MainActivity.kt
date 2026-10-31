package com.audioscope

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : Activity() {
    private companion object {
        const val REQ_OPEN = 11
        const val REQ_MIC = 12
        val FFT_SIZES = arrayOf("1024", "2048", "4096", "8192", "16384", "32768")
        val CH_MODES = arrayOf("混合 (L+R)", "左 L", "右 R", "中 Mid", "側 Side")
        val LO_OPTS = arrayOf(-160, -140, -120, -100, -80, -60)
        val HI_OPTS = arrayOf(12, 6, 0, -12, -20, -30)
        val SMOOTH_OPTS = arrayOf(0f, 0.3f, 0.5f, 0.7f, 0.85f, 0.95f)
        val HIST_OPTS = arrayOf(3f, 5f, 10f, 20f, 30f, 60f)
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    // ── 介面 ──
    private lateinit var timeText: TextView
    private lateinit var statusText: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnMic: Button
    private lateinit var waveform: WaveformView
    private lateinit var spectrum: SpectrumView
    private lateinit var waterfall: WaterfallView
    private lateinit var meter: MeterView
    private lateinit var stereo: StereoView
    private val rd = HashMap<String, TextView>()
    private lateinit var tabs: Array<TextView>
    private lateinit var pages: Array<View>
    private lateinit var specView: SpectrogramView
    private lateinit var specRead: TextView
    private lateinit var specScaleBtn: Button
    private lateinit var seekLo: SeekBar
    private lateinit var seekHi: SeekBar
    private lateinit var loLabel: TextView
    private lateinit var hiLabel: TextView
    private lateinit var infoList: LinearLayout
    private lateinit var infoStatus: TextView

    // ── 狀態 ──
    private var audio: DecodedAudio? = null
    private var meta: FileMeta? = null
    private var player: Player? = null
    private var fileSource: FileSource? = null
    private var live: LiveInput? = null
    private var source: SignalSource? = null
    private var result: AnalysisResult? = null
    private var sections: List<Section> = emptyList()
    private var gen = 0
    private var running = false

    // ── 設定 ──
    private var fftSize = 8192
    private var winName = "Hann"
    private var logScale = true
    private var cmap = "inferno"
    private var chMode = 0
    private var smoothing = 0.5f
    private var dbLo = -120f
    private var dbHi = 0f
    private var history = 10f
    private var holdOn = true

    // ── DSP 暫存 ──
    private var fft = Fft(8192)
    private var win = Windows.make("Hann", 8192)
    private var winNorm = 1.0
    private var blk: Array<FloatArray> = arrayOf(FloatArray(1))
    private var xbuf = FloatArray(8192)
    private var mag = FloatArray(4097)
    private var sdb = FloatArray(4097)
    private var lastCur = Long.MIN_VALUE
    private var lastFrameNs = 0L
    private var frameNo = 0L
    private var mPeak = FloatArray(0)
    private var mHold = FloatArray(0)
    private var mRms = FloatArray(0)
    private var holdT = LongArray(0)
    private var corrS = 1f
    private var lufsM = Double.NEGATIVE_INFINITY
    private var lufsS = Double.NEGATIVE_INFINITY
    private val liveZ = ArrayList<Double>()
    private var lastZt = 0L
    private var peakFreq = -1.0
    private var peakNote = "—"

    // ═════════════════════════ 生命週期 ═════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Pal.BG
        window.navigationBarColor = Pal.BG
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        prefs = getSharedPreferences("audioscope", Context.MODE_PRIVATE)
        loadPrefs()
        setContentView(buildUi())
        applyDsp()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        running = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    override fun onPause() {
        super.onPause()
        running = false
        player?.pause()
        if (live != null) stopLive()
        updateButtons()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        live?.stop()
    }

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!running) return
            val dt = if (lastFrameNs == 0L) 1 / 60f else ((t - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.2f)
            lastFrameNs = t
            try {
                tick(dt)
            } catch (e: Exception) {
                statusText.text = "顯示錯誤：${e.message}"
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ═════════════════════════ 版面 ═════════════════════════
    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun tv(text: String, size: Float = 13f, color: Int = Pal.TEXT, bold: Boolean = false, mono: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = when {
                mono && bold -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                mono -> Typeface.MONOSPACE
                bold -> Typeface.DEFAULT_BOLD
                else -> Typeface.DEFAULT
            }
        }

    private fun rounded(fill: Int, stroke: Int, r: Float = dp(8f)) = GradientDrawable().apply {
        setColor(fill)
        setStroke(dpi(1), stroke)
        cornerRadius = r
    }

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        setTextColor(Pal.TEXT)
        background = rounded(Pal.BTN, Pal.BTN_EDGE)
        stateListAnimator = null
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dpi(10), dpi(7), dpi(10), dpi(7))
        setOnClickListener { onClick() }
    }

    private fun panel(v: View): View = v.apply { background = rounded(Pal.PANEL, Color.parseColor("#1F2736")) }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pal.BG)
            setPadding(dpi(8), dpi(8), dpi(8), dpi(4))
        }
        // 標題列
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(tv("◉ AudioScope", 17f, Color.WHITE, bold = true), lp(0, WRAP, 1f))
        timeText = tv("—", 14f, Color.WHITE, mono = true)
        header.addView(timeText)
        root.addView(header, lp(MATCH, WRAP))

        // 按鈕列
        val bar = LinearLayout(this).apply { setPadding(0, dpi(6), 0, dpi(6)) }
        fun addBtn(b: Button, weight: Float) {
            bar.addView(b, lp(0, WRAP, weight).apply { marginEnd = dpi(5) })
        }
        addBtn(btn("開啟") { openFile() }, 1f)
        btnPlay = btn("▶ 播放") { togglePlay() }
        addBtn(btnPlay, 1.2f)
        addBtn(btn("■") { stopPlay() }, 0.6f)
        btnMic = btn("● 麥克風") { toggleMic() }
        addBtn(btnMic, 1.4f)
        bar.addView(btn("⚙") { showSettings() }, lp(0, WRAP, 0.6f))
        root.addView(bar, lp(MATCH, WRAP))

        // 分頁
        val tabRow = LinearLayout(this)
        tabs = arrayOf("即時", "頻譜圖", "資訊").mapIndexed { i, s ->
            tv(s, 14f, Pal.MUTED, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dpi(8), 0, dpi(8))
                setOnClickListener { selectTab(i) }
            }
        }.toTypedArray()
        for (t in tabs) tabRow.addView(t, lp(0, WRAP, 1f))
        root.addView(tabRow, lp(MATCH, WRAP))

        val content = FrameLayout(this)
        pages = arrayOf(buildLivePage(), buildSpecPage(), buildInfoPage())
        for (p in pages) content.addView(p, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(content, lp(MATCH, 0, 1f))

        statusText = tv("就緒", 11f, Pal.MUTED).apply {
            setPadding(dpi(2), dpi(4), 0, 0)
            maxLines = 1
        }
        root.addView(statusText, lp(MATCH, WRAP))
        selectTab(0)
        return root
    }

    private fun buildLivePage(): View {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        waveform = WaveformView(this).apply {
            visibility = View.GONE
            onSeek = { t -> seekTo(t) }
        }
        p.addView(waveform, lp(MATCH, dpi(52)).apply { bottomMargin = dpi(4) })
        spectrum = SpectrumView(this)
        p.addView(spectrum, lp(MATCH, 0, 1f).apply { bottomMargin = dpi(4) })
        waterfall = WaterfallView(this)
        p.addView(waterfall, lp(MATCH, 0, 1.1f).apply { bottomMargin = dpi(4) })
        meter = MeterView(this)
        p.addView(meter, lp(MATCH, dpi(56)).apply { bottomMargin = dpi(4) })

        val bottom = LinearLayout(this)
        stereo = StereoView(this)
        bottom.addView(stereo, lp(0, MATCH, 1f).apply { marginEnd = dpi(4) })
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pal.PANEL)
            setPadding(dpi(8), dpi(4), dpi(8), dpi(4))
        }
        for ((key, cap) in listOf(
            "pf" to "峰值頻率", "note" to "音名", "M" to "瞬時 M", "S" to "短期 S", "I" to "整合 I", "TP" to "真峰值",
        )) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(tv(cap, 11.5f, Pal.MUTED), lp(0, WRAP, 1f))
            val v = tv("—", 12.5f, Color.WHITE, mono = true)
            rd[key] = v
            row.addView(v)
            grid.addView(row, lp(MATCH, 0, 1f))
        }
        bottom.addView(grid, lp(0, MATCH, 1.1f))
        p.addView(bottom, lp(MATCH, dpi(116)))
        return p
    }

    private fun buildSpecPage(): View {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val r1 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        specScaleBtn = btn("刻度：線性") {
            specView.setLog(!specView.logScale)
            specScaleBtn.text = if (specView.logScale) "刻度：對數" else "刻度：線性"
        }
        r1.addView(specScaleBtn, lp(WRAP, WRAP).apply { marginEnd = dpi(6) })
        r1.addView(btn("自動對比") { autoContrast() }, lp(WRAP, WRAP))
        specRead = tv("", 11f, Color.WHITE, mono = true).apply {
            gravity = Gravity.END
            maxLines = 2
        }
        r1.addView(specRead, lp(0, WRAP, 1f).apply { marginStart = dpi(6) })
        p.addView(r1, lp(MATCH, WRAP))

        val r2 = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpi(4), 0, dpi(4))
        }
        loLabel = tv("下限 −110", 11f, Pal.MUTED, mono = true)
        hiLabel = tv("上限 −10", 11f, Pal.MUTED, mono = true)
        seekLo = SeekBar(this).apply { max = 170; progress = 60 }
        seekHi = SeekBar(this).apply { max = 170; progress = 140 }
        val l = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) {
                val lo = -170f + seekLo.progress
                val hi = -150f + seekHi.progress
                loLabel.text = "下限 ${lo.toInt()}".replace('-', '−')
                hiLabel.text = "上限 ${hi.toInt()}".replace('-', '−')
                if (fromUser) specView.setLevels(lo, hi)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        }
        seekLo.setOnSeekBarChangeListener(l)
        seekHi.setOnSeekBarChangeListener(l)
        r2.addView(loLabel, lp(dpi(64), WRAP))
        r2.addView(seekLo, lp(0, WRAP, 1f))
        r2.addView(hiLabel, lp(dpi(64), WRAP))
        r2.addView(seekHi, lp(0, WRAP, 1f))
        p.addView(r2, lp(MATCH, WRAP))

        specView = SpectrogramView(this).apply {
            onSeek = { t -> seekTo(t) }
            onReadout = { s -> specRead.text = s }
        }
        p.addView(specView, lp(MATCH, 0, 1f))
        p.addView(
            tv(
                "兩指縮放、單指拖曳、點一下跳到該時間、點兩下還原。找頻譜裡的隱藏圖像：用線性刻度、按「自動對比」，" +
                    "放大高頻區（15–22 kHz）。MP3 等有損格式通常會切掉 16 kHz 以上。",
                10.5f, Pal.MUTED,
            ).apply { setPadding(0, dpi(4), 0, 0) },
            lp(MATCH, WRAP),
        )
        return p
    }

    private fun buildInfoPage(): View {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        infoStatus = tv("開啟音檔後顯示完整資訊", 12f, Pal.MUTED)
        r.addView(infoStatus, lp(0, WRAP, 1f))
        r.addView(btn("複製") { copyReport() }, lp(WRAP, WRAP).apply { marginEnd = dpi(6) })
        r.addView(btn("分享") { shareReport() }, lp(WRAP, WRAP))
        p.addView(r, lp(MATCH, WRAP))
        infoList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(4), 0, dpi(4), dpi(16))
        }
        val sv = ScrollView(this).apply { addView(infoList) }
        p.addView(sv, lp(MATCH, 0, 1f))
        return p
    }

    private fun selectTab(i: Int) {
        for (k in tabs.indices) {
            val on = k == i
            tabs[k].setTextColor(if (on) Color.WHITE else Pal.MUTED)
            tabs[k].background = if (on) rounded(Pal.SEL, Pal.ACCENT, dp(6f)) else null
            pages[k].visibility = if (on) View.VISIBLE else View.GONE
        }
    }

    // ═════════════════════════ 檔案 ═════════════════════════
    private fun openFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "application/ogg", "video/mp4"))
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_OPEN)
        } catch (e: Exception) {
            toast("找不到檔案選擇器：${e.message}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK) data?.data?.let { load(it) }
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            else -> null
        }
        if (uri != null) load(uri)
    }

    private fun load(uri: Uri) {
        if (live != null) stopLive()
        player?.release()
        player = null
        fileSource = null
        source = null
        audio = null
        result = null
        val g = ++gen
        statusText.text = "解碼中…"
        infoStatus.text = "解碼中…"
        specView.setSpec(null)
        specView.setHint("解碼中…")
        updateButtons()
        Thread {
            try {
                var lastPct = -1
                val (a, m) = AudioDecoder.decode(this, uri) { p ->
                    val pct = (p * 100).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        ui.post { if (g == gen) statusText.text = "解碼中 $pct%" }
                    }
                }
                val env = Analyzer.envelope(a)
                ui.post { if (g == gen) onDecoded(a, m, env) }
                val res = Analyzer.run(a, m) { pct, msg ->
                    ui.post {
                        if (g == gen) {
                            infoStatus.text = "分析中 $pct%：$msg"
                            specView.setHint("分析中 $pct%")
                        }
                    }
                }
                ui.post { if (g == gen) onAnalyzed(res) }
            } catch (e: OutOfMemoryError) {
                ui.post { if (g == gen) showError("檔案太大，記憶體不足。可試試較短的檔案。") }
            } catch (e: Exception) {
                ui.post { if (g == gen) showError(e.message ?: e.javaClass.simpleName) }
            }
        }.start()
    }

    private fun onDecoded(a: DecodedAudio, m: FileMeta, env: Envelope) {
        audio = a
        meta = m
        val p = Player(a) { onPlayEnded() }
        player = p
        fileSource = FileSource(a, p)
        source = fileSource
        waveform.visibility = View.VISIBLE
        waveform.setEnvelope(env, a.duration)
        for (k in listOf("I", "TP")) rd[k]?.text = "…"
        sections = listOf(Analyzer.quick(a, m))
        renderInfo()
        applyDsp()
        statusText.text = "${m.name} · ${a.sampleRate} Hz · ${a.channelCount} 聲道 · ${m.codecName}"
        updateButtons()
    }

    private fun onAnalyzed(r: AnalysisResult) {
        result = r
        sections = r.sections
        renderInfo()
        infoStatus.text = "分析完成"
        rd["I"]?.text = Fmt.db(r.integrated, "LUFS", 1)
        rd["TP"]?.text = Fmt.db(r.truePeakDb, "dBTP", 1)
        specView.setColormap(cmap)
        specView.setSpec(r.spec)
        autoContrast()
    }

    private fun showError(msg: String) {
        statusText.text = "讀取失敗：$msg"
        infoStatus.text = "讀取失敗"
        specView.setHint("讀取失敗")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("無法讀取這個檔案")
            .setMessage(msg)
            .setPositiveButton("確定", null)
            .show()
    }

    private fun autoContrast() {
        val (lo, hi) = specView.autoContrast() ?: return
        seekLo.progress = (lo + 170).toInt().coerceIn(0, 170)
        seekHi.progress = (hi + 150).toInt().coerceIn(0, 170)
    }

    private fun renderInfo() {
        infoList.removeAllViews()
        for (s in sections) {
            infoList.addView(tv(s.title, 14.5f, Pal.ACCENT, bold = true).apply { setPadding(0, dpi(14), 0, dpi(4)) })
            for (r in s.rows) {
                val row = LinearLayout(this).apply { setPadding(0, dpi(4), 0, dpi(4)) }
                row.addView(tv(r.key, 12.5f, Pal.MUTED), lp(0, WRAP, 0.42f).apply { marginEnd = dpi(8) })
                row.addView(
                    tv(r.value, 12.5f, if (r.warn) Pal.WARN else Pal.TEXT).apply { setTextIsSelectable(true) },
                    lp(0, WRAP, 0.58f),
                )
                infoList.addView(row, lp(MATCH, WRAP))
                infoList.addView(View(this).apply { setBackgroundColor(Pal.GRID) }, lp(MATCH, 1))
            }
        }
    }

    private fun reportText(): String = buildString {
        append("AudioScope 分析報告\n\n")
        for (s in sections) {
            append("【${s.title}】\n")
            for (r in s.rows) append("  ${r.key}：${r.value}\n")
            append('\n')
        }
    }

    private fun copyReport() {
        if (sections.isEmpty()) return toast("還沒有資料")
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AudioScope", reportText()))
        toast("已複製報告")
    }

    private fun shareReport() {
        if (sections.isEmpty()) return toast("還沒有資料")
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "AudioScope 分析報告")
            putExtra(Intent.EXTRA_TEXT, reportText())
        }
        startActivity(Intent.createChooser(i, "分享報告"))
    }

    // ═════════════════════════ 播放 / 麥克風 ═════════════════════════
    private fun togglePlay() {
        val p = player ?: return toast("請先開啟音檔")
        if (live != null) stopLive()
        if (p.playing) p.pause() else {
            try {
                p.play()
            } catch (e: Exception) {
                toast("無法播放：${e.message}")
            }
        }
        updateButtons()
    }

    private fun stopPlay() {
        player?.stop()
        updateButtons()
    }

    private fun seekTo(t: Double) {
        val a = audio ?: return
        if (live != null) return
        player?.seek((t * a.sampleRate).toInt())
        updateButtons()
    }

    private fun onPlayEnded() = updateButtons()

    private fun toggleMic() {
        if (live != null) {
            stopLive()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        startLive()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startLive()
            else toast("需要麥克風權限才能即時分析")
        }
    }

    private fun startLive() {
        player?.pause()
        val li = LiveInput()
        if (!li.start(this)) return toast("無法開啟麥克風")
        live = li
        source = li
        liveZ.clear()
        lufsM = Double.NEGATIVE_INFINITY
        lufsS = Double.NEGATIVE_INFINITY
        applyDsp()
        rd["I"]?.text = "—"
        rd["TP"]?.text = "—"
        statusText.text = "麥克風：${li.sourceName} · ${li.sampleRate} Hz"
        updateButtons()
    }

    private fun stopLive() {
        live?.stop()
        live = null
        source = fileSource
        applyDsp()
        result?.let {
            rd["I"]?.text = Fmt.db(it.integrated, "LUFS", 1)
            rd["TP"]?.text = Fmt.db(it.truePeakDb, "dBTP", 1)
        }
        statusText.text = meta?.let { "${it.name}" } ?: "已停止麥克風"
        updateButtons()
    }

    private fun updateButtons() {
        val playing = player?.playing == true
        btnPlay.text = if (playing) "❚❚ 暫停" else "▶ 播放"
        btnMic.background = if (live != null) rounded(Pal.SEL, Pal.ACCENT) else rounded(Pal.BTN, Pal.BTN_EDGE)
        btnMic.text = if (live != null) "● 收音中" else "● 麥克風"
        if (playing || live != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ═════════════════════════ 設定 ═════════════════════════
    private fun loadPrefs() {
        fftSize = prefs.getInt("fft", 8192)
        winName = prefs.getString("win", "Hann") ?: "Hann"
        logScale = prefs.getBoolean("log", true)
        cmap = prefs.getString("cmap", "inferno") ?: "inferno"
        chMode = prefs.getInt("ch", 0)
        smoothing = prefs.getFloat("smooth", 0.5f)
        dbLo = prefs.getFloat("lo", -120f)
        dbHi = prefs.getFloat("hi", 0f)
        history = prefs.getFloat("hist", 10f)
        holdOn = prefs.getBoolean("hold", true)
    }

    private fun savePrefs() {
        prefs.edit()
            .putInt("fft", fftSize).putString("win", winName).putBoolean("log", logScale)
            .putString("cmap", cmap).putInt("ch", chMode).putFloat("smooth", smoothing)
            .putFloat("lo", dbLo).putFloat("hi", dbHi).putFloat("hist", history).putBoolean("hold", holdOn)
            .apply()
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(8), dpi(20), 0)
        }
        fun row(label: String, items: Array<String>, sel: Int): Spinner {
            val r = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dpi(4), 0, dpi(4))
            }
            r.addView(tv(label, 14f), lp(0, WRAP, 0.42f))
            val sp = Spinner(this)
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
            sp.setSelection(sel.coerceIn(0, items.size - 1))
            r.addView(sp, lp(0, WRAP, 0.58f))
            box.addView(r)
            return sp
        }
        val spFft = row("FFT 大小", FFT_SIZES, FFT_SIZES.indexOf(fftSize.toString()))
        val spWin = row("窗函數", Windows.names, Windows.names.indexOf(winName))
        val spScale = row("頻率刻度", arrayOf("對數", "線性"), if (logScale) 0 else 1)
        val spCmap = row("色彩", Colormaps.names, Colormaps.names.indexOf(cmap))
        val spCh = row("聲道", CH_MODES, chMode)
        val spLo = row("顯示下限 (dB)", LO_OPTS.map { "$it" }.toTypedArray(), LO_OPTS.indexOf(dbLo.toInt()).let { if (it < 0) 2 else it })
        val spHi = row("顯示上限 (dB)", HI_OPTS.map { "$it" }.toTypedArray(), HI_OPTS.indexOf(dbHi.toInt()).let { if (it < 0) 2 else it })
        val spSm = row("平滑", SMOOTH_OPTS.map { "$it" }.toTypedArray(), SMOOTH_OPTS.indexOfFirst { abs(it - smoothing) < 1e-3 }.let { if (it < 0) 2 else it })
        val spHist = row("瀑布長度", HIST_OPTS.map { "${it.toInt()} 秒" }.toTypedArray(), HIST_OPTS.indexOfFirst { abs(it - history) < 1e-3 }.let { if (it < 0) 2 else it })
        val ck = CheckBox(this).apply {
            text = "峰值保持（橘色虛線）"
            isChecked = holdOn
            setTextColor(Pal.TEXT)
        }
        box.addView(ck)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("分析設定")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("套用") { _, _ ->
                fftSize = FFT_SIZES[spFft.selectedItemPosition].toInt()
                winName = Windows.names[spWin.selectedItemPosition]
                logScale = spScale.selectedItemPosition == 0
                cmap = Colormaps.names[spCmap.selectedItemPosition]
                chMode = spCh.selectedItemPosition
                dbLo = LO_OPTS[spLo.selectedItemPosition].toFloat()
                dbHi = HI_OPTS[spHi.selectedItemPosition].toFloat()
                if (dbHi <= dbLo + 10) dbHi = dbLo + 10
                smoothing = SMOOTH_OPTS[spSm.selectedItemPosition]
                history = HIST_OPTS[spHist.selectedItemPosition]
                holdOn = ck.isChecked
                savePrefs()
                applyDsp()
                specView.setColormap(cmap)
            }
            .setNeutralButton("重設峰值") { _, _ -> spectrum.resetHold() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyDsp() {
        val sr = source?.sampleRate ?: 48000
        val ch = source?.channelCount ?: 2
        fft = Fft(fftSize)
        win = Windows.make(winName, fftSize)
        winNorm = 2.0 / win.sum()
        xbuf = FloatArray(fftSize)
        mag = FloatArray(fftSize / 2 + 1)
        sdb = FloatArray(fftSize / 2 + 1)
        val need = max(fftSize, 3 * sr)
        blk = Array(ch) { FloatArray(need) }
        mPeak = FloatArray(ch) { -120f }
        mHold = FloatArray(ch) { -120f }
        mRms = FloatArray(ch) { -120f }
        holdT = LongArray(ch)
        lastCur = Long.MIN_VALUE
        spectrum.apply {
            sampleRate = sr
            logScale = this@MainActivity.logScale
            dbLo = this@MainActivity.dbLo
            dbHi = this@MainActivity.dbHi
            smoothing = this@MainActivity.smoothing
            holdEnabled = holdOn
            reset()
        }
        waterfall.apply {
            sampleRate = sr
            logScale = this@MainActivity.logScale
            dbLo = this@MainActivity.dbLo
            dbHi = this@MainActivity.dbHi
            historySec = history
            lut = Colormaps.lut(cmap)
            reset()
        }
    }

    // ═════════════════════════ 每格更新 ═════════════════════════
    private fun sel(i: Int): Float {
        val b = blk
        if (b.size == 1) return b[0][i]
        val l = b[0][i]
        val r = b[1][i]
        return when (chMode) {
            1 -> l
            2 -> r
            3 -> (l + r) * 0.5f
            4 -> (l - r) * 0.5f
            else -> {
                var s = 0f
                for (c in b) s += c[i]
                s / b.size
            }
        }
    }

    private fun tick(dt: Float) {
        val src = source ?: return
        val sr = src.sampleRate
        val a = audio
        val p = player
        frameNo++
        if (src === fileSource && a != null && p != null) {
            val pos = p.position().toDouble() / sr
            timeText.text = "${Fmt.time(pos)} / ${Fmt.time(a.duration)}"
            waveform.setPosition(pos)
            specView.setPosition(pos)
            if (!p.playing && btnPlay.text.startsWith("❚")) updateButtons()
        } else if (src === live) {
            timeText.text = "● LIVE ${Fmt.n(sr / 1000.0, 1)} kHz"
        }
        val cur = src.cursor()
        val advanced = cur != lastCur
        lastCur = cur

        val need = blk[0].size
        if (blk.size != src.channelCount) return applyDsp()
        src.latest(need, blk)
        val n = fftSize
        val off = need - n
        for (i in 0 until n) xbuf[i] = sel(off + i) * win[i]
        fft.magnitude(xbuf, mag)
        for (k in mag.indices) sdb[k] = (20 * log10(mag[k] * winNorm + 1e-12)).toFloat()
        val binHz = sr.toDouble() / n
        spectrum.submit(sdb, binHz, advanced, dt)
        waterfall.submit(sdb, binHz, advanced)

        // 峰值頻率（拋物線內插）
        val k0 = max(1, (20 / binHz).toInt())
        var k = k0
        for (j in k0 until sdb.size - 1) if (sdb[j] > sdb[k]) k = j
        if (sdb[k] > dbLo + 15 && k > 0 && k < sdb.size - 1) {
            val al = sdb[k - 1]
            val be = sdb[k]
            val ga = sdb[k + 1]
            val den = al - 2 * be + ga
            val d = if (den != 0f) 0.5f * (al - ga) / den else 0f
            peakFreq = (k + d) * binHz
            val pdb = be - 0.25f * (al - ga) * d
            val (note, cents) = Notes.of(peakFreq)
            peakNote = "$note ${Fmt.signed(cents, 0)}¢"
            spectrum.setPeak(peakFreq, pdb, "${Fmt.n(peakFreq, 1)} Hz $note")
        } else {
            peakFreq = -1.0
            spectrum.clearPeak()
        }

        // 電平表
        val ch = blk.size
        val nt = max(256, (sr * dt * 1.5f).toInt()).coerceAtMost(need)
        val nr = (0.3 * sr).toInt().coerceAtMost(need)
        val now = System.nanoTime()
        for (c in 0 until ch) {
            val x = blk[c]
            var pk = 0f
            for (i in need - nt until need) pk = max(pk, abs(x[i]))
            var ss = 0.0
            for (i in need - nr until need) ss += x[i].toDouble() * x[i]
            val pkDb = ampDb(pk.toDouble()).coerceAtLeast(-120.0).toFloat()
            mRms[c] = ampDb(sqrt(ss / nr)).coerceAtLeast(-120.0).toFloat()
            mPeak[c] = max(pkDb, mPeak[c] - 26f * dt)
            if (pkDb >= mHold[c]) {
                mHold[c] = pkDb
                holdT[c] = now
            } else if (now - holdT[c] > 1_500_000_000L) mHold[c] -= 20f * dt
        }
        meter.set(Analyzer.channelNames(ch), mPeak, mRms, mHold)

        // 響度（每 3 格算一次）
        if (advanced && frameNo % 3 == 0L) {
            val len = min(need, 3 * sr)
            val z = Loudness.liveZ(blk, len, sr, (0.4 * sr).toInt())
            lufsM = Loudness.lufs(z[0])
            lufsS = Loudness.lufs(z[1])
            if (src === live && now - lastZt >= 100_000_000L) {
                lastZt = now
                liveZ.add(z[0])
            }
        }

        // 立體聲
        if (ch >= 2) {
            val l = blk[0]
            val r = blk[1]
            var ll = 0.0
            var rr = 0.0
            var lr = 0.0
            for (i in need - nr until need) {
                ll += l[i].toDouble() * l[i]
                rr += r[i].toDouble() * r[i]
                lr += l[i].toDouble() * r[i]
            }
            val c = if (ll * rr > 1e-20) (lr / sqrt(ll * rr)).toFloat() else 1f
            corrS = 0.8f * corrS + 0.2f * c
            stereo.set(l, r, need - 1024, 1024, corrS)
        } else {
            stereo.set(blk[0], blk[0], need - 1024, 1024, 1f)
        }

        // 文字讀數：每 6 格更新一次，避免一直重排版面
        if (frameNo % 6 == 0L) {
            rd["pf"]?.text = if (peakFreq > 0) "${Fmt.n(peakFreq, 1)} Hz" else "—"
            rd["note"]?.text = if (peakFreq > 0) peakNote else "—"
            rd["M"]?.text = Fmt.db(if (lufsM > -70) lufsM else Double.NEGATIVE_INFINITY, "LUFS", 1)
            rd["S"]?.text = Fmt.db(if (lufsS > -70) lufsS else Double.NEGATIVE_INFINITY, "LUFS", 1)
            if (src === live && liveZ.isNotEmpty()) rd["I"]?.text = Fmt.db(Loudness.integrated(liveZ), "LUFS", 1)
        }
    }
}
