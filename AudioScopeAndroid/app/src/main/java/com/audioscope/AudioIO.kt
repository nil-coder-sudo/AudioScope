package com.audioscope

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

// ════════════════════════════════════════════════════════════════════
//  訊號來源：檔案播放或麥克風，畫面每格從這裡取最新的樣本
// ════════════════════════════════════════════════════════════════════
interface SignalSource {
    val sampleRate: Int
    val channelCount: Int

    /** 單調遞增的位置；沒變代表暫停中。 */
    fun cursor(): Long

    /** 把最新 n 個樣本寫進 out[c][0 until n]，不足的前段補 0。 */
    fun latest(n: Int, out: Array<FloatArray>)
}

class FileSource(private val audio: DecodedAudio, private val player: Player) : SignalSource {
    override val sampleRate get() = audio.sampleRate
    override val channelCount get() = audio.channelCount
    override fun cursor(): Long = player.position().toLong()

    override fun latest(n: Int, out: Array<FloatArray>) {
        val end = player.position().coerceIn(0, audio.frames)
        val start = end - n
        for (c in 0 until audio.channelCount) {
            val dst = out[c]
            if (start >= 0) {
                System.arraycopy(audio.channels[c], start, dst, 0, n)
            } else {
                java.util.Arrays.fill(dst, 0, -start, 0f)
                if (end > 0) System.arraycopy(audio.channels[c], 0, dst, -start, end)
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
//  解碼：WAV 自己解析（保留真實位元深度），其他格式用 MediaCodec
// ════════════════════════════════════════════════════════════════════
object AudioDecoder {
    private class Grow(cap: Int) {
        var a = FloatArray(max(cap, 4096))
        var n = 0
        fun add(v: Float) {
            if (n == a.size) a = a.copyOf(a.size + a.size / 2 + 4096)
            a[n++] = v
        }
        fun result(): FloatArray = if (n == a.size) a else a.copyOf(n)
    }

    private val CODECS = mapOf(
        "audio/mpeg" to ("MP3（MPEG Layer III）" to false),
        "audio/mp4a-latm" to ("AAC" to false),
        "audio/flac" to ("FLAC（無損）" to true),
        "audio/raw" to ("PCM（未壓縮）" to true),
        "audio/opus" to ("Opus" to false),
        "audio/vorbis" to ("Ogg Vorbis" to false),
        "audio/alac" to ("ALAC（Apple 無損）" to true),
        "audio/3gpp" to ("AMR-NB" to false),
        "audio/amr-wb" to ("AMR-WB" to false),
        "audio/ac3" to ("Dolby AC-3" to false),
        "audio/eac3" to ("Dolby E-AC-3" to false),
    )

    fun decode(ctx: Context, uri: Uri, progress: (Float) -> Unit): Pair<DecodedAudio, FileMeta> {
        var name = uri.lastPathSegment ?: "audio"
        var size = -1L
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
        } catch (_: Exception) {
        }
        val tags = readTags(ctx, uri)
        val isWav = ctx.contentResolver.openInputStream(uri)?.use { s ->
            val h = ByteArray(12)
            var r = 0
            while (r < 12) {
                val k = s.read(h, r, 12 - r)
                if (k < 0) break
                r += k
            }
            r == 12 && String(h, 0, 4, Charsets.US_ASCII) == "RIFF" && String(h, 8, 4, Charsets.US_ASCII) == "WAVE"
        } ?: false

        if (isWav) {
            try {
                val (audio, bits, isFloat) = ctx.contentResolver.openInputStream(uri)!!.use { readWav(it, size, progress) }
                val meta = FileMeta(
                    name, size, "audio/wav",
                    if (isFloat) "PCM 浮點（WAV）" else "PCM 整數（WAV）", true, 0, bits, bits, tags,
                )
                return audio to meta
            } catch (e: UnsupportedOperationException) {
                // 不支援的 WAV 子格式 → 交給 MediaCodec
            }
        }
        return decodeWithCodec(ctx, uri, name, size, tags, progress)
    }

    private fun readTags(ctx: Context, uri: Uri): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val keys = listOf(
                MediaMetadataRetriever.METADATA_KEY_TITLE to "標題",
                MediaMetadataRetriever.METADATA_KEY_ARTIST to "演出者",
                MediaMetadataRetriever.METADATA_KEY_ALBUM to "專輯",
                MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST to "專輯演出者",
                MediaMetadataRetriever.METADATA_KEY_COMPOSER to "作曲",
                MediaMetadataRetriever.METADATA_KEY_WRITER to "作詞 / 作者",
                MediaMetadataRetriever.METADATA_KEY_GENRE to "類型",
                MediaMetadataRetriever.METADATA_KEY_YEAR to "年份",
                MediaMetadataRetriever.METADATA_KEY_DATE to "日期",
                MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER to "曲目編號",
                MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER to "光碟編號",
                MediaMetadataRetriever.METADATA_KEY_BITRATE to "位元率（標籤）",
                MediaMetadataRetriever.METADATA_KEY_MIMETYPE to "容器 MIME",
            )
            for ((k, label) in keys) {
                val v = r.extractMetadata(k)
                if (!v.isNullOrBlank()) out.add(label to v.trim())
            }
            if (r.embeddedPicture != null) out.add("內嵌封面" to "有")
        } catch (_: Exception) {
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
        return out
    }

    // ── WAV（PCM 8/16/24/32、浮點 32/64、WAVE_FORMAT_EXTENSIBLE）──
    private fun readWav(input: InputStream, fileSize: Long, progress: (Float) -> Unit): Triple<DecodedAudio, Int, Boolean> {
        val s = BufferedInputStream(input, 1 shl 16)
        fun readFully(b: ByteArray, len: Int = b.size) {
            var r = 0
            while (r < len) {
                val k = s.read(b, r, len - r)
                if (k < 0) throw EOFException()
                r += k
            }
        }
        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val k = s.skip(left)
                if (k <= 0) {
                    if (s.read() < 0) throw EOFException()
                    left--
                } else left -= k
            }
        }
        val hdr = ByteArray(12)
        readFully(hdr)
        var fmtTag = 0
        var ch = 0
        var sr = 0
        var bits = 0
        val ck = ByteArray(8)
        while (true) {
            readFully(ck)
            val id = String(ck, 0, 4, Charsets.US_ASCII)
            val len = ByteBuffer.wrap(ck, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            if (id == "fmt ") {
                val f = ByteArray(len.toInt())
                readFully(f)
                val bb = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
                fmtTag = bb.getShort(0).toInt() and 0xffff
                ch = bb.getShort(2).toInt()
                sr = bb.getInt(4)
                bits = bb.getShort(14).toInt()
                if (fmtTag == 0xFFFE && len >= 26) fmtTag = bb.getShort(24).toInt() and 0xffff
                if (len % 2 == 1L) skip(1)
            } else if (id == "data") {
                if (ch <= 0 || sr <= 0) throw UnsupportedOperationException()
                val isFloat = fmtTag == 3
                if (!(fmtTag == 1 || isFloat) || bits !in intArrayOf(8, 16, 24, 32, 64)) throw UnsupportedOperationException()
                val bps = bits / 8
                var dataLen = len
                if (dataLen == 0xffffffffL || dataLen == 0L) dataLen = if (fileSize > 0) fileSize - 44 else Long.MAX_VALUE
                val frames = (dataLen / (bps * ch)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val out = Array(ch) { FloatArray(frames) }
                val chunkFrames = 8192
                val buf = ByteArray(chunkFrames * bps * ch)
                var f0 = 0
                while (f0 < frames) {
                    val nf = min(chunkFrames, frames - f0)
                    val need = nf * bps * ch
                    var r = 0
                    while (r < need) {
                        val k = s.read(buf, r, need - r)
                        if (k < 0) break
                        r += k
                    }
                    val got = r / (bps * ch)
                    val bb = ByteBuffer.wrap(buf, 0, r).order(ByteOrder.LITTLE_ENDIAN)
                    var p = 0
                    for (i in 0 until got) for (c in 0 until ch) {
                        out[c][f0 + i] = when {
                            isFloat && bits == 32 -> bb.getFloat(p)
                            isFloat && bits == 64 -> bb.getDouble(p).toFloat()
                            bits == 8 -> ((buf[p].toInt() and 0xff) - 128) / 128f
                            bits == 16 -> bb.getShort(p) / 32768f
                            bits == 24 -> ((buf[p].toInt() and 0xff) or ((buf[p + 1].toInt() and 0xff) shl 8) or
                                (buf[p + 2].toInt() shl 16)) / 8388608f
                            else -> (bb.getInt(p) / 2147483648.0).toFloat()
                        }
                        p += bps
                    }
                    f0 += got
                    progress(f0.toFloat() / frames)
                    if (got < nf) break
                }
                val res = if (f0 < frames) Array(ch) { out[it].copyOf(f0) } else out
                return Triple(DecodedAudio(sr, res), bits, isFloat)
            } else {
                skip(len + (len and 1L))
            }
        }
    }

    // ── 其他格式：MediaExtractor + MediaCodec ──
    private fun decodeWithCodec(
        ctx: Context, uri: Uri, name: String, size: Long,
        tags: List<Pair<String, String>>, progress: (Float) -> Unit,
    ): Pair<DecodedAudio, FileMeta> {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        var track = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i
                fmt = f
                break
            }
        }
        if (track < 0 || fmt == null) {
            ex.release()
            throw IllegalArgumentException("這個檔案裡找不到音訊軌")
        }
        ex.selectTrack(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val bitrate = if (fmt.containsKey(MediaFormat.KEY_BIT_RATE)) fmt.getInteger(MediaFormat.KEY_BIT_RATE) else 0
        var srcBits: Int? = when {
            fmt.containsKey("bits-per-sample") -> fmt.getInteger("bits-per-sample")
            fmt.containsKey(MediaFormat.KEY_PCM_ENCODING) -> encBits(fmt.getInteger(MediaFormat.KEY_PCM_ENCODING))
            else -> null
        }
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var outEnc = AudioFormat.ENCODING_PCM_16BIT
        val est = if (durUs > 0) (durUs * sr / 1_000_000L + sr).toInt() else sr * 60
        var bufs = Array(ch) { Grow(est) }
        var inDone = false
        var outDone = false
        var idle = 0
        var lastP = -1
        try {
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                            if (durUs > 0) {
                                val p = (ex.sampleTime * 100 / durUs).toInt()
                                if (p != lastP) {
                                    lastP = p
                                    progress(p / 100f)
                                }
                            }
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) {
                    idle = 0
                    val ob = codec.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        append(ob.slice(), outEnc, ch, bufs)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val nch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) outEnc = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    if (nch != ch && bufs[0].n == 0) {
                        ch = nch
                        bufs = Array(ch) { Grow(est) }
                    }
                } else if (inDone && ++idle > 300) {
                    break // 解碼器沒送出結束旗標，避免卡死
                }
            }
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            codec.release()
            ex.release()
        }
        if (bufs[0].n == 0) throw IllegalStateException("解碼後沒有任何音訊資料")
        val (codecName, lossless) = CODECS[mime] ?: (mime.removePrefix("audio/").uppercase() to false)
        if (!lossless) srcBits = null
        val meta = FileMeta(name, size, mime, codecName, lossless, bitrate, srcBits, encBits(outEnc), tags)
        return DecodedAudio(sr, Array(ch) { bufs[it].result() }) to meta
    }

    private fun encBits(enc: Int): Int = when (enc) {
        AudioFormat.ENCODING_PCM_8BIT -> 8
        AudioFormat.ENCODING_PCM_FLOAT -> 32
        21 -> 24 // ENCODING_PCM_24BIT_PACKED
        22 -> 32 // ENCODING_PCM_32BIT
        else -> 16
    }

    private fun append(bb: ByteBuffer, enc: Int, ch: Int, out: Array<Grow>) {
        bb.order(ByteOrder.LITTLE_ENDIAN)
        when (enc) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = bb.asFloatBuffer()
                val frames = fb.remaining() / ch
                for (i in 0 until frames) for (c in 0 until ch) out[c].add(fb.get())
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val frames = bb.remaining() / ch
                for (i in 0 until frames) for (c in 0 until ch) out[c].add(((bb.get().toInt() and 0xff) - 128) / 128f)
            }
            21 -> {
                val frames = bb.remaining() / (3 * ch)
                for (i in 0 until frames) for (c in 0 until ch) {
                    val b0 = bb.get().toInt() and 0xff
                    val b1 = bb.get().toInt() and 0xff
                    val b2 = bb.get().toInt()
                    out[c].add(((b2 shl 16) or (b1 shl 8) or b0) / 8388608f)
                }
            }
            22 -> {
                val ib = bb.asIntBuffer()
                val frames = ib.remaining() / ch
                for (i in 0 until frames) for (c in 0 until ch) out[c].add((ib.get() / 2147483648.0).toFloat())
            }
            else -> {
                val sb = bb.asShortBuffer()
                val frames = sb.remaining() / ch
                for (i in 0 until frames) for (c in 0 until ch) out[c].add(sb.get() / 32768f)
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
//  播放：AudioTrack 串流，位置由播放頭回報（畫面與聲音同步）
// ════════════════════════════════════════════════════════════════════
class Player(private val audio: DecodedAudio, private val onEnded: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var volume = 1f
    var playing = false
        private set
    private var track: AudioTrack? = null
    private var writer: Thread? = null
    @Volatile private var stopReq = false
    private var startFrame = 0
    private var pausedFrame = 0
    private var session = 0
    private val outCh = min(2, audio.channelCount)

    fun position(): Int {
        val t = track
        return if (playing && t != null) min(audio.frames, startFrame + t.playbackHeadPosition) else pausedFrame
    }

    fun play() {
        if (playing) return
        if (pausedFrame >= audio.frames - 1) pausedFrame = 0
        startFrame = pausedFrame
        val mask = if (outCh == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(audio.sampleRate, mask, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(audio.sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(mask)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuf, 4096 * outCh * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        stopReq = false
        playing = true
        val my = ++session
        val from = startFrame
        t.play()
        writer = Thread({ writeLoop(t, my, from) }, "AudioScope-player").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun writeLoop(t: AudioTrack, my: Int, from: Int) {
        val chunk = 2048
        val buf = FloatArray(chunk * outCh)
        var pos = from
        val chs = audio.channels
        try {
            while (!stopReq && pos < audio.frames) {
                val n = min(chunk, audio.frames - pos)
                val v = volume
                var k = 0
                for (i in 0 until n) for (c in 0 until outCh) buf[k++] = chs[c][pos + i] * v
                var off = 0
                val total = n * outCh
                while (off < total && !stopReq) {
                    val w = t.write(buf, off, total - off, AudioTrack.WRITE_NON_BLOCKING)
                    if (w < 0) {
                        stopReq = true
                        break
                    }
                    if (w == 0) Thread.sleep(4) else off += w
                }
                pos += n
            }
            if (!stopReq) {
                val deadline = System.currentTimeMillis() + 3000
                while (!stopReq && from + t.playbackHeadPosition < audio.frames &&
                    System.currentTimeMillis() < deadline
                ) Thread.sleep(15)
                if (!stopReq) main.post { if (session == my && playing) finishAtEnd() }
            }
        } catch (_: InterruptedException) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun finishAtEnd() {
        teardown()
        pausedFrame = audio.frames
        onEnded()
    }

    private fun teardown() {
        stopReq = true
        val w = writer
        writer = null
        if (w != null && w !== Thread.currentThread()) {
            try {
                w.join(500)
            } catch (_: InterruptedException) {
            }
        }
        track?.let {
            try {
                it.pause()
                it.flush()
                it.release()
            } catch (_: Exception) {
            }
        }
        track = null
        playing = false
    }

    fun pause() {
        if (!playing) return
        val p = position()
        teardown()
        pausedFrame = p
    }

    fun seek(frame: Int) {
        val was = playing
        if (was) teardown()
        pausedFrame = frame.coerceIn(0, max(0, audio.frames - 1))
        if (was) play()
    }

    fun stop() {
        pause()
        pausedFrame = 0
    }

    fun release() = teardown()
}

// ════════════════════════════════════════════════════════════════════
//  麥克風：優先用「未處理」音源（沒有自動增益 / 降噪），環形緩衝 10 秒
// ════════════════════════════════════════════════════════════════════
class LiveInput : SignalSource {
    override var sampleRate = 48000
        private set
    override var channelCount = 1
        private set
    var sourceName = ""
        private set
    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var ring: Array<FloatArray> = arrayOf(FloatArray(1))
    private var size = 1
    private var w = 0
    private var total = 0L
    private val lock = Any()

    @SuppressLint("MissingPermission")
    fun start(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sources = ArrayList<Pair<Int, String>>()
        if (unprocessed) sources.add(MediaRecorder.AudioSource.UNPROCESSED to "未處理（無自動增益）")
        sources.add(MediaRecorder.AudioSource.VOICE_RECOGNITION to "語音辨識（低處理）")
        sources.add(MediaRecorder.AudioSource.MIC to "一般麥克風")
        for ((src, label) in sources) for (sr in intArrayOf(48000, 44100)) for (ch in intArrayOf(2, 1)) {
            val mask = if (ch == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val min = AudioRecord.getMinBufferSize(sr, mask, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val r = try {
                AudioRecord(src, sr, mask, AudioFormat.ENCODING_PCM_16BIT, max(min * 4, sr / 5 * ch * 2))
            } catch (_: Exception) {
                null
            } ?: continue
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                continue
            }
            try {
                r.startRecording()
            } catch (_: Exception) {
                r.release()
                continue
            }
            rec = r
            sampleRate = sr
            channelCount = ch
            sourceName = "$label · ${if (ch == 2) "立體聲" else "單聲道"}"
            size = sr * 10
            ring = Array(ch) { FloatArray(size) }
            w = 0
            total = 0
            running = true
            thread = Thread({ loop(r) }, "AudioScope-mic").apply { start() }
            return true
        }
        return false
    }

    private fun loop(r: AudioRecord) {
        val chn = channelCount
        val buf = ShortArray(1024 * chn)
        while (running) {
            val n = r.read(buf, 0, buf.size)
            if (n < 0) break
            if (n == 0) continue
            val frames = n / chn
            synchronized(lock) {
                var k = 0
                for (i in 0 until frames) {
                    for (c in 0 until chn) ring[c][w] = buf[k++] / 32768f
                    if (++w == size) w = 0
                }
                total += frames
            }
        }
    }

    fun stop() {
        running = false
        rec?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
        }
        try {
            thread?.join(500)
        } catch (_: InterruptedException) {
        }
        rec?.release()
        rec = null
    }

    override fun cursor(): Long = synchronized(lock) { total }

    override fun latest(n: Int, out: Array<FloatArray>) {
        synchronized(lock) {
            val m = min(n, size)
            val pad = n - m
            for (c in 0 until channelCount) {
                val dst = out[c]
                if (pad > 0) java.util.Arrays.fill(dst, 0, pad, 0f)
                var s = w - m
                if (s < 0) s += size
                val first = min(m, size - s)
                System.arraycopy(ring[c], s, dst, pad, first)
                if (first < m) System.arraycopy(ring[c], 0, dst, pad + first, m - first)
            }
        }
    }
}
