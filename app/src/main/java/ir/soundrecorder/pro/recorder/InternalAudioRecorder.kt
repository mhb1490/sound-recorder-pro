package ir.soundrecorder.pro.recorder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import ir.soundrecorder.pro.storage.AudioStorage
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/** Only playback that the source app and Android explicitly allow. */
class InternalAudioRecorder(private val context: Context, private val onResult: (String?) -> Unit,
    private val onError: (Throwable) -> Unit) {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private val rate = 48000
    private val channels = 2
    private val frameBytes = channels * 2
    private val segmentBytes = rate.toLong() * frameBytes * 300

    fun start(projection: MediaProjection) {
        check(worker == null)
        val mask = AudioFormat.CHANNEL_IN_STEREO
        val min = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        require(min > 0) { "فرمت ضبط صدای داخلی پشتیبانی نمی‌شود" }
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate).setChannelMask(mask).build()
        val record = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(maxOf(min * 2, 32768))
            .setAudioPlaybackCaptureConfig(config).build()
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "ضبط صدای داخلی آماده نیست" }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            running.set(true)
            worker = Thread({ capture(record) }, "PlaybackCapture").apply { start() }
        } catch (t: Throwable) {
            running.set(false)
            runCatching { record.release() }
            throw t
        }
    }

    private fun capture(record: AudioRecord) {
        var file: File? = null
        var out: RandomAccessFile? = null
        var bytes = 0L
        var hasSignal = false
        var hadData = false
        fun finish() {
            val f = file ?: return
            val stream = checkNotNull(out)
            try { if (bytes > 0) writeHeader(stream, bytes) } finally {
                stream.close(); out = null; file = null
            }
            if (bytes == 0L) f.delete() else {
                val warning = AudioStorage.finish(context, f)
                onResult(listOfNotNull(warning, if (!hasSignal) "صدای داخلی این بخش کاملاً ساکت بود؛ ممکن است منبع اجازه ضبط ندهد." else null).joinToString(" | ").takeIf { it.isNotBlank() })
            }
            bytes = 0; hasSignal = false
        }
        val buffer = ByteArray(8192)
        try {
            while (running.get()) {
                val n = record.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                if (n < 0) error("AudioRecord read failed: $n")
                if (n == 0) { Thread.sleep(10); continue }
                if (out == null) {
                    val f = AudioStorage.temporary(context, "internal", "wav")
                    file = f
                    out = RandomAccessFile(f, "rw").apply { setLength(0); write(ByteArray(44)) }
                }
                check(n % frameBytes == 0) { "PCM frame alignment error" }
                out!!.write(buffer, 0, n)
                bytes += n
                hadData = true
                if (!hasSignal) for (i in 0 until n) if (buffer[i].toInt() != 0) { hasSignal = true; break }
                if (bytes >= segmentBytes) finish()
            }
        } catch (t: Throwable) { onError(t) }
        finally {
            running.set(false)
            runCatching { record.stop() }
            runCatching { record.release() }
            try { finish() } catch (t: Throwable) { onError(t); runCatching { out?.close() } }
            if (!hadData) onResult("هیچ نمونه صوتی از صدای داخلی دریافت نشد.")
        }
    }

    private fun writeHeader(out: RandomAccessFile, bytes: Long) {
        fun intLE(n: Long) { repeat(4) { out.write((n shr (it * 8)).toInt() and 255) } }
        fun shortLE(n: Int) { out.write(n and 255); out.write((n shr 8) and 255) }
        out.seek(0)
        out.writeBytes("RIFF"); intLE(36 + bytes); out.writeBytes("WAVEfmt "); intLE(16)
        shortLE(1); shortLE(channels); intLE(rate.toLong()); intLE(rate.toLong() * frameBytes)
        shortLE(frameBytes); shortLE(16); out.writeBytes("data"); intLE(bytes)
    }

    fun stop() {
        running.set(false)
        worker?.join()
        worker = null
    }
}
